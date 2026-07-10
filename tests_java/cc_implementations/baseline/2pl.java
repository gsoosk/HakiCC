package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wound-Wait Two-Phase Locking (2PL) concurrency control.
 *
 * Uses a per-transaction AtomicInteger state machine to prevent TOCTOU races
 * between commit and wound operations:
 *   STATE_ACTIVE(0) -> STATE_COMMITTING(1) -> STATE_COMMITTED(2)
 *   STATE_ACTIVE(0) -> STATE_ABORTED(3)
 */
class GeneratedCC extends ConcurrencyControl {

    private static final Object NULL_VALUE = new Object();
    private enum LockMode { SHARED, EXCLUSIVE }

    private static final int STATE_ACTIVE = 0;
    private static final int STATE_COMMITTING = 1;
    private static final int STATE_COMMITTED = 2;
    private static final int STATE_ABORTED = 3;

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final AtomicInteger tsCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, LockEntry> lockTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Transaction> activeTxns = new ConcurrentHashMap<>();


    private static class LockEntry {
        final Set<Integer> holders = new HashSet<>();
        LockMode holdersMode = null;
        final PriorityQueue<Integer> pendingQueue = new PriorityQueue<>();
        final Map<Integer, LockMode> pendingModes = new HashMap<>();

        boolean isHeldBefore(int txid, LockMode mode) {
            if (!holders.contains(txid)) return false;
            if (holdersMode == LockMode.SHARED && mode == LockMode.SHARED) return true;
            return holdersMode == LockMode.EXCLUSIVE;
        }

        boolean canGrant(int txid, LockMode mode) {
            if (isHeldBefore(txid, mode)) return true;

            if (mode == LockMode.EXCLUSIVE) {
                if (holders.isEmpty() || (holders.size() == 1 && holders.contains(txid)))
                    if (pendingQueue.contains(txid) && pendingQueue.peek() == txid)
                        return true;
                return false;
            } else {
                if (holdersMode == LockMode.EXCLUSIVE && !holders.isEmpty() && holders.contains(txid))
                    return true;
                if (holdersMode == LockMode.EXCLUSIVE)
                    return false;
                for (Map.Entry<Integer, LockMode> e : pendingModes.entrySet()) {
                    if (e.getKey() < txid && e.getValue() == LockMode.EXCLUSIVE)
                        return false;
                }
                return true;
            }
        }

        void addPending(int txid, LockMode mode) {
            if (!pendingQueue.contains(txid)) {
                pendingQueue.offer(txid);
                pendingModes.put(txid, mode);
            } else if (pendingModes.get(txid) == LockMode.SHARED && mode == LockMode.EXCLUSIVE) {
                pendingModes.put(txid, mode);
            }
        }

        void grant(int txid, LockMode mode) {
            pendingQueue.remove(txid);
            pendingModes.remove(txid);
            holders.add(txid);
            holdersMode = mode;
        }

        void release(int txid) {
            holders.remove(txid);
            if (holders.isEmpty()) holdersMode = null;
            pendingQueue.remove(txid);
            pendingModes.remove(txid);
        }

        boolean hasConflict(int holderId, int requesterId, LockMode requestedMode) {
            if (holderId == requesterId) return false;
            return requestedMode == LockMode.EXCLUSIVE || holdersMode == LockMode.EXCLUSIVE;
        }

        void promoteWaiters() {
            while (!pendingQueue.isEmpty()) {
                int txid = pendingQueue.peek();
                LockMode mode = pendingModes.get(txid);
                if (mode == null) { pendingQueue.poll(); continue; }
                if (canGrant(txid, mode)) grant(txid, mode);
                else break;
            }
        }
    }


    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        int ts = tsCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, ts);
        txn.meta.put("heldKeys", ConcurrentHashMap.newKeySet());
        txn.meta.put("thread", Thread.currentThread());
        txn.meta.put("state", new AtomicInteger(STATE_ACTIVE));
        activeTxns.put(txid, txn);
        return txn;
    }

    @SuppressWarnings("unchecked")
    private Set<String> getHeldKeys(Transaction txn) {
        return (Set<String>) txn.meta.get("heldKeys");
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        if (!acquireLock(txn, key, LockMode.SHARED)) return null;
        Object value = store.get(key);
        txn.readSet.put(key, value == null ? NULL_VALUE : value);
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        if (!acquireLock(txn, key, LockMode.EXCLUSIVE)) return;
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        AtomicInteger state = (AtomicInteger) txn.meta.get("state");

        if (!state.compareAndSet(STATE_ACTIVE, STATE_COMMITTING)) {
            txn.status = TxStatus.ABORTED;
            releaseAllLocks(txn);
            activeTxns.remove(txn.txid);
            return CommitResult.failure("Aborted by wound-wait");
        }

        try {
            store.updateAll(txn.writeSet);
            txn.status = TxStatus.COMMITTED;
            state.set(STATE_COMMITTED);
            return CommitResult.success(tsCounter.incrementAndGet());
        } finally {
            releaseAllLocks(txn);
            activeTxns.remove(txn.txid);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        AtomicInteger state = (AtomicInteger) txn.meta.get("state");
        state.set(STATE_ABORTED);
        txn.status = TxStatus.ABORTED;
        releaseAllLocks(txn);
        activeTxns.remove(txn.txid);
    }


    private boolean acquireLock(Transaction txn, String key, LockMode mode) {
        LockEntry lock = lockTable.computeIfAbsent(key, k -> new LockEntry());

        synchronized (lock) {
            if (txn.status != TxStatus.ACTIVE) return false;

            if (lock.isHeldBefore(txn.txid, mode)) {
                getHeldKeys(txn).add(key);
                return true;
            }

            handleConflict(txn, mode, lock);
            if (txn.status != TxStatus.ACTIVE) return false;

            lock.addPending(txn.txid, mode);
            getHeldKeys(txn).add(key);

            lock.promoteWaiters();
            lock.notifyAll();

            while (!lock.isHeldBefore(txn.txid, mode)) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    // Woken by wound-wait interrupt; flag cleared by exception
                }
                if (txn.status != TxStatus.ACTIVE) {
                    lock.release(txn.txid);
                    getHeldKeys(txn).remove(key);
                    lock.promoteWaiters();
                    lock.notifyAll();
                    return false;
                }
            }

            return true;
        }
    }

    /**
     * Wound-wait conflict handling.
     *
     * For each conflicting holder younger than the requester:
     *  - If ACTIVE: CAS to ABORTED, release its lock, interrupt its thread.
     *  - If already ABORTED: release its lock on this resource immediately
     *    (the aborted txn may not have cleaned up yet).
     *  - If COMMITTING/COMMITTED: let it finish naturally.
     */
    private void handleConflict(Transaction tx, LockMode requestedMode, LockEntry lock) {
        Set<Integer> holders = new HashSet<>(lock.holders);
        for (int holderId : holders) {
            if (!lock.hasConflict(holderId, tx.txid, requestedMode)) continue;
            Transaction holder = activeTxns.get(holderId);
            if (holder == null) continue;

            if (tx.startTs < holder.startTs) {
                AtomicInteger holderState = (AtomicInteger) holder.meta.get("state");
                int s = holderState.get();

                if (s == STATE_ACTIVE) {
                    if (holderState.compareAndSet(STATE_ACTIVE, STATE_ABORTED)) {
                        holder.status = TxStatus.ABORTED;
                        lock.release(holderId);
                        Thread t = (Thread) holder.meta.get("thread");
                        if (t != null) t.interrupt();
                    }
                } else if (s == STATE_ABORTED) {
                    lock.release(holderId);
                }
            }
        }
    }

    private void releaseAllLocks(Transaction txn) {
        Set<String> heldKeys = getHeldKeys(txn);
        if (heldKeys == null) return;

        for (String key : new ArrayList<>(heldKeys)) {
            LockEntry lock = lockTable.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                lock.release(txn.txid);
                lock.promoteWaiters();
                lock.notifyAll();
            }
        }
        heldKeys.clear();
    }
}
