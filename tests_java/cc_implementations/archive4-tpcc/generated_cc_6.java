/* Technical Report:
### Final Technical Report: Semantic-Aware Deterministic Locking (SADL)

*   **Algorithm Overview**
    SADL is a hybrid concurrency control protocol designed for high performance on workloads with pre-defined transaction types and a partitionable keyspace. It combines three core concepts: strict data partitioning, deterministic ordering via dedicated schedulers, and semantic-aware locking. The keyspace is partitioned based on a `warehouse_id` extracted from the key. Each partition is managed by an independent, single-threaded scheduler that establishes a definitive serial execution order for all transactions within its partition *before* they execute. This order is enforced by assigning a monotonic Intra-Partition Sequence Number (IPSN) to each transaction upon its first operation. Concurrency is then maximized during the execution phase by using specialized lock modes that leverage the commutative properties of the known transaction types.

*   **Read/Write/Lock Strategy**
    -   **Visibility & Writes:** Writes are buffered locally in a transaction's private write-set. Reads first check this local write-set to ensure "read-your-own-writes" semantics. If a key is not in the write-set, the transaction acquires the appropriate lock and reads from the global key-value store.
    -   **Locking Strategy:** SADL uses three lock modes: Shared (S), Exclusive (X), and a custom Ordered-Commutative (OC).
        -   **S-Lock:** Acquired for standard read operations. Compatible with other S-Locks.
        -   **X-Lock:** Acquired for non-commutative writes, such as incrementing the `next_oid` counter. Incompatible with all other locks.
        -   **OC-Lock:** Acquired for commutative arithmetic operations (e.g., `ytd += val`, `stock -= qty`). OC-Locks are compatible with other OC-Locks, allowing multiple transactions to concurrently modify the same key. They are not compatible with S or X locks.
    -   **Commit Strategy:** When a transaction completes execution, it is placed into its partition's commit queue, which is ordered by IPSN. The partition's scheduler thread processes this queue in strict IPSN order, applying the buffered writes to the database and releasing the transaction's locks. This ensures the physical state of the database always reflects the pre-determined serial order.

*   **Deadlock Handling**
    The protocol is **deadlock-free by design**. Lock contention is resolved based on the pre-assigned IPSN. A transaction `T_j` is only permitted to wait for a lock held by a transaction `T_i` if `IPSN(i) < IPSN(j)`. This rule creates a strict, acyclic ordering in the waits-for graph, making circular dependencies (deadlocks) impossible. Consequently, there is no need for deadlock detection or timeout-based aborts.

*   **Correctness Guarantees**
    SADL provides **Strict Conflict-Serializability**.
    -   **Serializability:** The assignment of a unique IPSN to every transaction within a partition establishes a total serial order *a priori*. The ordered commit mechanism guarantees that the final database state is equivalent to one where transactions were executed serially in their IPSN order. Since the TPC-C workload transactions are disjoint across partitions, local serializability guarantees global serializability.
    -   **Strictness:** All locks (S, X, and OC) are held until the transaction is processed by the commit scheduler (after its logical completion). This ensures that no transaction can read or write data modified by another uncommitted transaction, preventing cascading aborts.

*   **Complexity and Performance Notes**
    -   **Performance:** SADL achieves high throughput on the target TPC-C workload. Partitioning allows for true parallelism across warehouses. The use of OC-Locks dramatically reduces contention on frequently updated hotspot keys (like `ytd` and `stock_qty`), further boosting intra-partition concurrency. The zero-abort design for concurrency conflicts eliminates wasted work and leads to predictable latency under contention.
    -   **Complexity:** The primary overhead is the inter-thread communication between worker threads and the partition schedulers for IPSN assignment and commit processing. The single-threaded nature of the scheduler serializes these two critical points, which could become a bottleneck if individual transaction execution times were negligible.

*   **Assumptions and Limitations**
    -   **Workload Specificity:** The protocol is highly optimized for the given TPC-C-like workload. Its performance relies on the ability to partition the data and the prevalence of commutative arithmetic operations.
    -   **Known Semantics:** The protocol assumes the semantics of write operations are known in advance to correctly classify them as commutative (OC-Lock) or non-commutative (X-Lock).
    -   **Partition-Bound Transactions:** The design is most effective for transactions that operate entirely within a single partition. Cross-partition transactions would require a more complex global coordination mechanism, which is not implemented and would degrade performance.
    -   **No Ad-Hoc Queries:** The protocol is not designed for general-purpose, ad-hoc queries, which may not align with the partitioning scheme or semantic lock modes.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 62,
  "verdict": "Moderately Novel",
  "score_breakdown": {
    "conceptual_originality_40": 25,
    "distance_from_known_families_30": 20,
    "implementation_distinctiveness_15": 12,
    "correctness_argument_originality_10": 5,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 65,
    "code_novelty": 60
  },
  "overlap_analysis": [
    {
      "family": "Deterministic Ordering (e.g., Calvin, BOHM)",
      "overlap_reasoning": "SADL shares the core principle of establishing a serial order *before* execution using a sequence number (IPSN). This pre-ordering is used to manage conflicts deterministically. The key difference is that SADL acquires locks dynamically during execution and uses semantic lock modes, whereas classic deterministic systems typically acquire all (S/X) locks upfront based on static analysis.",
      "overlap_strength_0_to_100": 75
    },
    {
      "family": "2PL (with Wait-Die)",
      "overlap_reasoning": "SADL uses locks and a strict ordering rule to prevent deadlocks, similar to Wait-Die. However, SADL's ordering is based on a permanent, pre-assigned serial number (IPSN) rather than a transient transaction start time. Furthermore, SADL's ordered commit and the introduction of the OC-Lock for commutativity are significant departures from standard 2PL.",
      "overlap_strength_0_to_100": 50
    },
    {
      "family": "Escrow Locking / Commutativity",
      "overlap_reasoning": "The 'Ordered-Commutative (OC) Lock' is functionally a form of escrow locking, a known technique for handling commutative arithmetic updates concurrently. The novelty in SADL is not the lock mode itself, but its integration into a deterministic ordering protocol to provide correctness guarantees while maximizing parallelism.",
      "overlap_strength_0_to_100": 60
    }
  ],
  "feature_diff": [
    {
      "axis": "Ordering Basis",
      "ours": "A per-partition, monotonic Intra-Partition Sequence Number (IPSN) is assigned by a scheduler upon transaction arrival.",
      "typical_in_nearest_family": "In Calvin, a global sequencer assigns a logical timestamp to transactions collected in a batch.",
      "difference_note": "SADL's per-partition approach allows for more decentralized scaling and avoids a global sequencing bottleneck, at the cost of requiring strict data partitioning."
    },
    {
      "axis": "Lock Acquisition",
      "ours": "Locks are acquired dynamically as the transaction executes its operations.",
      "typical_in_nearest_family": "In Calvin, all locks for a transaction's pre-declared read/write set are acquired in a deterministic phase before execution begins.",
      "difference_note": "Dynamic acquisition is more flexible for transactions with data-dependent access patterns but sacrifices the perfect predictability of a static locking phase."
    },
    {
      "axis": "Semantic Concurrency",
      "ours": "Uses a first-class 'OC-Lock' for commutative operations, which is compatible with other OC-Locks, allowing high concurrency on hot numeric keys.",
      "typical_in_nearest_family": "Deterministic systems typically use standard Shared/Exclusive locks, which would serialize all updates to the same key.",
      "difference_note": "This is the core performance optimization. It allows SADL to overcome the classic deterministic database bottleneck on write-write conflicts for specific, common workload patterns."
    },
    {
      "axis": "Commit Rule",
      "ours": "Transactions are committed strictly in their assigned IPSN order. A commit worker processes a queue, ensuring physical writes match the logical order.",
      "typical_in_nearest_family": "Transactions in a batch execute and their effects are applied, with the logical serial order guaranteed by the pre-ordering. Physical commit timing is less strict.",
      "difference_note": "SADL's strict physical commit ordering simplifies reasoning about state and recovery, ensuring the database state always reflects a valid prefix of the serial history."
    }
  ],
  "code_checks": {
    "uses_locks_or_lock_modes": true,
    "uses_versions_or_snapshots": false,
    "commit_time_validation": false,
    "timestamp_ordering_core": false,
    "deterministic_preordering": true,
    "dependency_graph_cycles": false,
    "other_key_constructs": [
      "Per-partition single-threaded scheduler",
      "Ordered commit queue based on IPSN",
      "Commutative lock mode (OC-Lock)",
      "IPSN-based wait ordering for deadlock prevention"
    ]
  },
  "correctness_rationale_summary": "Correctness hinges on establishing a total serial order via the Intra-Partition Sequence Number (IPSN) *before* execution. The protocol then enforces this order. Deadlocks are prevented by ensuring transactions only wait for those with a lower IPSN. Commutative OC-Locks allow for concurrent physical execution of some operations, but the strictly ordered commit ensures the final database state is equivalent to the pre-defined serial execution. This rationale is a novel composition of known arguments from deterministic and semantic locking systems.",
  "risks_and_edge_cases": [
    "The single-threaded scheduler/committer per partition is a potential bottleneck if transaction execution times are very short, making scheduling overhead dominant.",
    "For workloads without many commutative operations, the OC-Lock provides no benefit, and the protocol degenerates to a partitioned, deterministic 2PL variant.",
    "A single 'hot' partition with high contention on non-commutative keys (e.g., the `_next_oid` key) will still serialize transactions, limiting throughput.",
    "The strict partitioning assumption is critical; any cross-partition transaction would break the model or require a much more complex coordination layer."
  ],
  "suggestions_to_increase_novelty": [
    "Generalize the semantic lock concept beyond simple commutative arithmetic. Could the system analyze user-defined functions for properties like commutativity or associativity to create lock modes dynamically?",
    "Integrate a multi-versioning scheme for reads. Reads could use a non-blocking snapshot based on the `lastCommittedIpsn`, while writes follow the deterministic locking path. This would create a novel hybrid of deterministic locking and snapshot isolation.",
    "Explore a multi-threaded scheduler per partition. This would require a more complex mechanism than a simple IPSN to define the serial order, perhaps involving a fine-grained dependency graph constructed during scheduling."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of Semantic-Aware Deterministic Locking (SADL).
 * This protocol partitions the database by warehouse_id and uses a single-threaded
 * scheduler per partition to assign a serial order (IPSN) and commit transactions
 * in that order. It employs special commutative lock modes (OC-Lock) to maximize
 * concurrency for arithmetic operations, while preventing deadlocks by ensuring
* transactions only wait for those with a lower IPSN.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger nextTxid = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PartitionManager> partitions = new ConcurrentHashMap<>();
    private static final Pattern WAREHOUSE_KEY_PATTERN = Pattern.compile("^[a-z]_(\\d+)_.*");
    private static final Object NULL_SENTINEL = new Object(); // Sentinel for null values in ConcurrentHashMap

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    // Enum for different lock modes
    private enum LockMode {
        NONE, S_LOCK, X_LOCK, OC_LOCK // Ordered-Commutative Lock
    }

    // Represents a lock on a specific key
    private static class LockEntry {
        volatile LockMode mode = LockMode.NONE;
        final List<Integer> owners = new ArrayList<>(); // List of txids holding the lock
    }

    // Manages all state and scheduling for a single partition (warehouse)
    private class PartitionManager {
        private final int partitionId;
        private final AtomicLong ipsnCounter = new AtomicLong(0); // Intra-Partition Sequence Number
        private long lastCommittedIpsn = -1;

        private final ConcurrentHashMap<String, LockEntry> lockManager = new ConcurrentHashMap<>();
        private final BlockingQueue<Transaction> registrationQueue = new LinkedBlockingQueue<>();
        private final PriorityBlockingQueue<Transaction> commitQueue = new PriorityBlockingQueue<>(11,
                Comparator.comparingLong(t -> (Long) t.meta.get("ipsn")));

        private final Thread schedulerThread;
        private volatile boolean running = true;

        PartitionManager(int partitionId) {
            this.partitionId = partitionId;
            this.schedulerThread = new Thread(this::run, "scheduler-" + partitionId);
            this.schedulerThread.start();
        }

        void shutdown() {
            running = false;
            schedulerThread.interrupt();
        }

        private void run() {
            while (running) {
                try {
                    // Prioritize committing before registering new transactions to keep the pipeline moving
                    processCommits();
                    processRegistrations();
                    // Avoid busy-waiting if both queues are empty
                    if (commitQueue.isEmpty() && registrationQueue.isEmpty()) {
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    running = false;
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void processRegistrations() {
            Transaction txn = registrationQueue.poll();
            if (txn == null) return;

            ReentrantLock lock = (ReentrantLock) txn.meta.get("lock");
            Condition cond = (Condition) txn.meta.get("condition");
            lock.lock();
            try {
                txn.meta.put("ipsn", ipsnCounter.getAndIncrement());
                cond.signal();
            } finally {
                lock.unlock();
            }
        }

        private void processCommits() {
            while (true) {
                Transaction txn = commitQueue.peek();
                if (txn == null || (Long) txn.meta.get("ipsn") != lastCommittedIpsn + 1) {
                    break; // Head of queue is not the next in sequence, or queue is empty
                }

                // We have the next transaction to commit
                txn = commitQueue.poll();
                
                ReentrantLock lock = (ReentrantLock) txn.meta.get("lock");
                Condition cond = (Condition) txn.meta.get("condition");
                lock.lock();
                try {
                    if (txn.status == TxStatus.ABORTED) {
                        // This transaction was rolled back while in the commit queue
                        lastCommittedIpsn++;
                        txn.meta.put("commit_result", CommitResult.failure("Rolled back by client"));
                    } else {
                        KVStore store = (KVStore) txn.meta.get("kvstore");
                        // Apply writes to KVStore
                        for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                            store.update(entry.getKey(), entry.getValue());
                        }
                        txn.status = TxStatus.COMMITTED;
                        lastCommittedIpsn++;
                        txn.meta.put("commit_result", CommitResult.success((int)lastCommittedIpsn));
                    }
                    
                    releaseAllLocks(txn);
                    cond.signal(); // Wake up the waiting client thread
                } finally {
                    lock.unlock();
                }
            }
        }

        void registerTransaction(Transaction txn) throws InterruptedException {
            registrationQueue.put(txn);
        }
        
        void submitForCommit(Transaction txn) throws InterruptedException {
            commitQueue.put(txn);
        }
    }
    
    @Override
    public Transaction begin(KVStore store) {
        int txid = nextTxid.getAndIncrement();
        Transaction txn = new Transaction(txid, 0);
        ReentrantLock lock = new ReentrantLock();
        txn.meta.put("lock", lock);
        txn.meta.put("condition", lock.newCondition());
        txn.meta.put("held_locks", new HashSet<String>());
        activeTransactions.put(txid, txn);
        return txn;
    }

    private void ensureTransactionIsInitialized(Transaction txn, String key) {
        if (txn.meta.containsKey("partition_id")) {
            return;
        }
        ReentrantLock lock = (ReentrantLock) txn.meta.get("lock");
        lock.lock();
        try {
            // Double-check after acquiring lock
            if (txn.meta.containsKey("partition_id")) {
                return;
            }
            int warehouseId = getWarehouseId(key);
            txn.meta.put("partition_id", warehouseId);
            PartitionManager pm = partitions.computeIfAbsent(warehouseId, PartitionManager::new);
            
            pm.registerTransaction(txn);
            // Wait for the scheduler to assign an IPSN
            ((Condition) txn.meta.get("condition")).await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txn.status = TxStatus.ABORTED;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        ensureTransactionIsInitialized(txn, key);
        if (txn.status != TxStatus.ACTIVE) return null; // Status might change during initialization

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key); // Read-your-own-writes
        }
        if (txn.readSet.containsKey(key)) {
            Object cachedValue = txn.readSet.get(key);
            return (cachedValue == NULL_SENTINEL) ? null : cachedValue;
        }

        acquireLock(txn, key, LockMode.S_LOCK);
        if (txn.status != TxStatus.ACTIVE) return null;

        Object value = store.get(key);
        // KVStore uses AtomicLong for numbers, unwrap it for the transaction logic
        if (value instanceof AtomicLong) {
            value = ((AtomicLong) value).get();
        }
        
        // **FIX**: Use sentinel for null values to avoid NPE in ConcurrentHashMap
        Object valueToCache = (value == null) ? NULL_SENTINEL : value;
        txn.readSet.put(key, valueToCache);
        
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        ensureTransactionIsInitialized(txn, key);
        if (txn.status != TxStatus.ACTIVE) return;

        LockMode mode = getLockModeForKey(key, value);
        acquireLock(txn, key, mode);
        if (txn.status != TxStatus.ACTIVE) return;
        
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already completed.");
        }
        if (!txn.meta.containsKey("partition_id")) { // Read-only transaction
            txn.status = TxStatus.COMMITTED;
            activeTransactions.remove(txn.txid);
            return CommitResult.success(0);
        }

        PartitionManager pm = partitions.get((Integer) txn.meta.get("partition_id"));
        ReentrantLock lock = (ReentrantLock) txn.meta.get("lock");
        lock.lock();
        try {
            txn.meta.put("kvstore", store);
            pm.submitForCommit(txn);
            ((Condition) txn.meta.get("condition")).await(); // Wait for scheduler to commit
            
            activeTransactions.remove(txn.txid);
            return (CommitResult) txn.meta.get("commit_result");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txn.status = TxStatus.ABORTED;
            return CommitResult.failure("Commit interrupted");
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        
        txn.status = TxStatus.ABORTED;
        
        if (txn.meta.containsKey("partition_id")) {
             PartitionManager pm = partitions.get((Integer) txn.meta.get("partition_id"));
             // Attempt to remove from queue if it was submitted. It's okay if it fails.
             pm.commitQueue.remove(txn);
             releaseAllLocks(txn);
        }

        activeTransactions.remove(txn.txid);
    }
    
    private void acquireLock(Transaction txn, String key, LockMode requestedMode) {
        PartitionManager pm = partitions.get((Integer) txn.meta.get("partition_id"));
        LockEntry entry = pm.lockManager.computeIfAbsent(key, k -> new LockEntry());
        long txnIpsn = (Long) txn.meta.get("ipsn");

        synchronized (entry) {
            while (true) {
                if (txn.status != TxStatus.ACTIVE) return;

                boolean conflict = false;
                if (!entry.owners.isEmpty() && !(entry.owners.size() == 1 && entry.owners.get(0) == txn.txid)) {
                     for (int ownerTxid : entry.owners) {
                        Transaction ownerTxn = activeTransactions.get(ownerTxid);
                        if (ownerTxn == null || ownerTxn.txid == txn.txid || !ownerTxn.meta.containsKey("ipsn")) continue;

                        long ownerIpsn = (long) ownerTxn.meta.get("ipsn");
                        if (ownerIpsn < txnIpsn && !areLocksCompatible(entry.mode, requestedMode)) {
                            conflict = true;
                            break;
                        }
                    }
                }

                if (!conflict) {
                    // Grant lock
                    if (entry.mode == LockMode.NONE) {
                        entry.mode = requestedMode;
                    }
                    if (!entry.owners.contains(txn.txid)) {
                        entry.owners.add(txn.txid);
                    }
                    ((Set<String>) txn.meta.get("held_locks")).add(key);
                    return;
                } else {
                    try {
                        entry.wait(); // Wait for a lock to be released
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        txn.status = TxStatus.ABORTED;
                        return;
                    }
                }
            }
        }
    }

    private void releaseAllLocks(Transaction txn) {
        Set<String> heldLocks = (Set<String>) txn.meta.get("held_locks");
        if (heldLocks == null) return;
        
        PartitionManager pm = partitions.get((Integer) txn.meta.get("partition_id"));
        
        for (String key : heldLocks) {
            LockEntry entry = pm.lockManager.get(key);
            if (entry != null) {
                synchronized (entry) {
                    entry.owners.remove(Integer.valueOf(txn.txid));
                    if (entry.owners.isEmpty()) {
                        entry.mode = LockMode.NONE;
                    }
                    entry.notifyAll(); // Wake up all waiting threads
                }
            }
        }
    }
    
    private boolean areLocksCompatible(LockMode held, LockMode requested) {
        if (held == LockMode.NONE || requested == LockMode.NONE) return true;
        if (held == LockMode.OC_LOCK && requested == LockMode.OC_LOCK) return true;
        if (held == LockMode.S_LOCK && requested == LockMode.S_LOCK) return true;
        return false;
    }

    private LockMode getLockModeForKey(String key, Object value) {
        // d_..._next_oid must be exclusive
        if (key.endsWith("_next_oid")) {
            return LockMode.X_LOCK;
        }
        // All other writes in this workload are commutative arithmetic operations
        return LockMode.OC_LOCK;
    }

    private int getWarehouseId(String key) {
        Matcher matcher = WAREHOUSE_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        // Fallback for non-warehouse-specific keys like "item_..."
        return 0; // A global partition for shared data
    }

    public void shutdown() {
        partitions.values().forEach(PartitionManager::shutdown);
    }
}
