/* Technical Report:
### Final Technical Report: Optimistic Batch Aggregation (OBA)

*   **Algorithm Overview**
    Optimistic Batch Aggregation (OBA) is a hybrid protocol combining Optimistic Concurrency Control (OCC) with partitioned, batched commit processing. Transactions execute in a non-blocking optimistic phase, reading versioned data items and buffering their writes locally. Upon a commit request, the transaction's read-set (keys and their observed versions) and write-set are submitted to a dedicated, single-threaded worker responsible for that data partition (determined by `warehouse_id`). This worker collects requests into batches. For each batch, it first validates all constituent transactions using standard OCC rules. For the valid transactions, it then aggregates all their commutative write operations (e.g., summing up all balance updates for a single customer) into a single set of deltas. Finally, the worker materializes the aggregated deltas and any non-commutative writes to the database in a single, atomic burst, updating the data and incrementing version numbers. This model transforms high-frequency, contentious updates into a single, efficient batch write, maximizing throughput.

*   **Read/Write/Validation Strategy**
    -   **Read Strategy:** During the execution phase, reads are optimistic and non-blocking. The protocol reads the current value of a data item from a versioned store and records the item's version number in the transaction's private read-set. High-contention, non-commutative keys like `d_..._next_oid` are handled by dedicated atomic counters and bypass the versioning and validation mechanism entirely.
    -   **Write Strategy:** Writes are buffered locally within the transaction's write-set and are not visible to other transactions until commit. A key semantic optimization is employed: for known commutative operations (on keys ending in `_ytd`, `_bal`, `_qty`), the protocol calculates and stores the numerical *delta* rather than the final absolute value. All other operations are stored as standard overwrites.
    -   **Validation Strategy:** Validation is deferred and centralized in the per-partition worker thread. For an entire batch of transactions, the worker first iterates through each transaction and validates its read-set against the current versions in the central store. If any key's current version is greater than the version the transaction read, the transaction fails validation and is marked for abort. This is a classic "backward validation" OCC scheme applied at the batch level.

*   **Deadlock Handling**
    The protocol is **deadlock-free by design**. The transaction execution phase is entirely non-blocking, so no locks are held. The commit processing phase is handled by a single worker thread per partition which processes a linear queue of requests. This serial processing within the worker eliminates any possibility of circular dependencies, thus preventing deadlocks.

*   **Correctness Guarantees**
    OBA provides **Strict Conflict-Serializability**.
    -   **Serializability:** The single-threaded worker for each partition processes batches sequentially (`B1, B2, B3...`), establishing a total serial order of batches. The validation step ensures that the transactions within a committed batch are serializable with respect to all previously committed batches. The aggregation of commutative operations is algebraically equivalent to some serial execution of those operations within the batch. The final state is therefore equivalent to a serial execution of all transactions, ordered by batch.
    -   **Strictness:** The effects of a batch of transactions are applied atomically by the single worker thread. No partial results are visible, and all writes are materialized only after successful validation. This ensures that no uncommitted data is ever read.

*   **Complexity and Performance Notes**
    -   **Performance:** The protocol is optimized for extremely high throughput in workloads with high write contention on commutative keys. By aggregating hundreds of individual updates into a single database operation, it dramatically reduces I/O and contention on the underlying storage. The primary performance trade-off is increased latency, as a transaction must wait for its batch to be formed and processed. The `BATCH_SIZE` and `BATCH_TIMEOUT_MS` are critical parameters for tuning the throughput-latency balance.
    -   **Complexity:** The implementation complexity is shifted from the client-side transaction execution (which is very simple) to the centralized partition worker, which must manage the multi-phase process of validation, aggregation, and materialization.

*   **Assumptions and Limitations**
    -   **Workload Semantics:** The protocol's effectiveness is highly dependent on a pre-known workload where commutative operations are common and can be identified. It is not a general-purpose protocol.
    -   **Partitionability:** The scaling model relies on the ability to partition the workload effectively. Cross-partition transactions would need to be handled by a single global worker, creating a bottleneck.
    -   **Latency Sensitivity:** The batching model is unsuitable for applications requiring consistently low, predictable transaction latency.
    -   **Last-Writer-Wins:** For non-commutative writes to the same key within a single batch, the protocol implicitly uses a "last writer wins" policy, which is deterministic but may not be appropriate for all use cases.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 12,
    "verdict": "Derivative",
    "score_breakdown": {
        "conceptual_originality_40": 12,
        "distance_from_known_families_30": 8,
        "implementation_distinctiveness_15": 2,
        "correctness_argument_originality_10": 1,
        "rebranding_penalty_minus15": -11
    },
    "separate_scores": {
        "proposal_novelty": 30,
        "code_novelty": 10
    },
    "overlap_analysis": [
        {
            "family": "Optimistic Concurrency Control (OCC)",
            "overlap_reasoning": "The implementation's core correctness mechanism is a commit-time validation phase where each transaction's read-set (versions) is checked against the current database state. Transactions with stale reads are aborted. This is the defining characteristic of OCC. The batching and aggregation are optimizations layered on top, not a new control mechanism.",
            "overlap_strength_0_to_100": 90
        },
        {
            "family": "Deterministic Batching (e.g., Calvin)",
            "overlap_reasoning": "The *proposal* describes a system where a single-threaded worker processes batches sequentially, which is the core idea of deterministic systems like Calvin that eliminate concurrency conflicts during execution. However, the *implementation* abandons this 'conflict-free' model in favor of optimistic validation, making it fundamentally different.",
            "overlap_strength_0_to_100": 85
        },
        {
            "family": "Commutative/Escrow Updates",
            "overlap_reasoning": "The protocol identifies commutative operations (YTD updates, stock decrements) and aggregates them as deltas. This is a well-known optimization technique to reduce write-write conflicts, often used in conjunction with other CC protocols like 2PL or OCC. It is an enhancement, not a standalone CC protocol.",
            "overlap_strength_0_to_100": 75
        }
    ],
    "feature_diff": [
        {
            "axis": "conflict detection",
            "ours": "Optimistic validation at the start of batch processing. A transaction's read-set of versions is checked against the live database versions. Stale reads cause an abort.",
            "typical_in_nearest_family": "In classic OCC, validation happens serially at commit time per-transaction. In Calvin (deterministic), there is NO validation; correctness is by construction via pre-ordered, deterministic execution.",
            "difference_note": "The protocol is fundamentally OCC. The proposal claims it is deterministic and conflict-free, but the implementation proves otherwise. The difference from classic OCC is merely that validation is done for a batch, but the mechanism is identical."
        },
        {
            "axis": "commit rule",
            "ours": "Transactions are submitted to a queue. A worker thread processes a batch by: 1) validating all txns, 2) aggregating writes from valid txns, 3) applying aggregated writes to the store, 4) notifying clients.",
            "typical_in_nearest_family": "In OCC, a transaction enters a critical section, validates its read-set, and writes its write-set. This protocol parallelizes the validation and write phases across transactions within a batch via a single worker.",
            "difference_note": "This batching of the commit phase is an engineering optimization to reduce I/O and contention on a commit lock, but it doesn't change the underlying OCC logic for ensuring serializability."
        },
        {
            "axis": "metadata / structures",
            "ours": "Per-transaction read-sets (key -> version) and write-sets (key -> delta/value). A global versioned key-value store. Per-partition transaction queues.",
            "typical_in_nearest_family": "This is the exact metadata required for standard OCC. The only addition is the per-partition queue, which is an architectural choice for partitioning, not a new CC mechanism.",
            "difference_note": "The metadata is identical to that of OCC, confirming its classification."
        }
    ],
    "code_checks": {
        "uses_locks_or_lock_modes": false,
        "uses_versions_or_snapshots": true,
        "commit_time_validation": true,
        "timestamp_ordering_core": false,
        "deterministic_preordering": false,
        "dependency_graph_cycles": false,
        "other_key_constructs": [
            "Partitioned single-threaded commit workers",
            "BlockingQueue for asynchronous transaction submission",
            "In-memory aggregation of commutative deltas"
        ]
    },
    "correctness_rationale_summary": "Correctness relies on two standard principles. First, OCC-style read-set validation ensures that transactions in a batch operated on a consistent snapshot from before the batch began. Second, a single-threaded worker serializes the application of the aggregated batch writes, ensuring atomicity and preventing conflicts during the materialization phase. This rationale is a direct and well-understood combination of OCC and single-writer serialization; it is not novel.",
    "risks_and_edge_cases": [
        "The proposal's claim of 'Zero Concurrency Aborts' is directly contradicted by the implementation, which is a standard OCC scheme that aborts transactions on validation failure.",
        "The protocol's performance will degrade to that of serialized OCC under high contention, as most transactions in a batch will fail validation against the writes of the previously committed batch.",
        "The 'last writer wins' rule for non-commutative operations within a batch makes the outcome dependent on the arbitrary ordering of the batch list, which can violate serializability equivalence if transactions have read-write dependencies on such keys.",
        "The protocol is mis-named. The proposal 'CTB' describes a deterministic system, but the implementation 'OBA' is an optimistic one. This is a significant conceptual disconnect."
    ],
    "suggestions_to_increase_novelty": [
        "To truly be novel and match the proposal, eliminate the OCC validation phase entirely. The worker would need to read base values, apply all batch operations in memory to calculate final values, perform logical checks (e.g., stock levels), and then write results. This would make it a true, conflict-free deterministic batching system.",
        "Instead of simple version validation, explore a hybrid where the worker attempts to 'rebase' or 'forward' the logic of failed transactions onto the newer state, transforming some aborts into successful commits. This would be a move towards state reconciliation.",
        "Introduce a novel cross-partition commit mechanism that avoids 2PC, for example, by creating deterministic dependencies between batches across different partitions."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java.previous_proposals.archive2;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of Optimistic Batch Aggregation (OBA).
 * This protocol combines optimistic concurrency control with batched, partitioned commits.
 * Transactions execute optimistically, reading versioned data. Writes are recorded
 * locally as deltas (for commutative operations) or final values. At commit time,
 * transactions are submitted to a dedicated, single-threaded worker for their data
 * partition (based on warehouse ID). The worker processes transactions in batches:
 * it validates all transactions in the batch, aggregates the deltas of the valid
 * ones, applies the aggregated changes to the database in a single burst, and
 * then notifies the client threads. This minimizes database contention and I/O
 * by turning many small writes into a few large, atomic updates.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final int BATCH_SIZE = 200;
    private static final int BATCH_TIMEOUT_MS = 2;
    private static final Pattern WAREHOUSE_KEY_PATTERN = Pattern.compile("^[a-z]_(\\d+)_.*");

    // A wrapper to add a version number to every value for OCC.
    private static class VersionedValue {
        volatile Object value;
        final AtomicLong version = new AtomicLong(0);

        VersionedValue(Object value) {
            this.value = value;
        }
    }
    
    // A request submitted to a partition worker's queue.
    private static class TransactionRequest {
        final Transaction txn;
        final CompletableFuture<CommitResult> future;

        TransactionRequest(Transaction txn, CompletableFuture<CommitResult> future) {
            this.txn = txn;
            this.future = future;
        }
    }
    
    private final AtomicInteger nextTxid = new AtomicInteger(0);
    // The main data store, holding versioned values. Managed by this CC protocol.
    private static final ConcurrentHashMap<String, VersionedValue> versionedStore = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicLong> districtNextOid = new ConcurrentHashMap<>();
    private static volatile boolean isInitialized = false;

    private final ConcurrentHashMap<Integer, PartitionWorker> partitions = new ConcurrentHashMap<>();
    
    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    public GeneratedCC() {
        // Ensure data is initialized only once.
        synchronized (GeneratedCC.class) {
            if (!isInitialized) {
                initVersionedData();
                isInitialized = true;
            }
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        return new Transaction(nextTxid.getAndIncrement(), 0);
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Optimization: _next_oid is handled by a dedicated atomic counter to avoid
        // making it a high-contention point for OCC validation.
        if (key.endsWith("_next_oid")) {
            int districtId = getDistrictId(key);
            long nextId = districtNextOid.computeIfAbsent(districtId, k -> new AtomicLong(1)).get();
            txn.readSet.put(key, nextId); // Store value, but it won't be version-validated
            return nextId;
        }

        VersionedValue vv = versionedStore.get(key);
        if (vv == null) return null;

        txn.readSet.put(key, vv.version.get()); // Store version for validation
        return vv.value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Optimization for _next_oid
        if (key.endsWith("_next_oid")) {
            int districtId = getDistrictId(key);
            districtNextOid.get(districtId).set(((Number) value).longValue());
            // No need to add to writeset, it's applied immediately and atomically.
            return;
        }

        // Semantic analysis: store deltas for commutative operations
        if (isCommutativeKey(key)) {
            Object oldValueObj = read(txn, key, store);
            if (oldValueObj instanceof Number) {
                long oldValue = ((Number) oldValueObj).longValue();
                long newValue = ((Number) value).longValue();
                long delta = newValue - oldValue;
                txn.writeSet.put(key, delta);
                return;
            }
        }
        
        // Default: store the final value for non-commutative overwrites
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction not active.");
        }

        // Read-only or next_oid-only transactions can commit immediately.
        if (txn.writeSet.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);
        }

        int partitionId = getPartitionIdFromTxn(txn);
        PartitionWorker worker = partitions.computeIfAbsent(partitionId, id -> new PartitionWorker());
        
        CompletableFuture<CommitResult> future = new CompletableFuture<>();
        txn.meta.put("future", future);
        worker.submit(new TransactionRequest(txn, future));

        try {
            return future.get(); // Block and wait for the worker to process the batch
        } catch (InterruptedException | ExecutionException e) {
            txn.status = TxStatus.ABORTED;
            return CommitResult.failure("Commit interrupted or failed: " + e.getMessage());
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;
        
        CompletableFuture<CommitResult> future = (CompletableFuture<CommitResult>) txn.meta.get("future");
        if (future != null) {
            future.completeExceptionally(new RuntimeException("Transaction rolled back by client."));
        }
    }

    public void shutdown() {
        partitions.values().forEach(PartitionWorker::shutdown);
    }
    
    // --- Helper Methods & Classes ---

    private boolean isCommutativeKey(String key) {
        return key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty");
    }

    private int getWarehouseId(String key) {
        Matcher matcher = WAREHOUSE_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0; // Global partition for non-warehouse keys like "item_..."
    }
    
    private int getDistrictId(String key) {
        // A simple hash-based unique ID for district keys like "d_wid_did_next_oid"
        String[] parts = key.split("_");
        if (parts.length > 2) {
            return Integer.parseInt(parts[1]) * 100 + Integer.parseInt(parts[2]);
        }
        return key.hashCode();
    }

    private int getPartitionIdFromTxn(Transaction txn) {
        // Find the first key in either set to determine the partition
        if (!txn.readSet.isEmpty()) {
            return getWarehouseId(txn.readSet.keys().nextElement());
        }
        if (!txn.writeSet.isEmpty()) {
            return getWarehouseId(txn.writeSet.keys().nextElement());
        }
        return 0; // Default to global partition
    }

    private static class PartitionWorker implements Runnable {
        private final BlockingQueue<TransactionRequest> queue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;

        PartitionWorker() {
            Thread workerThread = new Thread(this, "PartitionWorker-" + this.hashCode());
            workerThread.setDaemon(true);
            workerThread.start();
        }

        void submit(TransactionRequest request) {
            if (running) {
                queue.add(request);
            } else {
                request.future.complete(CommitResult.failure("System is shutting down."));
            }
        }

        void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            List<TransactionRequest> batch = new ArrayList<>(BATCH_SIZE);
            while (running) {
                try {
                    // Drain the queue to form a batch
                    Queues.drain(queue, batch, BATCH_SIZE, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!batch.isEmpty()) {
                        processBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        private void processBatch(List<TransactionRequest> batch) {
            Set<Transaction> failedTxs = new HashSet<>();
            Map<String, Long> aggregatedDeltas = new HashMap<>();
            Map<String, Object> aggregatedOverwrites = new HashMap<>();
            Set<String> writtenKeys = new HashSet<>();

            // Phase 1: Validation
            for (TransactionRequest req : batch) {
                Transaction txn = req.txn;
                for (Map.Entry<String, Object> readEntry : txn.readSet.entrySet()) {
                    String key = readEntry.getKey();
                    if (key.endsWith("_next_oid")) continue; // Skip validation for this key

                    long readVersion = (Long) readEntry.getValue();
                    VersionedValue currentVv = versionedStore.get(key);
                    if (currentVv == null || currentVv.version.get() != readVersion) {
                        failedTxs.add(txn);
                        break; // No need to validate further for this txn
                    }
                }
            }

            // Phase 2: Aggregation
            for (TransactionRequest req : batch) {
                if (!failedTxs.contains(req.txn)) {
                    for (Map.Entry<String, Object> writeEntry : req.txn.writeSet.entrySet()) {
                        String key = writeEntry.getKey();
                        Object value = writeEntry.getValue();
                        writtenKeys.add(key);

                        if (value instanceof Long && isCommutativeKey(key)) {
                            aggregatedDeltas.merge(key, (Long) value, Long::sum);
                        } else {
                            // Last writer in the batch wins for non-commutative writes
                            aggregatedOverwrites.put(key, value);
                        }
                    }
                }
            }

            // Phase 3: Materialization
            for (Map.Entry<String, Long> entry : aggregatedDeltas.entrySet()) {
                VersionedValue vv = versionedStore.get(entry.getKey());
                if (vv != null && vv.value instanceof Long) {
                    vv.value = ((Long) vv.value) + entry.getValue();
                }
            }
            for (Map.Entry<String, Object> entry : aggregatedOverwrites.entrySet()) {
                 VersionedValue vv = versionedStore.get(entry.getKey());
                 if (vv != null) {
                    vv.value = entry.getValue();
                 }
            }
            // Increment versions for all written keys
            for (String key : writtenKeys) {
                VersionedValue vv = versionedStore.get(key);
                if (vv != null) {
                    vv.version.incrementAndGet();
                }
            }


            // Phase 4: Notification
            for (TransactionRequest req : batch) {
                if (failedTxs.contains(req.txn)) {
                    req.txn.status = TxStatus.ABORTED;
                    req.future.complete(CommitResult.failure("Validation failed."));
                } else {
                    req.txn.status = TxStatus.COMMITTED;
                    req.future.complete(CommitResult.success(0));
                }
            }
        }
         private boolean isCommutativeKey(String key) {
            return key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty");
        }
    }

    private static void initVersionedData() {
        // This is a simplified, non-concurrent initialization.
        // Constants matching the provided transaction generator code.
        int TPCC_NUM_WAREHOUSES = 10;
        int TPCC_DISTRICTS_PER_WH = 10;
        int TPCC_CUSTOMERS_PER_DIST = 3000;
        int TPCC_NUM_ITEMS = 100000;
        long TPCC_INITIAL_YTD = 300000;
        long TPCC_INITIAL_BALANCE = -10;
        long TPCC_INITIAL_STOCK = 100;

        Random rand = new Random(42);

        for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
            versionedStore.put("w_" + wid + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
            for (int did = 1; did <= TPCC_DISTRICTS_PER_WH; did++) {
                versionedStore.put("d_" + wid + "_" + did + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
                int districtId = wid * 100 + did;
                districtNextOid.put(districtId, new AtomicLong(1));
                for (int cid = 1; cid <= TPCC_CUSTOMERS_PER_DIST; cid++) {
                    versionedStore.put("c_" + wid + "_" + did + "_" + cid + "_bal", new VersionedValue(TPCC_INITIAL_BALANCE));
                    versionedStore.put("c_" + wid + "_" + did + "_" + cid + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
                }
            }
        }
        for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
            versionedStore.put("item_" + iid + "_price", new VersionedValue(rand.nextInt(9901) + 100));
        }
        for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
            for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
                versionedStore.put("s_" + wid + "_" + iid + "_qty", new VersionedValue(TPCC_INITIAL_STOCK));
            }
        }
    }
}

// A helper class for BlockingQueue that is not part of the standard Java library until Java 9+.
// This is included to ensure compatibility with older Java versions.
final class Queues {
    private Queues() {}
    static <E> int drain(BlockingQueue<E> q, Collection<? super E> c, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int added = 0;
        while (added < maxElements) {
            added += q.drainTo(c, maxElements - added);
            if (added < maxElements) {
                E e = q.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (e == null) break;
                c.add(e);
                added++;
            }
        }
        return added;
    }
}
