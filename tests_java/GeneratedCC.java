
package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements "ASCP with Speculative Reads and Commit-Time Synchronization" (ASCP-S).
 *
 * Core Idea:
 * 1.  **Global Ordering:** Transactions are assigned a unique, monotonic Transaction Sequence Number (TSN) at begin time. This defines the serializable order.
 * 2.  **Sharding:** The key space is partitioned into a fixed number of logical shards. Each shard has its own lock and a queue of pending writes.
 * 3.  **Speculative Execution:** Read and write operations are fast. Reads go to the main KV store speculatively, and writes are buffered locally in the transaction's write set. No locks are held during this phase.
 * 4.  **Ordered, Validating Commit:** The commit phase is where serializability is enforced.
 *     a. **Locking:** The transaction acquires locks on all shards it has touched, in a globally consistent order (by shard ID) to prevent deadlocks.
 *     b. **Validation:** It validates its read set against writes from transactions with earlier TSNs that are pending in the shard queues. If a read is stale (i.e., it should have seen a pending write), the transaction aborts.
 *     c. **Enqueue & Apply:** If validation passes, the transaction's writes are added to the pending queues of the respective shards, keyed by their TSN. The system then attempts to apply any consecutive pending writes to the main KV store.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final int NUM_SHARDS = 256;
    private final AtomicInteger globalTsn = new AtomicInteger(0);
    private final ExecutionShard[] shards = new ExecutionShard[NUM_SHARDS];

    private static class ExecutionShard {
        final ReentrantLock lock = new ReentrantLock();
        // Stores pending writes, keyed by TSN, sorted chronologically.
        final ConcurrentSkipListMap<Integer, Map<String, Object>> pendingWrites = new ConcurrentSkipListMap<>();
        // Watermark of the last TSN whose writes were applied to the KVStore.
        volatile int commitWatermark = 0;
    }

    public GeneratedCC() {
        for (int i = 0; i < NUM_SHARDS; i++) {
            shards[i] = new ExecutionShard();
        }
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private int getShardId(String key) {
        // Using hashCode() provides a good general-purpose distribution.
        // Math.abs is needed because hashCode() can be negative.
        return Math.abs(key.hashCode() % NUM_SHARDS);
    }

    @Override
    public Transaction begin(KVStore store) {
        int tsn = globalTsn.getAndIncrement();
        Transaction txn = new Transaction(tsn, tsn); // Using tsn for both txid and startTs
        txn.meta.put("tsn", tsn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        // 1. Read from own write set first.
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        // 2. Speculatively read from the main store.
        Object value = store.get(key);

        // 3. Record the read for later validation.
        txn.readSet.put(key, value);
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

        int tsn = (int) txn.meta.get("tsn");
        List<ReentrantLock> acquiredLocks = new ArrayList<>();

        try {
            // Phase 1: Identify and Lock Shards in a globally consistent order to prevent deadlock.
            Set<Integer> affectedShardIds = new TreeSet<>();
            txn.readSet.keySet().forEach(key -> affectedShardIds.add(getShardId(key)));
            txn.writeSet.keySet().forEach(key -> affectedShardIds.add(getShardId(key)));

            if (affectedShardIds.isEmpty()) {
                txn.status = TxStatus.COMMITTED;
                return CommitResult.success(tsn);
            }

            for (int shardId : affectedShardIds) {
                ReentrantLock lock = shards[shardId].lock;
                lock.lock();
                acquiredLocks.add(lock);
            }

            // Phase 2: Validation.
            for (Map.Entry<String, Object> readEntry : txn.readSet.entrySet()) {
                String key = readEntry.getKey();
                int shardId = getShardId(key);
                ExecutionShard shard = shards[shardId];

                // Check for conflicting writes from transactions with an earlier TSN that are still pending.
                // If such a write exists, our read was stale.
                NavigableMap<Integer, Map<String, Object>> precedingWrites = shard.pendingWrites.headMap(tsn, false);
                for (Map<String, Object> writes : precedingWrites.values()) {
                    if (writes.containsKey(key)) {
                        // Abort: Read conflict detected.
                        rollback(txn, store);
                        return CommitResult.failure("Read conflict on key '" + key + "'");
                    }
                }
            }

            // Phase 3: Enqueue Writes.
            if (!txn.writeSet.isEmpty()) {
                Map<Integer, Map<String, Object>> writesByShard = txn.writeSet.entrySet().stream()
                        .collect(Collectors.groupingBy(
                                entry -> getShardId(entry.getKey()),
                                Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)
                        ));

                for (Map.Entry<Integer, Map<String, Object>> shardWrites : writesByShard.entrySet()) {
                    int shardId = shardWrites.getKey();
                    shards[shardId].pendingWrites.put(tsn, shardWrites.getValue());
                }
            }
            
            txn.status = TxStatus.COMMITTED;

            // Phase 4: Apply Pending Writes for all locked shards.
            for (int shardId : affectedShardIds) {
                applyPendingWrites(shards[shardId], store);
            }

            return CommitResult.success(tsn);

        } finally {
            // Ensure all acquired locks are released.
            for (int i = acquiredLocks.size() - 1; i >= 0; i--) {
                acquiredLocks.get(i).unlock();
            }
        }
    }

    private void applyPendingWrites(ExecutionShard shard, KVStore store) {
        while (true) {
            int nextTsn = shard.commitWatermark + 1;
            Map<String, Object> writesToApply = shard.pendingWrites.get(nextTsn);

            if (writesToApply != null) {
                store.put(writesToApply);
                shard.pendingWrites.remove(nextTsn);
                shard.commitWatermark = nextTsn;
            } else {
                // No consecutive TSN found, stop for now.
                break;
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
        // No need to clean up KVStore as writes are never applied directly.
        // Locks are released in the commit method's finally block.
    }
}
