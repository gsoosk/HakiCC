package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Optimistic Concurrency Control (OCC) implementation.
 *
 * Read phase: reads go directly to the store (no locking), recording per-key
 * versions via putIfAbsent (first read version is kept).
 * Writes are buffered locally in the transaction's write set.
 *
 * Validation+Write phase (at commit): acquire per-key locks in sorted order
 * (mirrors original getOCCtoLock), validate all read versions are still current
 * (mirrors original OCCVersion check), then apply writes to store and increment
 * versions (mirrors original updateOCC).
 */
class GeneratedCC extends ConcurrencyControl {

    private static final Object NULL_VALUE = new Object();

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final AtomicInteger tsCounter = new AtomicInteger(0);

    // Per-key version counters (mirrors original OCCVersion: Map<String, AtomicInteger>)
    private final ConcurrentHashMap<String, AtomicInteger> versionMap = new ConcurrentHashMap<>();

    // Per-key locks for the validation+write critical section
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        int ts = tsCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, ts);
        txn.meta.put("readVersions", new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Integer> getReadVersions(Transaction txn) {
        return (ConcurrentHashMap<String, Integer>) txn.meta.get("readVersions");
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Read from local write set first
        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);

        // Snapshot version BEFORE reading value to avoid TOCTOU race:
        int version = versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).get();

        // Read from store (no locking in OCC read phase)
        Object value = store.get(key);

        // Record version; putIfAbsent keeps first read version
        getReadVersions(txn).putIfAbsent(key, version);

        txn.readSet.put(key, value == null ? NULL_VALUE : value);
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Initialize version counter
        versionMap.computeIfAbsent(key, k -> new AtomicInteger(0));

        // Buffer write locally (mirrors original: tx.addOCCWrite(key, d, version))
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED)
            return CommitResult.failure("Already aborted");
        if (txn.status == TxStatus.COMMITTED)
            return CommitResult.failure("Already committed");

        ConcurrentHashMap<String, Integer> readVersions = getReadVersions(txn);

        // Compute sorted key set: union of read and write keys
        // (mirrors original getOCCtoLock: sorted union of OCCReadSet + OCCWriteSet.keySet())
        Set<String> allKeys = new HashSet<>(readVersions.keySet());
        allKeys.addAll(txn.writeSet.keySet());
        List<String> sortedKeys = new ArrayList<>(allKeys);
        sortedKeys.sort(String::compareTo);

        // Acquire per-key locks in sorted order (deadlock-free)
        List<ReentrantLock> acquiredLocks = new ArrayList<>(sortedKeys.size());
        for (String key : sortedKeys) {
            ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
            lock.lock();
            acquiredLocks.add(lock);
        }

        try {
            // Validation phase: check all read versions are still current
            // (mirrors original: OCCVersion.get(key).get() == tx.getOCCReadVersion(key))
            for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
                int currentVersion = versionMap.get(entry.getKey()).get();
                if (currentVersion != entry.getValue()) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("OCC validation failed");
                }
            }

            // Write phase: apply writes to store and increment versions
            // (mirrors original updateOCC: OCCVersion.get(key).incrementAndGet() + store write)
            store.updateAll(txn.writeSet);
            for (String key : txn.writeSet.keySet()) {
                versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(tsCounter.incrementAndGet());
        } finally {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
    }
}
