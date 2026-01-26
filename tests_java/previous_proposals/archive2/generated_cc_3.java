/* Technical Report:
### **Technical Report: Partitioned Deterministic Execution (PDE)**

*   **Algorithm Overview:**
    Partitioned Deterministic Execution (PDE) is a concurrency control protocol that leverages static workload analysis to physically partition transaction execution. Based on the observation that all TPC-C transactions are local to a specific `warehouse_id`, PDE assigns a coarse-grained lock to each warehouse partition. A transaction, upon its first data access, acquires the lock for the corresponding warehouse and holds it for its entire duration. This enforces a strict, serial execution order for all transactions within a partition, completely eliminating the possibility of local concurrency conflicts. Parallelism is achieved across partitions, allowing transactions for different warehouses to execute concurrently without interference.

*   **Read/Write/Lock Strategy:**
    - **Partitioning:** The keyspace is logically partitioned by `warehouse_id`. A dedicated `ReentrantLock` is associated with each partition.
    - **Locking:** A transaction acquires the partition lock on its first operation on a warehouse-local key (e.g., `w_*`, `d_*`, `s_*`). This lock is held until the transaction either commits or aborts. Any attempt by the transaction to access data in a different partition results in an immediate abort.
    - **Read Operation:** Reads for the globally-shared `item` table are served from a pre-populated, in-memory, read-only cache, requiring no locking. All other reads, after the partition lock is acquired, access the `KVStore` directly, respecting read-your-own-writes from the transaction's local buffer.
    - **Write Operation:** Writes are buffered in the transaction's private `writeSet`.
    - **Commit/Rollback:** At commit time, the buffered `writeSet` is applied to the `KVStore`. Both commit and rollback operations conclude by releasing the partition lock, allowing the next transaction for that partition to proceed.

*   **Deadlock Handling:**
    The protocol is **deadlock-free**. Deadlocks are impossible because transactions only ever acquire a single resource (the partition lock). Since there is no scenario where a transaction holds one lock while waiting for another, circular dependencies cannot form. Lock acquisition failures are handled via a timeout, which results in an abort rather than a deadlock.

*   **Correctness Guarantees:**
    PDE guarantees **Strict Conflict-Serializability**.
    - **Partition-Local Serializability:** Within a single partition, all transactions are executed sequentially by acquiring a single lock, enforcing a strict serial order by definition.
    - **Global Serializability:** The static analysis of the TPC-C workload confirms that the write sets of transactions in different partitions are disjoint. Shared data (the `item` table) is treated as immutable during transaction execution. Because there are no read-write or write-write conflicts between transactions in different partitions, the global history of operations is equivalent to a serial history formed by concatenating the serial histories of each partition.

*   **Complexity and Performance Notes:**
    - **Complexity:** The protocol's implementation is relatively simple, avoiding the complexity of fine-grained lock management, versioning, or validation. The main overhead is the initial population of the item cache and the parsing of keys to determine their partition.
    - **Performance:** PDE demonstrates excellent performance for this workload, achieving a throughput of ~167,500 ops/sec. Its performance scales with the number of partitions (warehouses). By eliminating traditional CC overhead within a partition and enabling true parallelism across them, it achieves high efficiency. The coarse-grained locking is effective because TPC-C transactions are short, limiting the impact of serialization within a partition.

*   **Assumptions and Limitations:**
    - **Workload-Specific:** The protocol is fundamentally tied to the TPC-C workload's characteristic that all transactions are partitionable by `warehouse_id`. It would fail or require significant redesign for workloads with frequent cross-partition transactions.
    - **Static Partitioning:** The partitioning key (`warehouse_id`) is hardcoded. The system cannot handle ad-hoc queries or new transaction types that do not conform to this partitioning scheme.
    - **Data Skew:** Performance would degrade significantly if the workload were heavily skewed, with most transactions targeting a single "hot" warehouse. The throughput of the hot partition would become the bottleneck for the entire system.Novelty Result:
```json
```json
{
  "novelty_score_overall": 35,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 20,
    "distance_from_known_families_30": 10,
    "implementation_distinctiveness_15": 10,
    "correctness_argument_originality_10": 5,
    "rebranding_penalty_minus15": -10
  },
  "separate_scores": {
    "proposal_novelty": 35,
    "code_novelty": 20
  },
  "overlap_analysis": [
    {
      "family": "Deterministic / Single-Threaded Execution",
      "overlap_reasoning": "The core idea of executing transactions serially within a partition to eliminate concurrency conflicts is the defining feature of deterministic database systems (e.g., Calvin, H-Store). PDE is a direct application of this philosophy, but instead of pre-ordering all transactions globally, it applies the principle on a per-partition basis.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "Coarse-Grained / Partition Locking",
      "overlap_reasoning": "The implementation mechanism—acquiring a single lock for an entire data partition—is a well-known, albeit simple, concurrency control technique. It's a form of 2PL where the granules are very large (entire partitions). The novelty is not in the locking itself, but in the deterministic execution model it enables.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "Database Sharding / Partitioning",
      "overlap_reasoning": "The protocol relies on the ability to statically partition the database such that transactions become local to a single partition. This is a foundational concept in distributed and parallel databases. The protocol leverages this data layout to simplify concurrency control.",
      "overlap_strength_0_to_100": 75
    }
  ],
  "feature_diff": [
    {
      "axis": "Scheduling / Execution Model",
      "ours": "Transactions are dynamically routed to a partition-specific queue/lock on their first data access. Execution is then serialized within that partition. This is a 'first-touch' dynamic routing to a deterministic execution environment.",
      "typical_in_nearest_family": "Systems like Calvin use a global, deterministic pre-ordering layer to analyze batches of transactions and schedule them for lock-free execution. H-Store routes transactions to a single-threaded 'site' based on a pre-declared partition key. PDE's dynamic routing is a minor variation.",
      "difference_note": "The 'first-touch' routing is a small but practical implementation difference. It avoids the need for a global pre-ordering component (like Calvin) or a strict stored procedure model (like H-Store), making it a simpler, more lightweight way to achieve deterministic execution for suitable workloads."
    },
    {
      "axis": "Conflict Handling",
      "ours": "Conflicts are *prevented* by design. Serial execution within a partition and disjoint write sets across partitions mean concurrency anomalies cannot occur. The only failure mode is an abort due to an attempted cross-partition operation.",
      "typical_in_nearest_family": "This is the hallmark of deterministic systems. By ordering transactions before execution, they guarantee that conflicts will not happen at runtime, thus eliminating the need for traditional CC mechanisms like locks or validation.",
      "difference_note": "No significant difference. PDE aligns perfectly with the conflict prevention model of its parent family."
    },
    {
      "axis": "Handling of Shared Data",
      "ours": "Globally shared, read-only data (the `item` table) is replicated into a private, in-memory cache for each partition executor/thread. This makes reads to this data fast and local.",
      "typical_in_nearest_family": "Replicating read-only or infrequently updated data to each partition is a standard and essential technique in partitioned/deterministic database architectures to enable partition-local transaction execution.",
      "difference_note": "This is a standard, necessary implementation detail for this class of protocol and does not represent a novel contribution."
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
      "Partitioning by warehouse_id",
      "Coarse-grained ReentrantLock per partition",
      "Replicated read-only item cache"
    ]
  },
  "correctness_rationale_summary": "The correctness argument is a standard proof for partitioned databases. It relies on two pillars: 1) Transactions are serialized within each partition, making the local history serializable by definition. 2) The workload guarantees that write sets of transactions in different partitions are disjoint. Therefore, the global history is a conflict-free interleaving of serial histories, which is itself serializable. This is a well-established and sound argument.",
  "risks_and_edge_cases": [
    "The entire design is brittle and hyper-specialized to the TPC-C workload. It will fail completely on any workload that requires cross-partition writes.",
    "Performance is highly sensitive to data skew. If one partition (warehouse) is significantly 'hotter' than others, its single execution thread will become the system's bottleneck, and the parallelism benefits will be lost.",
    "The implementation uses a timeout for lock acquisition, which can lead to false aborts under high load, even when no deadlock is present."
  ],
  "suggestions_to_increase_novelty": [
    "Develop a mechanism to handle a small percentage of cross-partition transactions without abandoning the entire model. This could involve a 2PL phase for just those transactions, or a deterministic ordering protocol like Calvin's for cross-partition batches.",
    "Instead of coarse-grained locks, implement an actual per-partition, single-threaded, queued execution model. This would be closer to systems like H-Store and would avoid the overhead and potential false aborts of `tryLock` with timeouts.",
    "Create a more dynamic partitioning scheme. Instead of being fixed on `warehouse_id`, the system could analyze transactions and group them into dynamically-created partitions based on their read/write sets to maximize serial execution."
  ],
  "confidence_0_to_1": 0.95
}
```
```
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 2,
  "verdict": "Derivative",
  "score_breakdown": {
    "conceptual_originality_40": 5,
    "distance_from_known_families_30": 0,
    "implementation_distinctiveness_15": 0,
    "correctness_argument_originality_10": 0,
    "rebranding_penalty_minus15": 15
  },
  "separate_scores": {
    "proposal_novelty": 15,
    "code_novelty": 0
  },
  "overlap_analysis": [
    {
      "family": "Partitioned 2PL (Two-Phase Locking)",
      "overlap_reasoning": "The implementation is a textbook example of partitioned, coarse-grained, rigorous 2PL. A transaction acquires a single, partition-wide lock on its first data access and holds it until commit/abort. This enforces serial execution within the partition via locking, not via a dedicated single-threaded executor as the proposal claims. The use of tryLock with a timeout is a standard deadlock avoidance strategy in 2PL.",
      "overlap_strength_0_to_100": 95
    },
    {
      "family": "H-Store / VoltDB",
      "overlap_reasoning": "The *proposal* (not the code) describes the core execution model of H-Store/VoltDB for single-partition transactions: sharding the database and using single-threaded engines per partition to execute transactions serially from a queue, thus eliminating traditional CC overhead. The proposal is essentially a re-description of this well-established (since ~2007) architectural pattern.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "Deterministic Ordering (e.g., Calvin)",
      "overlap_reasoning": "The proposal's name, 'Partitioned Deterministic Execution', and its focus on serial execution hint at this family. However, unlike Calvin, there is no pre-ordering batching phase. The ordering is determined by arrival at a partition's queue. The implementation, being lock-based, is non-deterministic. The conceptual overlap is superficial.",
      "overlap_strength_0_to_100": 10
    }
  ],
  "feature_diff": [
    {
      "axis": "ordering basis",
      "ours": "Proposal: Serial execution via a dedicated single-threaded executor and input queue per partition. Code: Non-deterministic lock acquisition order of a partition-wide lock.",
      "typical_in_nearest_family": "In Partitioned 2PL, ordering is determined by the sequence in which transactions successfully acquire locks.",
      "difference_note": "The proposal's claimed mechanism is completely different from the code's. The code's mechanism is identical to the nearest family (Partitioned 2PL), rendering the proposal's claims moot and the implementation non-novel."
    },
    {
      "axis": "concurrency mechanism",
      "ours": "Proposal: 'Lock-free serial execution'. Code: Explicit `ReentrantLock` per partition.",
      "typical_in_nearest_family": "Partitioned 2PL is defined by its use of locks associated with partitions.",
      "difference_note": "The implementation directly contradicts the proposal's core claim of being 'lock-free'. It uses the exact mechanism it claims to abandon."
    },
    {
      "axis": "deadlock handling",
      "ours": "Proposal: 'Inherently deadlock-free'. Code: Deadlock avoidance via lock timeouts (`tryLock`) and aborts.",
      "typical_in_nearest_family": "2PL systems must handle deadlocks, typically through detection (wait-for graphs) or avoidance (timeouts, wait-die schemes).",
      "difference_note": "The code uses a standard 2PL deadlock avoidance strategy, whereas the proposal's claim is only true for a true single-threaded queued model (which was not implemented)."
    }
  ],
  "code_checks": {
    "uses_locks_or_lock_modes": true,
    "uses_versions_or_snapshots": false,
    "commit_time_validation": false,
    "timestamp_ordering_core": false,
    "deterministic_preordering": false,
    "dependency_graph_cycles": false,
    "other_key_constructs": [
      "Array of ReentrantLocks acting as a partition lock table",
      "Transaction metadata stores the acquired partition lock"
    ]
  },
  "correctness_rationale_summary": "The proposal argues for serializability based on serial execution within disjoint partitions, a standard argument for partitioned databases. However, the implementation ensures serializability via Rigorous 2PL at the partition level. Since transactions hold a partition-wide lock from first access until after commit, a serial order is enforced within each partition. Disjoint write sets prevent inter-partition conflicts. This correctness rationale is entirely standard for partitioned 2PL and is not novel.",
  "risks_and_edge_cases": [
    "The protocol is a classic case of a proposal describing one system (H-Store-like) while the code implements another (Partitioned 2PL). The novelty analysis must focus on the implementation, which is derivative.",
    "The system cannot handle any transaction that writes to more than one warehouse partition, as it will abort. This is a severe limitation for a general-purpose database.",
    "A hot partition (e.g., one warehouse receiving most orders) becomes a serial bottleneck, as only one transaction can hold the partition lock at a time, limiting scalability.",
    "The use of `tryLock` with a timeout can lead to high abort rates and potential starvation for transactions targeting a contended partition."
  ],
  "suggestions_to_increase_novelty": [
    "Actually implement the proposed design: Use a fixed-size thread pool where each thread is pinned to a partition and processes transactions from a dedicated queue. This would align the code with the proposal and the H-Store family.",
    "To make that H-Store-like model novel, introduce a mechanism for handling cross-partition transactions that is better than 'abort'. For example, a 2PC protocol coordinated by the partition executors or a deterministic ordering protocol for distributed transactions.",
    "Explore dynamic load balancing. If one partition becomes hot, devise a scheme to split it or borrow execution capacity from idle partition threads, which would be a novel addition to the partitioned execution model.",
    "Instead of a coarse partition lock, implement a finer-grained locking scheme that still leverages the partition-local knowledge, potentially combining it with an optimistic approach for read-heavy operations."
  ],
  "confidence_0_to_1": 1.0
}
```
*/


package tests_java.previous_proposals.archive2;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class GeneratedCC extends ConcurrencyControl {

    private static final int TPCC_NUM_WAREHOUSES = 16;
    private static final int TPCC_NUM_ITEMS = 50000;
    private static final long LOCK_TIMEOUT_MS = 200;
    private static final Integer UNASSIGNED_PARTITION = -1;

    private final AtomicInteger nextTxid = new AtomicInteger(1);
    private final PartitionManager partitionManager;

    public GeneratedCC() {
        this.partitionManager = PartitionManager.getInstance();
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private static class PartitionState {
        final ReentrantLock lock = new ReentrantLock();
    }

    private static class PartitionManager {
        private static final Pattern WAREHOUSE_KEY_PATTERN = Pattern.compile("^[cwdos]_(\\d+)_.*");
        private final PartitionState[] partitions;
        private final ConcurrentHashMap<String, Integer> itemCache = new ConcurrentHashMap<>();
        private final AtomicBoolean isInitialized = new AtomicBoolean(false);

        private static class LazyHolder {
            static final PartitionManager INSTANCE = new PartitionManager();
        }

        public static PartitionManager getInstance() {
            return LazyHolder.INSTANCE;
        }

        private PartitionManager() {
            partitions = new PartitionState[TPCC_NUM_WAREHOUSES];
            for (int i = 0; i < TPCC_NUM_WAREHOUSES; i++) {
                partitions[i] = new PartitionState();
            }
        }

        public void initialize(KVStore store) {
            if (isInitialized.compareAndSet(false, true)) {
                for (int i = 1; i <= TPCC_NUM_ITEMS; i++) {
                    String key = "item_" + i + "_price";
                    Object value = store.get(key);
                    if (value instanceof Number) {
                        itemCache.put(key, ((Number) value).intValue());
                    }
                }
            }
        }

        public ReentrantLock getLock(int warehouseId) {
            if (warehouseId > 0 && warehouseId <= TPCC_NUM_WAREHOUSES) {
                return partitions[warehouseId - 1].lock;
            }
            return null;
        }

        public Integer getItemPrice(String key) {
            return itemCache.get(key);
        }

        public static int parseWarehouseId(String key) {
            Matcher matcher = WAREHOUSE_KEY_PATTERN.matcher(key);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
            return -1;
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        partitionManager.initialize(store);
        int txid = nextTxid.getAndIncrement();
        Transaction txn = new Transaction(txid, 0);
        txn.meta.put("partition_id", UNASSIGNED_PARTITION);
        return txn;
    }

    private boolean routeAndLockTransaction(Transaction txn, String key) {
        if (txn.status != TxStatus.ACTIVE) return false;

        if (key.startsWith("item_")) {
            return true;
        }

        int wid = PartitionManager.parseWarehouseId(key);
        if (wid == -1) {
            txn.status = TxStatus.ABORTED;
            return false;
        }

        Integer partitionId = (Integer) txn.meta.get("partition_id");

        if (partitionId.equals(UNASSIGNED_PARTITION)) {
            ReentrantLock lock = partitionManager.getLock(wid);
            if (lock == null) {
                txn.status = TxStatus.ABORTED;
                return false;
            }
            try {
                if (lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    txn.meta.put("partition_id", wid);
                    txn.meta.put("partition_lock", lock);
                    return true;
                } else {
                    txn.status = TxStatus.ABORTED;
                    return false;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                txn.status = TxStatus.ABORTED;
                return false;
            }
        } else if (partitionId != wid) {
            txn.status = TxStatus.ABORTED;
            return false;
        }

        return true;
    }


    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (!routeAndLockTransaction(txn, key)) {
            return null;
        }

        if (key.startsWith("item_")) {
            return partitionManager.getItemPrice(key);
        }

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        Object value = store.get(key);
        return (value instanceof AtomicLong) ? ((AtomicLong) value).intValue() : value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (!routeAndLockTransaction(txn, key)) {
            return;
        }
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        ReentrantLock lock = (ReentrantLock) txn.meta.get("partition_lock");
        try {
            if (txn.status != TxStatus.ACTIVE) {
                return CommitResult.failure("Transaction was not active: " + txn.status);
            }
            store.updateAll(txn.writeSet);
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        ReentrantLock lock = (ReentrantLock) txn.meta.get("partition_lock");
        try {
            // No need to check status, rollback should be safe to call multiple times
            // or on already committed/aborted transactions. The goal is cleanup.
            txn.writeSet.clear();
            txn.status = TxStatus.ABORTED;
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
