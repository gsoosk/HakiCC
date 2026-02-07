package tests_java;

import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Baseline Optimistic Concurrency Control (OCC).
 *
 * Classic read-validate-write protocol:
 * - Reads capture value + version under a short per-key lock.
 * - Writes are buffered in the transaction's writeSet.
 * - At commit, we lock all keys in read/write sets, validate versions, then apply writes.
 */
class BaselineOCC extends ConcurrencyControl {

    private static final Object NULL_SENTINEL = new Object();

    /** Per-key lock and version state. */
    private final ConcurrentHashMap<String, ReentrantLock> lockTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> versionTable = new ConcurrentHashMap<>();

    /** Global counters for tx ids and commit timestamps. */
    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final AtomicInteger commitTsCounter = new AtomicInteger(0);

    private static final String READ_VERSIONS_KEY = "read_versions";

    @SuppressWarnings("unchecked")
    private Map<String, Integer> getReadVersions(Transaction txn) {
        return (Map<String, Integer>) txn.meta.get(READ_VERSIONS_KEY);
    }

    private ReentrantLock lockForKey(String key) {
        return lockTable.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private AtomicInteger versionForKey(String key) {
        return versionTable.computeIfAbsent(key, k -> new AtomicInteger(0));
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, commitTsCounter.get());
        txn.meta.put(READ_VERSIONS_KEY, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Read-your-own-writes first.
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        // Repeatable reads from cache.
        if (txn.readSet.containsKey(key)) {
            Object cached = txn.readSet.get(key);
            return cached == NULL_SENTINEL ? null : cached;
        }

        // Lock-protected read of (value, version).
        ReentrantLock lock = lockForKey(key);
        lock.lock();
        try {
            Object value = store.get(key);
            int version = versionForKey(key).get();

            txn.readSet.put(key, value == null ? NULL_SENTINEL : value);
            getReadVersions(txn).put(key, version);
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Ensure we have a version dependency for this key ("write implies read").
        Map<String, Integer> readVersions = getReadVersions(txn);
        if (!readVersions.containsKey(key)) {
            read(txn, key, store);
        }

        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Map<String, Integer> readVersions = getReadVersions(txn);

        // Collect keys to lock (union of read and write sets), in sorted order to prevent deadlock.
        TreeSet<String> keysToLock = new TreeSet<>();
        keysToLock.addAll(readVersions.keySet());
        keysToLock.addAll(txn.writeSet.keySet());

        if (keysToLock.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTsCounter.incrementAndGet());
        }

        ReentrantLock[] heldLocks = new ReentrantLock[keysToLock.size()];
        int idx = 0;
        try {
            // Phase 1: lock all keys.
            for (String key : keysToLock) {
                ReentrantLock lock = lockForKey(key);
                lock.lock();
                heldLocks[idx++] = lock;
            }

            // Phase 2: validate versions.
            for (Map.Entry<String, Integer> e : readVersions.entrySet()) {
                String key = e.getKey();
                int expected = e.getValue();
                int current = versionForKey(key).get();
                if (expected != current) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Validation failed for key: " + key);
                }
            }

            // Phase 3: apply writes and bump versions.
            int commitTs = commitTsCounter.incrementAndGet();
            for (Map.Entry<String, Object> e : txn.writeSet.entrySet()) {
                String key = e.getKey();
                Object newVal = e.getValue();
                Object oldVal = txn.readSet.get(key);

                if (oldVal == NULL_SENTINEL) {
                    // Key did not exist when first read.
                    store.insert(key, newVal);
                } else {
                    // Overwrite existing value.
                    store.update(key, newVal);
                }

                versionForKey(key).incrementAndGet();
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);
        } finally {
            // Phase 4: unlock in reverse order.
            for (int i = heldLocks.length - 1; i >= 0; i--) {
                if (heldLocks[i] != null) {
                    heldLocks[i].unlock();
                }
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        getReadVersions(txn).clear();
    }
}

package tests_java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Baseline Optimistic Concurrency Control (OCC) implementation.
 *
 * - Transactions execute optimistically, buffering writes in Transaction.writeSet.
 * - Each key has an integer version in a global versionManager.
 * - Reads capture (value, version) under a short-term per-key lock to form a consistent snapshot.
 * - Commit follows the classic lock-validate-write-unlock OCC pattern.
 */
class BaselineOCC extends ConcurrencyControl {

    private static final Object NULL_SENTINEL = new Object();

    /** Per-key lock used both for consistent reads and for commit-time locking. */
    private final ConcurrentHashMap<String, ReentrantLock> lockManager = new ConcurrentHashMap<>();
    /** Per-key logical version counter. */
    private final ConcurrentHashMap<String, AtomicInteger> versionManager = new ConcurrentHashMap<>();

    /** Monotonic transaction id and timestamp source. */
    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final AtomicInteger globalTimestamp = new AtomicInteger(0);

    private static final String VERSIONED_READ_SET_KEY = "versioned_read_set";

    public static ConcurrencyControl buildCc() {
        return new BaselineOCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, globalTimestamp.get());
        // txn.readSet holds values; meta map stores versions for each read key.
        txn.meta.put(VERSIONED_READ_SET_KEY, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> getVersionedReadSet(Transaction txn) {
        return (Map<String, Integer>) txn.meta.get(VERSIONED_READ_SET_KEY);
    }

    private ReentrantLock lockForKey(String key) {
        return lockManager.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private AtomicInteger versionRefForKey(String key) {
        return versionManager.computeIfAbsent(key, k -> new AtomicInteger(0));
    }

    /**
     * Perform a lock-protected read of (value, version) for a given key.
     */
    private Object performConsistentRead(Transaction txn, String key, KVStore store) {
        Map<String, Integer> versionedReadSet = getVersionedReadSet(txn);
        ReentrantLock lock = lockForKey(key);
        AtomicInteger versionRef = versionRefForKey(key);

        lock.lock();
        try {
            Object value = store.get(key);
            int version = versionRef.get();

            txn.readSet.put(key, value == null ? NULL_SENTINEL : value);
            versionedReadSet.put(key, version);
            return value;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Read-your-own-writes.
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        if (txn.readSet.containsKey(key)) {
            Object cached = txn.readSet.get(key);
            return cached == NULL_SENTINEL ? null : cached;
        }

        return performConsistentRead(txn, key, store);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Write-implies-read: ensure we have a version dependency for every written key.
        Map<String, Integer> versionedReadSet = getVersionedReadSet(txn);
        if (!versionedReadSet.containsKey(key)) {
            performConsistentRead(txn, key, store);
        }

        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Map<String, Integer> versionedReadSet = getVersionedReadSet(txn);

        // Collect all keys we need to lock for validation and write.
        TreeSet<String> allKeysToLock = new TreeSet<>();
        allKeysToLock.addAll(versionedReadSet.keySet());
        allKeysToLock.addAll(txn.writeSet.keySet());

        if (allKeysToLock.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(globalTimestamp.incrementAndGet());
        }

        List<ReentrantLock> heldLocks = new ArrayList<>(allKeysToLock.size());
        try {
            // Phase 1: lock all keys in canonical (sorted) order to prevent deadlock.
            for (String key : allKeysToLock) {
                ReentrantLock lock = lockForKey(key);
                lock.lock();
                heldLocks.add(lock);
            }

            // Phase 2: validate that all read versions are still current.
            for (Map.Entry<String, Integer> e : versionedReadSet.entrySet()) {
                String key = e.getKey();
                int readVersion = e.getValue();
                int currentVersion = versionRefForKey(key).get();
                if (readVersion != currentVersion) {
                    txn.status = TxStatus.ABORTED;
                    // No state was mutated yet; just abort.
                    return CommitResult.failure("Validation failed for key: " + key);
                }
            }

            // Phase 3: apply writes and bump versions.
            int commitTs = globalTimestamp.incrementAndGet();
            for (Map.Entry<String, Object> e : txn.writeSet.entrySet()) {
                String key = e.getKey();
                Object newVal = e.getValue();
                Object oldVal = txn.readSet.get(key);

                if (oldVal == NULL_SENTINEL) {
                    // Previously non-existent key => insertion.
                    store.insert(key, newVal);
                } else if (isNumeric(oldVal) && isNumeric(newVal)) {
                    long before = toLong(oldVal);
                    long after = toLong(newVal);
                    long delta = after - before;
                    if (delta > 0) {
                        store.increment(key, delta);
                    } else if (delta < 0) {
                        store.decrement(key, -delta);
                    } else {
                        // No change; skip KVStore call but still advance version below.
                    }
                } else {
                    // Non-numeric or mismatched types: overwrite.
                    store.update(key, newVal);
                }

                versionRefForKey(key).incrementAndGet();
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);
        } finally {
            // Phase 4: release all locks.
            for (ReentrantLock lock : heldLocks) {
                lock.unlock();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        getVersionedReadSet(txn).clear();
    }

    private static boolean isNumeric(Object v) {
        return v instanceof Integer || v instanceof Long;
    }

    private static long toLong(Object v) {
        if (v instanceof Integer) return ((Integer) v).longValue();
        if (v instanceof Long) return (Long) v;
        throw new IllegalArgumentException("Expected numeric type, got " + v.getClass());
    }
}

