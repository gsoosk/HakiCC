
package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    // This now serves as the logical clock for the database. It is only incremented on successful commit.
    private final AtomicInteger tsCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final ReentrantLock certifierLock = new ReentrantLock();

    // Stores the keys in the write sets of committed transactions, keyed by their commit timestamp.
    private final NavigableMap<Integer, Set<String>> committedWriteSets = new ConcurrentSkipListMap<>();

    private static final Object NULL_VALUE = new Object(); // Sentinel for null values

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        // The transaction's start timestamp is the current state of the logical clock.
        int startTs = tsCounter.get();
        Transaction txn = new Transaction(txid, startTs);
        activeTransactions.put(txid, txn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        Object value = store.get(key);
        
        if (value == null) {
            txn.readSet.put(key, NULL_VALUE);
        } else {
            txn.readSet.put(key, value);
        }
        
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return;
        }
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        certifierLock.lock();
        try {
            if (txn.status != TxStatus.ACTIVE) {
                return CommitResult.failure("Transaction aborted while waiting for lock");
            }

            // --- Backward Validation against Committed History ---
            // A transaction is valid if its read set does not intersect with the write sets
            // of any other transaction that committed after this one started.

            int validationEndTs = tsCounter.get();
            
            // Get the write sets of all transactions that committed between our start time (exclusive) and now (inclusive).
            NavigableMap<Integer, Set<String>> concurrentCommits = 
                committedWriteSets.subMap(txn.startTs, false, validationEndTs, true);

            for (Set<String> committedWs : concurrentCommits.values()) {
                for (String readKey : txn.readSet.keySet()) {
                    if (committedWs.contains(readKey)) {
                        // Stale read detected. Our transaction read a value that has since been
                        // overwritten by a transaction that we should be serialized after.
                        this.rollback(txn, store);
                        return CommitResult.failure("Stale read conflict on key: " + readKey);
                    }
                }
            }

            // --- Success: Commit and update history ---
            int commitTs = tsCounter.incrementAndGet();
            store.updateAll(txn.writeSet);

            if (!txn.writeSet.isEmpty()) {
                committedWriteSets.put(commitTs, new HashSet<>(txn.writeSet.keySet()));
            }

            txn.status = TxStatus.COMMITTED;
            activeTransactions.remove(txn.txid);

            // --- Prune old history ---
            // We can safely remove commit history that is older than the start time
            // of the oldest active transaction.
            int minStartTs = Integer.MAX_VALUE;
            for(Transaction t : activeTransactions.values()) {
                if(t.startTs < minStartTs) {
                    minStartTs = t.startTs;
                }
            }
            
            if (minStartTs != Integer.MAX_VALUE) {
                // Remove all entries with commitTs < minStartTs
                committedWriteSets.headMap(minStartTs, false).clear();
            } else if (activeTransactions.isEmpty()) {
                // If no transactions are active, we can clear all history.
                committedWriteSets.clear();
            }

            return CommitResult.success(commitTs);

        } finally {
            certifierLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
        activeTransactions.remove(txn.txid);
    }
}
