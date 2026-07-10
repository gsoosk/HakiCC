package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

class GeneratedCC extends ConcurrencyControl {

    private static final Object NULL_VALUE = new Object();
    private enum LockMode { SHARED, EXCLUSIVE }

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final AtomicInteger tsCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, LockEntry> lockTable = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Transaction> activeTxns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, AtomicInteger> commitSemaphores = new ConcurrentHashMap<>();

    private static class LockEntry {
        final Set<Integer> holders = new HashSet<>();
        LockMode holdersMode = null;

        final LinkedList<Integer> retiredQueue = new LinkedList<>();
        final Set<Integer> retiredSet = new HashSet<>();
        final Map<Integer, LockMode> retiredModes = new HashMap<>();

        final PriorityQueue<Integer> pendingQueue = new PriorityQueue<>();
        final Map<Integer, LockMode> pendingModes = new HashMap<>();

        final Map<Integer, Integer> depOn = new HashMap<>();

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
                if (holdersMode == LockMode.EXCLUSIVE) return false;
                for (Map.Entry<Integer, LockMode> e : pendingModes.entrySet()) {
                    if (e.getKey() < txid && e.getValue() == LockMode.EXCLUSIVE) return false;
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
            retiredQueue.remove(Integer.valueOf(txid));
            retiredSet.remove(txid);
            retiredModes.remove(txid);
            depOn.remove(txid);
        }

        void retire(int txid) {
            LockMode mode = holdersMode;
            holders.remove(txid);
            if (!retiredSet.contains(txid)) {
                int idx = 0;
                for (int existing : retiredQueue) {
                    if (txid < existing) break;
                    idx++;
                }
                retiredQueue.add(idx, txid);
                retiredSet.add(txid);
            }
            retiredModes.put(txid, mode);
            if (holders.isEmpty()) holdersMode = null;
        }

        boolean hasConflict(int holderId, int requesterId, LockMode requestedMode) {
            if (holderId == requesterId) return false;
            if (!holders.contains(holderId) && !retiredSet.contains(holderId)) return false;
            if (!holders.contains(holderId) && retiredSet.contains(holderId))
                return requestedMode == LockMode.EXCLUSIVE || retiredModes.get(holderId) == LockMode.EXCLUSIVE;
            return requestedMode == LockMode.EXCLUSIVE || holdersMode == LockMode.EXCLUSIVE;
        }

        static boolean conflictModes(LockMode a, LockMode b) {
            return a == LockMode.EXCLUSIVE || b == LockMode.EXCLUSIVE;
        }

        Set<Integer> getAllOwners() {
            Set<Integer> all = new HashSet<>(holders);
            all.addAll(retiredSet);
            return all;
        }

        LockMode getAllOwnerMode(int txid) {
            if (holders.contains(txid)) return holdersMode;
            return retiredModes.get(txid);
        }

        void promoteWaiters(ConcurrentHashMap<Integer, AtomicInteger> semaphores,
                            ConcurrentHashMap<Integer, Transaction> activeTxns) {
            while (!pendingQueue.isEmpty()) {
                int txid = pendingQueue.peek();
                LockMode mode = pendingModes.get(txid);
                if (mode == null) { pendingQueue.poll(); continue; }

                boolean upgrade = holders.size() == 1 && holders.contains(txid);

                if (canGrant(txid, mode)) {
                    grant(txid, mode);
                } else {
                    break;
                }

                if (!upgrade && !depOn.containsKey(txid)) {
                    for (int retiredTxid : retiredQueue) {
                        if (retiredTxid == txid) continue;
                        LockMode retiredMode = retiredModes.get(retiredTxid);
                        if (retiredMode == null) continue;
                        Transaction retiredTx = activeTxns.get(retiredTxid);
                        if (retiredTx == null || retiredTx.status != TxStatus.ACTIVE) continue;
                        if (conflictModes(retiredMode, mode)) {
                            AtomicInteger sem = semaphores.get(txid);
                            if (sem != null) {
                                sem.incrementAndGet();
                                depOn.put(txid, retiredTxid);
                            }
                            break;
                        }
                    }
                }
            }
        }

        void resolveDependencies(int releasedTxid,
                                 ConcurrentHashMap<Integer, AtomicInteger> sems,
                                 ConcurrentHashMap<Integer, Transaction> activeTxns) {
            List<Integer> dependents = new ArrayList<>();
            for (Map.Entry<Integer, Integer> e : depOn.entrySet()) {
                if (e.getValue() == releasedTxid) {
                    dependents.add(e.getKey());
                }
            }
            for (int depTxid : dependents) {
                LockMode depMode = retiredModes.get(depTxid);
                if (depMode == null && holders.contains(depTxid)) {
                    depMode = holdersMode;
                }
                if (depMode == null) {
                    depOn.remove(depTxid);
                    continue;
                }
                int newDep = findFirstActiveConflicting(depTxid, depMode, activeTxns);
                if (newDep == -1) {
                    depOn.remove(depTxid);
                    AtomicInteger sem = sems.get(depTxid);
                    if (sem != null) sem.decrementAndGet();
                } else {
                    depOn.put(depTxid, newDep);
                }
            }
        }

        int findFirstActiveConflicting(int beforeTxid, LockMode mode,
                                       ConcurrentHashMap<Integer, Transaction> activeTxns) {
            for (int rid : retiredQueue) {
                if (rid == beforeTxid) break;
                Transaction rtx = activeTxns.get(rid);
                if (rtx == null || rtx.status != TxStatus.ACTIVE) continue;
                LockMode rmode = retiredModes.get(rid);
                if (rmode != null && conflictModes(rmode, mode)) {
                    return rid;
                }
            }
            return -1;
        }
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        Thread.interrupted();
        int txid = txidCounter.incrementAndGet();
        int ts = tsCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, ts);
        txn.meta.put("heldKeys", ConcurrentHashMap.newKeySet());
        txn.meta.put("thread", Thread.currentThread());
        commitSemaphores.put(txid, new AtomicInteger(0));
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
        retireLock(txn, key);
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        if (!acquireLock(txn, key, LockMode.EXCLUSIVE)) return;
        txn.writeSet.put(key, value);
        retireLock(txn, key);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        Thread.interrupted();
        if (txn.status == TxStatus.ABORTED) {
            cleanup(txn);
            return CommitResult.failure("Aborted");
        }
        if (txn.status == TxStatus.COMMITTED)
            return CommitResult.failure("Already committed");

        AtomicInteger sem = commitSemaphores.get(txn.txid);
        while (sem != null && sem.get() > 0) {
            if (txn.status == TxStatus.ABORTED) {
                cleanup(txn);
                return CommitResult.failure("Aborted during commit wait");
            }
            try {
                Thread.sleep(0, 1000);
            } catch (InterruptedException e) {
                if (txn.status == TxStatus.ABORTED) {
                    cleanup(txn);
                    return CommitResult.failure("Aborted during commit wait");
                }
            }
        }

        if (txn.status == TxStatus.ABORTED) {
            cleanup(txn);
            return CommitResult.failure("Aborted after commit wait");
        }

        // OCC-style read validation: ensure no stale reads
        for (Map.Entry<String, Object> entry : txn.readSet.entrySet()) {
            Object currentVal = store.get(entry.getKey());
            Object readVal = entry.getValue();
            if (readVal == NULL_VALUE) {
                if (currentVal != null) {
                    cleanup(txn);
                    return CommitResult.failure("Validation failed: key appeared");
                }
            } else if (!readVal.equals(currentVal)) {
                cleanup(txn);
                return CommitResult.failure("Validation failed: value changed");
            }
        }

        if (txn.status == TxStatus.ABORTED) {
            cleanup(txn);
            return CommitResult.failure("Aborted after validation");
        }

        try {
            store.updateAll(txn.writeSet);
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(tsCounter.incrementAndGet());
        } finally {
            cleanup(txn);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
        cleanup(txn);
    }

    private void cleanup(Transaction txn) {
        releaseAllLocks(txn);
        activeTxns.remove(txn.txid);
        commitSemaphores.remove(txn.txid);
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

            lock.promoteWaiters(commitSemaphores, activeTxns);
            lock.notifyAll();

            while (!lock.isHeldBefore(txn.txid, mode)) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    // Interrupt used as abort wakeup
                }
                if (txn.status != TxStatus.ACTIVE) {
                    lock.release(txn.txid);
                    getHeldKeys(txn).remove(key);
                    lock.promoteWaiters(commitSemaphores, activeTxns);
                    lock.notifyAll();
                    return false;
                }
            }

            return true;
        }
    }

    private void handleConflict(Transaction tx, LockMode requestedMode, LockEntry lock) {
        Set<Integer> toCheck = lock.getAllOwners();
        for (int otherId : toCheck) {
            if (!lock.hasConflict(otherId, tx.txid, requestedMode)) continue;
            Transaction other = activeTxns.get(otherId);
            if (other == null || other.status != TxStatus.ACTIVE) continue;

            if (tx.startTs < other.startTs) {
                other.status = TxStatus.ABORTED;
                releaseLockWithCascade(other.txid, lock);
                Thread t = (Thread) other.meta.get("thread");
                if (t != null) t.interrupt();
            }
        }
    }

    private void retireLock(Transaction txn, String key) {
        LockEntry lock = lockTable.get(key);
        if (lock == null) return;

        synchronized (lock) {
            if (txn.status != TxStatus.ACTIVE) return;
            if (!lock.holders.contains(txn.txid)) return;
            lock.retire(txn.txid);
            lock.promoteWaiters(commitSemaphores, activeTxns);
            lock.notifyAll();
        }
    }

    private void releaseLockWithCascade(int txid, LockEntry lock) {
        Set<Integer> allOwners = lock.getAllOwners();
        if (!allOwners.contains(txid)) {
            lock.release(txid);
            return;
        }

        LockMode ownerMode = lock.getAllOwnerMode(txid);
        Transaction tx = activeTxns.get(txid);

        if (tx != null && tx.status == TxStatus.ABORTED && ownerMode == LockMode.EXCLUSIVE) {
            List<Integer> cascadeVictims = new ArrayList<>();
            for (int otherId : allOwners) {
                if (otherId == txid) continue;
                Transaction other = activeTxns.get(otherId);
                if (other != null && other.startTs > tx.startTs) {
                    other.status = TxStatus.ABORTED;
                    Thread t = (Thread) other.meta.get("thread");
                    if (t != null) t.interrupt();
                    cascadeVictims.add(otherId);
                }
            }
            cascadeVictims.sort(Collections.reverseOrder());
            for (int victimId : cascadeVictims) {
                releaseOneFromLock(victimId, lock);
            }
        }

        releaseOneFromLock(txid, lock);
    }

    private void releaseOneFromLock(int txid, LockEntry lock) {
        if (!lock.holders.contains(txid) && !lock.retiredSet.contains(txid)
                && !lock.pendingQueue.contains(txid)) {
            return;
        }
        lock.release(txid);
        lock.resolveDependencies(txid, commitSemaphores, activeTxns);
    }

    private void releaseAllLocks(Transaction txn) {
        Set<String> heldKeys = getHeldKeys(txn);
        if (heldKeys == null) return;

        Set<Integer> cascadeAborted = new LinkedHashSet<>();

        for (String key : new ArrayList<>(heldKeys)) {
            LockEntry lock = lockTable.get(key);
            if (lock == null) continue;
            synchronized (lock) {
                collectCascadeVictims(txn.txid, lock, cascadeAborted);
                releaseLockWithCascade(txn.txid, lock);
                lock.promoteWaiters(commitSemaphores, activeTxns);
                lock.notifyAll();
            }
        }
        heldKeys.clear();

        for (int abortedId : new ArrayList<>(cascadeAborted)) {
            if (abortedId == txn.txid) continue;
            Transaction abortedTx = activeTxns.get(abortedId);
            if (abortedTx != null) {
                releaseAllLocks(abortedTx);
            }
        }
    }

    private void collectCascadeVictims(int txid, LockEntry lock, Set<Integer> cascadeAborted) {
        Set<Integer> allOwners = lock.getAllOwners();
        if (!allOwners.contains(txid)) return;
        LockMode ownerMode = lock.getAllOwnerMode(txid);
        Transaction tx = activeTxns.get(txid);

        if (tx != null && tx.status == TxStatus.ABORTED && ownerMode == LockMode.EXCLUSIVE) {
            for (int otherId : allOwners) {
                if (otherId == txid) continue;
                Transaction other = activeTxns.get(otherId);
                if (other != null && other.startTs > tx.startTs) {
                    cascadeAborted.add(otherId);
                }
            }
        }
    }
}
