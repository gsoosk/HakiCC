package tests_java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Baseline Strict Two-Phase Locking (2PL) implementation.
 *
 * - Uses per-key shared (S) and exclusive (X) locks.
 * - Locks are held until commit/rollback (strict 2PL).
 * - Deadlocks are prevented with a simple wait-die policy based on Transaction.startTs.
 * - Reads use read-your-own-writes and cache values in the transaction's readSet.
 */
class Baseline2PL extends ConcurrencyControl {

    private enum LockMode { NONE, SHARED, EXCLUSIVE }

    private static final Object NULL_SENTINEL = new Object();

    /** Global logical clock for assigning transaction IDs / start timestamps. */
    private final AtomicInteger timestampSeq = new AtomicInteger(0);
    /** Global commit timestamp generator (monotonic, best-effort). */
    private final AtomicInteger commitSeq = new AtomicInteger(0);

    /**
     * Per-key lock structure. Guarded by this object's monitor.
     */
    private static class KeyLock {
        LockMode mode = LockMode.NONE;
        final List<Transaction> holders = new ArrayList<>();
    }

    /**
     * Simple lock manager: one KeyLock per logical key.
     */
    private static class LockManager {
        private final ConcurrentHashMap<String, KeyLock> locks = new ConcurrentHashMap<>();

        KeyLock getKeyLock(String key) {
            return locks.computeIfAbsent(key, k -> new KeyLock());
        }
    }

    /**
     * Used internally to abort a transaction when the wait-die policy chooses "die".
     */
    private static class LockAbortException extends Exception {
        LockAbortException(String msg) { super(msg); }
    }

    private final LockManager lockManager = new LockManager();

    private static final String HELD_LOCKS_KEY = "held_locks";

    @SuppressWarnings("unchecked")
    private Map<String, LockMode> getHeldLocks(Transaction txn) {
        return (Map<String, LockMode>) txn.meta.get(HELD_LOCKS_KEY);
    }

    @Override
    public Transaction begin(KVStore store) {
        int ts = timestampSeq.getAndIncrement();
        Transaction txn = new Transaction(ts, ts);
        txn.meta.put(HELD_LOCKS_KEY, new HashMap<String, LockMode>());
        return txn;
    }

    /**
     * Wait-die deadlock prevention:
     * - If an older transaction requests a conflicting lock, it waits.
     * - If a younger transaction requests and finds an older conflicting holder, it aborts ("dies").
     */
    private void acquireLock(Transaction txn, String key, LockMode requested) throws LockAbortException {
        if (txn.status != TxStatus.ACTIVE) {
            throw new LockAbortException("Transaction not active");
        }

        KeyLock keyLock = lockManager.getKeyLock(key);
        Map<String, LockMode> heldLocks = getHeldLocks(txn);

        synchronized (keyLock) {
            while (true) {
                if (txn.status != TxStatus.ACTIVE) {
                    throw new LockAbortException("Transaction aborted while waiting for lock");
                }

                // Handle lock upgrade if we already hold a lock on this key.
                LockMode currentHeld = heldLocks.getOrDefault(key, LockMode.NONE);
                if (currentHeld == requested || currentHeld == LockMode.EXCLUSIVE) {
                    // Already have sufficient lock.
                    return;
                }

                boolean hasConflict = false;
                boolean youngerThanSomeHolder = false;

                // Check for conflicts with existing holders.
                if (!keyLock.holders.isEmpty()) {
                    for (Transaction holder : keyLock.holders) {
                        if (holder.txid == txn.txid) {
                            continue; // Skip self; handled in upgrade logic above.
                        }
                        LockMode holderMode = getHeldLocks(holder).getOrDefault(key, LockMode.NONE);
                        if (!areCompatible(holderMode, requested)) {
                            hasConflict = true;
                            if (txn.startTs > holder.startTs) {
                                youngerThanSomeHolder = true;
                                break;
                            }
                        }
                    }
                }

                if (!hasConflict) {
                    // Grant / upgrade lock.
                    if (currentHeld == LockMode.NONE) {
                        keyLock.holders.add(txn);
                    }
                    keyLock.mode = combineModes(keyLock.mode, requested);
                    heldLocks.put(key, requested);
                    return;
                }

                // Conflict exists: apply wait-die.
                if (youngerThanSomeHolder) {
                    // Younger txn must abort ("die").
                    txn.status = TxStatus.ABORTED;
                    throw new LockAbortException("Aborted by wait-die policy");
                }

                // Otherwise, this transaction is older than all conflicting holders: wait.
                try {
                    keyLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    txn.status = TxStatus.ABORTED;
                    throw new LockAbortException("Interrupted while waiting for lock");
                }
            }
        }
    }

    private boolean areCompatible(LockMode held, LockMode requested) {
        if (held == LockMode.NONE || requested == LockMode.NONE) return true;
        if (held == LockMode.SHARED && requested == LockMode.SHARED) return true;
        return false; // Any other combination conflicts.
    }

    /**
     * Combine aggregate and requested lock modes for the KeyLock.
     */
    private LockMode combineModes(LockMode existing, LockMode requested) {
        if (existing == LockMode.NONE) return requested;
        if (existing == LockMode.SHARED && requested == LockMode.SHARED) return LockMode.SHARED;
        // Any involvement of EXCLUSIVE results in EXCLUSIVE.
        return LockMode.EXCLUSIVE;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Read-your-own-writes.
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        // Repeatable reads from cache.
        if (txn.readSet.containsKey(key)) {
            Object cached = txn.readSet.get(key);
            return cached == NULL_SENTINEL ? null : cached;
        }

        try {
            acquireLock(txn, key, LockMode.SHARED);
        } catch (LockAbortException e) {
            txn.status = TxStatus.ABORTED;
            return null;
        }

        if (txn.status != TxStatus.ACTIVE) return null;

        Object value = store.get(key);
        if (value instanceof AtomicLong) {
            value = ((AtomicLong) value).get();
        }

        txn.readSet.put(key, value == null ? NULL_SENTINEL : value);
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        try {
            acquireLock(txn, key, LockMode.EXCLUSIVE);
        } catch (LockAbortException e) {
            txn.status = TxStatus.ABORTED;
            return;
        }

        if (txn.status != TxStatus.ACTIVE) return;

        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            // Ensure locks are released even on aborted/finished txns.
            releaseAllLocks(txn);
            return CommitResult.failure("Transaction already " + txn.status);
        }

        // Apply all buffered writes atomically with respect to the lock protocol.
        store.updateAll(txn.writeSet);
        txn.status = TxStatus.COMMITTED;
        releaseAllLocks(txn);

        int commitTs = commitSeq.incrementAndGet();
        return CommitResult.success(commitTs);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            // Already finished; nothing to do but ensure locks are free.
            releaseAllLocks(txn);
            return;
        }
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        releaseAllLocks(txn);
    }

    private void releaseAllLocks(Transaction txn) {
        Map<String, LockMode> heldLocks = getHeldLocks(txn);
        if (heldLocks == null || heldLocks.isEmpty()) return;

        for (String key : heldLocks.keySet()) {
            KeyLock keyLock = lockManager.getKeyLock(key);
            synchronized (keyLock) {
                keyLock.holders.removeIf(t -> t.txid == txn.txid);
                if (keyLock.holders.isEmpty()) {
                    keyLock.mode = LockMode.NONE;
                } else {
                    // Recompute aggregate mode based on remaining holders.
                    LockMode aggregate = LockMode.NONE;
                    for (Transaction holder : keyLock.holders) {
                        LockMode hMode = getHeldLocks(holder).getOrDefault(key, LockMode.NONE);
                        aggregate = combineModes(aggregate, hMode);
                    }
                    keyLock.mode = aggregate;
                }
                keyLock.notifyAll();
            }
        }

        heldLocks.clear();
    }
}

