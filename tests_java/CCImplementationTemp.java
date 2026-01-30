
package tests_java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of the Semantic-Aware Latching and Optimistic Delta Application (SALVO) protocol.
 * This protocol analyzes transaction semantics to apply optimized concurrency patterns.
 */
class GeneratedCC extends ConcurrencyControl {

    /**
     * A wrapper class to associate a version number with each value stored in the KVStore.
     * This is fundamental for the optimistic concurrency control parts of the protocol.
     */
    private static class VersionedValue {
        final Object value;
        final long version;

        VersionedValue(Object value, long version) {
            this.value = value;
            this.version = version;
        }
    }

    // A sentinel object to represent null values in ConcurrentHashMaps.
    private static final Object NULL_SENTINEL = new Object();

    // Global state for the CC protocol
    private final AtomicInteger tx_counter = new AtomicInteger(0);
    private final AtomicInteger ts_counter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ReentrantLock> lockManager = new ConcurrentHashMap<>();

    // Standardized keys for the Transaction's 'meta' map to avoid magic strings
    private static final String META_VERSIONS_READ = "versions_read";
    private static final String META_DELTAS = "deltas";
    private static final String META_IS_NEW_ORDER = "is_new_order";
    private static final String META_PRIVATE_WRITES = "private_writes";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = tx_counter.incrementAndGet();
        int startTs = ts_counter.incrementAndGet();
        Transaction txn = new Transaction(txid, startTs);
        
        txn.meta.put(META_VERSIONS_READ, new ConcurrentHashMap<String, Long>());
        txn.meta.put(META_DELTAS, new ConcurrentHashMap<String, Long>());
        txn.meta.put(META_PRIVATE_WRITES, new ConcurrentHashMap<String, Object>());
        txn.meta.put(META_IS_NEW_ORDER, false);
        
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> privateWrites = (Map<String, Object>) txn.meta.get(META_PRIVATE_WRITES);
        if (privateWrites.containsKey(key)) return privateWrites.get(key);

        if (key.startsWith("d_") && key.endsWith("_next_oid")) {
            txn.meta.put(META_IS_NEW_ORDER, true);
            Long newOid = store.increment(key, 1);
            return (newOid != null) ? newOid - 1 : 1;
        }

        Object rawValue = store.get(key);
        Object unwrappedValue = rawValue;
        if (unwrappedValue instanceof VersionedValue) {
            unwrappedValue = ((VersionedValue) unwrappedValue).value;
        }
        if (unwrappedValue instanceof AtomicLong) {
            unwrappedValue = ((AtomicLong) unwrappedValue).get();
        }
        
        if (key.startsWith("c_") && key.endsWith("_bal") && (Boolean) txn.meta.get(META_IS_NEW_ORDER)) {
            return unwrappedValue;
        }
        
        long version = (rawValue instanceof VersionedValue) ? ((VersionedValue) rawValue).version : 0L;
        @SuppressWarnings("unchecked")
        Map<String, Long> versionsRead = (Map<String, Long>) txn.meta.get(META_VERSIONS_READ);
        versionsRead.put(key, version);
        
        txn.readSet.put(key, unwrappedValue == null ? NULL_SENTINEL : unwrappedValue);

        return unwrappedValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if (key.startsWith("d_") && key.endsWith("_next_oid")) return;

        if (key.startsWith("o_")) {
             @SuppressWarnings("unchecked")
             Map<String, Object> privateWrites = (Map<String, Object>) txn.meta.get(META_PRIVATE_WRITES);
             privateWrites.put(key, value);
             return;
        }
        
        boolean isCommutative = key.contains("_ytd") || (key.startsWith("c_") && key.endsWith("_bal"));
        if (isCommutative && txn.readSet.containsKey(key)) {
            Object oldValueObj = txn.readSet.get(key);
            if (oldValueObj == NULL_SENTINEL) {
                oldValueObj = 0L;
            }

            if (oldValueObj instanceof Number && value instanceof Number) {
                long delta = ((Number) value).longValue() - ((Number) oldValueObj).longValue();
                @SuppressWarnings("unchecked")
                Map<String, Long> deltas = (Map<String, Long>) txn.meta.get(META_DELTAS);
                deltas.merge(key, delta, Long::sum);
                txn.writeSet.put(key, value);
                return;
            }
        }
        
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return CommitResult.failure("Transaction not active.");

        @SuppressWarnings("unchecked")
        Map<String, Long> deltas = (Map<String, Long>) txn.meta.get(META_DELTAS);
        
        java.util.Set<String> keysToLock = new java.util.HashSet<>(txn.writeSet.keySet());
        keysToLock.addAll(deltas.keySet());
        ArrayList<String> sortedLockKeys = new ArrayList<>(keysToLock);
        Collections.sort(sortedLockKeys);

        ArrayList<ReentrantLock> heldLocks = new ArrayList<>();
        try {
            for (String key : sortedLockKeys) {
                ReentrantLock lock = lockManager.computeIfAbsent(key, k -> new ReentrantLock(true));
                lock.lock();
                heldLocks.add(lock);
            }

            @SuppressWarnings("unchecked")
            Map<String, Long> versionsRead = (Map<String, Long>) txn.meta.get(META_VERSIONS_READ);
            for (Map.Entry<String, Long> entry : versionsRead.entrySet()) {
                String key = entry.getKey();
                long readVersion = entry.getValue();
                Object rawValue = store.get(key);
                long currentVersion = (rawValue instanceof VersionedValue) ? ((VersionedValue) rawValue).version : 0L;

                if (readVersion != currentVersion) {
                    rollback(txn, store);
                    return CommitResult.failure("Version conflict on key: " + key);
                }
            }
            
            int commitTs = ts_counter.incrementAndGet();
            
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                if (deltas.containsKey(key)) continue;
                Object rawValue = store.get(key);
                long currentVersion = (rawValue instanceof VersionedValue) ? ((VersionedValue) rawValue).version : 0L;
                store.update(key, new VersionedValue(entry.getValue(), currentVersion + 1));
            }

            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                String key = entry.getKey();
                Object rawValue = store.get(key);
                VersionedValue vv = (rawValue instanceof VersionedValue) ? (VersionedValue) rawValue : new VersionedValue(null, 0L);
                
                Object currentVal = vv.value;
                if (currentVal instanceof AtomicLong) {
                    currentVal = ((AtomicLong)currentVal).get();
                }

                long baseValue = (currentVal instanceof Number) ? ((Number) currentVal).longValue() : 0L;
                long newNumericValue = baseValue + entry.getValue();
                store.update(key, new VersionedValue(newNumericValue, vv.version + 1));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> privateWrites = (Map<String, Object>) txn.meta.get(META_PRIVATE_WRITES);
            for (Map.Entry<String, Object> entry : privateWrites.entrySet()) {
                store.insert(entry.getKey(), new VersionedValue(entry.getValue(), 0L));
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);

        } finally {
            for (ReentrantLock lock : heldLocks) {
                lock.unlock();
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return;
        txn.status = TxStatus.ABORTED;
        
        txn.readSet.clear();
        txn.writeSet.clear();
        if (txn.meta != null) {
            ((Map<?, ?>) txn.meta.get(META_VERSIONS_READ)).clear();
            ((Map<?, ?>) txn.meta.get(META_DELTAS)).clear();
            ((Map<?, ?>) txn.meta.get(META_PRIVATE_WRITES)).clear();
        }
    }
}
