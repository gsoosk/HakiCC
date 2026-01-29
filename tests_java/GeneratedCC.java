
package tests_java;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, KeyMetadata> keyMetadataMap = new ConcurrentHashMap<>();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    // Enum to represent the type of operation.
    private enum OperationType {
        ADD, SUBTRACT, SET
    }

    /**
     * Represents a pending update to a key.
     * These are stored in the KeyMetadata's pending list.
     */
    private static class UpdateIntent {
        final int txid;
        final OperationType op;
        final Object value; // For SET, this is the value. For ADD/SUBTRACT, this is the delta.
        final Predicate<Long> condition; // For conditional updates (e.g., stock level)
        final AtomicReference<TxStatus> status; // Shared status with the parent transaction

        UpdateIntent(int txid, OperationType op, Object value, Predicate<Long> condition, AtomicReference<TxStatus> status) {
            this.txid = txid;
            this.op = op;
            this.value = value;
            this.condition = condition;
            this.status = status;
        }
    }

    /**
     * Per-key metadata managed by the CC protocol.
     */
    private static class KeyMetadata {
        final AtomicInteger version = new AtomicInteger(0);
        final ReentrantLock lock = new ReentrantLock(); // For exclusive operations like non-commutative writes
        final ConcurrentLinkedQueue<UpdateIntent> pendingUpdates = new ConcurrentLinkedQueue<>();
        // In a real system, a cleanup mechanism would be needed for the pendingUpdates queue.
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, txid);
        // Use meta to store a shared status reference for all intents of this transaction
        txn.meta.put("status_ref", new AtomicReference<>(TxStatus.ACTIVE));
        return txn;
    }

    private KeyMetadata getKeyMetadata(String key) {
        return keyMetadataMap.computeIfAbsent(key, k -> new KeyMetadata());
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        // Read-your-own-writes: Check if we have a local write.
        if (txn.writeSet.containsKey(key)) {
            UpdateIntent ownIntent = (UpdateIntent) txn.writeSet.get(key);
            if (ownIntent.op == OperationType.SET) {
                return ownIntent.value;
            }
        }

        KeyMetadata metadata = getKeyMetadata(key);
        
        // Record the version for OCC validation at commit time.
        txn.readSet.put(key, metadata.version.get());

        Object baseValueObj = store.get(key);
        long currentValue = 0;
        if (baseValueObj instanceof AtomicLong) {
            currentValue = ((AtomicLong) baseValueObj).get();
        } else if (baseValueObj instanceof Number) {
            currentValue = ((Number) baseValueObj).longValue();
        }

        // Apply committed deltas to reconstruct the current value.
        for (UpdateIntent intent : metadata.pendingUpdates) {
            if (intent.status.get() == TxStatus.COMMITTED) {
                long delta = ((Number) intent.value).longValue();
                if (intent.op == OperationType.ADD) {
                    currentValue += delta;
                } else if (intent.op == OperationType.SUBTRACT) {
                    currentValue -= delta;
                }
            }
        }
        
        // Apply this transaction's own uncommitted changes on top.
        if (txn.writeSet.containsKey(key)) {
             UpdateIntent ownIntent = (UpdateIntent) txn.writeSet.get(key);
             long delta = ((Number) ownIntent.value).longValue();
             if (ownIntent.op == OperationType.ADD) {
                 currentValue += delta;
             } else if (ownIntent.op == OperationType.SUBTRACT) {
                 currentValue -= delta;
             }
        }
        
        return (int) currentValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return;
        }

        Object oldValueObj = read(txn, key, store);
        long oldValue = (oldValueObj instanceof Number) ? ((Number) oldValueObj).longValue() : 0;
        long newValue = ((Number) value).longValue();
        long delta = newValue - oldValue;

        @SuppressWarnings("unchecked")
        AtomicReference<TxStatus> statusRef = (AtomicReference<TxStatus>) txn.meta.get("status_ref");
        UpdateIntent intent;

        // Semantic analysis of the write operation
        if (key.startsWith("w_") || key.startsWith("d_") || key.startsWith("c_") || key.startsWith("d_") && key.endsWith("_next_oid")) {
            // These are always commutative additions
            intent = new UpdateIntent(txn.txid, OperationType.ADD, newValue - oldValue, null, statusRef);
            getKeyMetadata(key).pendingUpdates.add(intent);
            txn.writeSet.put(key, intent);
        } else if (key.startsWith("s_")) {
            // Stock updates are subtractions with a condition
            Predicate<Long> stockCondition = stockLevel -> stockLevel >= 10;
            intent = new UpdateIntent(txn.txid, OperationType.SUBTRACT, oldValue - newValue, stockCondition, statusRef);
            getKeyMetadata(key).pendingUpdates.add(intent);
            txn.writeSet.put(key, intent);
        } else {
            // All other writes are treated as non-commutative SETs
            intent = new UpdateIntent(txn.txid, OperationType.SET, value, null, statusRef);
            txn.writeSet.put(key, intent);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        // --- PHASE 1: VALIDATION ---

        // 1. Read-Set Validation (OCC)
        for (String key : txn.readSet.keySet()) {
            int readVersion = (int) txn.readSet.get(key);
            int currentVersion = getKeyMetadata(key).version.get();
            if (readVersion != currentVersion) {
                rollback(txn, store);
                return CommitResult.failure("Read-write conflict on key: " + key);
            }
        }

        // 2. Conditional Write Validation
        for (String key : txn.writeSet.keySet()) {
            UpdateIntent intent = (UpdateIntent) txn.writeSet.get(key);
            if (intent.condition != null) {
                // Reconstruct value *without* this transaction's update to check condition
                Object baseValueObj = store.get(key);
                long projectedValue = (baseValueObj instanceof AtomicLong) ? ((AtomicLong) baseValueObj).get() : 0;

                for (UpdateIntent pending : getKeyMetadata(key).pendingUpdates) {
                    if (pending.txid != txn.txid && pending.status.get() == TxStatus.COMMITTED) {
                        long delta = ((Number) pending.value).longValue();
                        if (pending.op == OperationType.ADD) projectedValue += delta;
                        else if (pending.op == OperationType.SUBTRACT) projectedValue -= delta;
                    }
                }
                // Now apply this transaction's update
                long delta = ((Number) intent.value).longValue();
                if (intent.op == OperationType.ADD) projectedValue += delta;
                else if (intent.op == OperationType.SUBTRACT) projectedValue -= delta;
                
                if (!intent.condition.test(projectedValue)) {
                    rollback(txn, store);
                    return CommitResult.failure("Condition failed for key: " + key);
                }
            }
        }

        // --- PHASE 2: APPLY ---
        
        // Atomically mark the transaction as committed. This is the logical commit point.
        @SuppressWarnings("unchecked")
        AtomicReference<TxStatus> statusRef = (AtomicReference<TxStatus>) txn.meta.get("status_ref");
        if (!statusRef.compareAndSet(TxStatus.ACTIVE, TxStatus.COMMITTED)) {
             return CommitResult.failure("Transaction was aborted during commit");
        }
        txn.status = TxStatus.COMMITTED;

        // Apply non-commutative writes and update versions
        for (String key : txn.writeSet.keySet()) {
            UpdateIntent intent = (UpdateIntent) txn.writeSet.get(key);
            if (intent.op == OperationType.SET) {
                KeyMetadata metadata = getKeyMetadata(key);
                metadata.lock.lock();
                try {
                    store.update(key, intent.value);
                    metadata.version.incrementAndGet();
                } finally {
                    metadata.lock.unlock();
                }
            }
        }

        return CommitResult.success(txn.txid);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            return;
        }
        @SuppressWarnings("unchecked")
        AtomicReference<TxStatus> statusRef = (AtomicReference<TxStatus>) txn.meta.get("status_ref");
        statusRef.set(TxStatus.ABORTED);
        txn.status = TxStatus.ABORTED;
    }
}
