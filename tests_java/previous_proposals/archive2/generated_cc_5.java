/* Technical Report:
### Final Technical Report: Speculative Partitioned Locking with Escrow (SPLE)

**1. Algorithm Overview**

Speculative Partitioned Locking with Escrow (SPLE) is a hybrid concurrency control protocol designed for workloads with pre-known transaction semantics, specifically the provided TPC-C-like workload. The core idea is to partition the dataset by a high-locality key (`warehouse_id`) and apply a specialized, highly concurrent control mechanism within each partition.

-   **Partitioning:** Data is logically partitioned by `warehouse_id`. All operations for different warehouses run in parallel without any cross-partition coordination.
-   **Hybrid Control:** Within a partition, SPLE uses two different strategies based on the operation's semantics:
    1.  **Escrow for Commutative Operations:** For blind, commutative updates (e.g., updating year-to-date counters or customer balances in `tpccPayment`), the protocol avoids data locks. Instead, update deltas are applied to a shared, in-memory "escrow" accumulator. This allows numerous transactions performing these updates to execute concurrently.
    2.  **Strict Two-Phase Locking (S2PL):** For operations with read-write dependencies or conditional logic (e.g., checking and updating stock levels in `tpccNewOrder`), the protocol uses traditional fine-grained exclusive locks, which are held until the transaction commits or aborts.

**2. Read/Write/Validation Strategy**

-   **Read Strategy:**
    -   **Escrowed Keys (`_ytd`, `_bal`):** Reads are speculative. The transaction acquires a short-lived partition-level *read lock*, reads the committed base value from storage, records this base value for later validation, and adds the current delta from the shared escrow map. The lock is released immediately after the read.
    -   **Standard Keys:** The transaction acquires a fine-grained lock on the specific key, which is held until the end of the transaction. It then reads the value from storage.

-   **Write Strategy:**
    -   **Escrowed Keys:** The transaction calculates the delta between its previously read value and the new value. It then atomically adds this delta to the key's corresponding value in the partition's shared escrow map. This change is immediately visible to subsequent speculative reads from other transactions.
    -   **Standard Keys:** The transaction acquires a fine-grained exclusive lock on the key and buffers the new value in its private write-set.

-   **Commit & Validation Strategy:**
    1.  The transaction acquires an exclusive, partition-level *write lock*, serializing all commits within that partition.
    2.  **Validation Phase:** It iterates through all escrowed keys it speculatively read. For each, it re-reads the base value from storage and compares it to the base value it recorded during the initial read. If any value has changed, it means another transaction committed and invalidated its read. The current transaction immediately aborts.
    3.  **Apply Phase:** If validation succeeds, the transaction applies its changes. It atomically merges its escrow deltas into the key-value store (using efficient `increment` operations) and applies its buffered standard writes.
    4.  Finally, it releases all fine-grained locks and the partition-level write lock.

**3. Deadlock Handling**

Deadlock is handled via a timeout-based prevention mechanism. When acquiring fine-grained locks for standard keys, a transaction uses `tryLock` with a short timeout (10ms). If the lock cannot be acquired within this period, the transaction assumes a potential deadlock, aborts immediately, releases all its resources, and can be retried.

**4. Correctness Guarantees**

The protocol ensures **Strict Conflict-Serializability**.
-   **Inter-Partition:** Transactions operating on different warehouses have disjoint write sets, so any interleaving is serializable by default.
-   **Intra-Partition:** Serializability within a partition is guaranteed by the combination of three mechanisms:
    1.  **S2PL:** Guarantees serializability for all conflicts on standard, non-escrowed data.
    2.  **Validation:** The commit-time validation step prevents non-repeatable reads on escrowed data, breaking potential read-write dependency cycles that would otherwise violate serializability.
    3.  **Serialized Commits:** The exclusive partition-level write lock establishes a total, unambiguous commit order for all transactions within a partition. The final database state is equivalent to executing the transactions serially in this commit order.

**5. Complexity and Performance Notes**

-   **Performance:** SPLE is optimized to maximize concurrency for the `tpccPayment` transaction, which becomes nearly lock-free during execution and only serializes briefly at commit. This significantly reduces contention on hotspot keys like warehouse and district YTDs. `tpccNewOrder` transactions still face locking but benefit from the reduced conflict with `Payment` transactions. The primary bottleneck shifts from data-item hotspots to the throughput of the per-partition commit lock.
-   **Complexity:** The implementation is more complex than standard protocols, requiring semantic analysis of keys and managing a hybrid state of locks and escrow values.

**6. Assumptions and Limitations**

-   **Assumptions:** The protocol's effectiveness relies on the ability to statically analyze the workload and schema to:
    1.  Reliably partition the data (e.g., by `warehouse_id`).
    2.  Correctly identify which operations are commutative and can use the escrow mechanism.
-   **Limitations:**
    -   The protocol is not general-purpose. It would perform poorly on workloads that are not easily partitionable or lack a significant number of commutative update operations.
    -   High contention on escrowed keys can lead to a high abort rate due to frequent validation failures, though this is preferable to serialization violations.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 28,
    "verdict": "Minor Variation",
    "score_breakdown": {
        "conceptual_originality_40": 12,
        "distance_from_known_families_30": 8,
        "implementation_distinctiveness_15": 5,
        "correctness_argument_originality_10": 1,
        "rebranding_penalty_minus15": -2
    },
    "separate_scores": {
        "proposal_novelty": 35,
        "code_novelty": 20
    },
    "overlap_analysis": [
        {
            "family": "Partitioning (H-Store/VoltDB)",
            "overlap_reasoning": "The top-level design is based on statically partitioning the workload by `warehouse_id`, a foundational technique in systems like H-Store to eliminate cross-partition coordination. This part is directly borrowed.",
            "overlap_strength_0_to_100": 90
        },
        {
            "family": "Escrow / Semantic Locking (O'Neil 1986)",
            "overlap_reasoning": "The core idea of handling blind, commutative updates by applying deltas to a separate counter instead of locking the data item is the classic Escrow method. SPLE applies this known pattern to specific keys.",
            "overlap_strength_0_to_100": 85
        },
        {
            "family": "Strict 2PL",
            "overlap_reasoning": "For non-escrowed data, the protocol uses fine-grained read/write locks that are held until the end of the transaction. This is a textbook implementation of Strict Two-Phase Locking.",
            "overlap_strength_0_to_100": 100
        },
        {
            "family": "OCC (Kung-Robinson)",
            "overlap_reasoning": "The implementation's commit phase includes a validation step for escrowed reads. It checks if the base value read at the start of the transaction is still the same at commit time. This read-set validation is the hallmark of Optimistic Concurrency Control.",
            "overlap_strength_0_to_100": 75
        }
    ],
    "feature_diff": [
        {
            "axis": "Core Mechanism Integration",
            "ours": "A single protocol uses a static heuristic (`isEscrowKey`) to route operations within the same transaction to either a pessimistic (2PL) or an optimistic (Escrow+Validation) path, all within a partitioned context.",
            "typical_in_nearest_family": "Systems typically choose one primary mechanism (e.g., all 2PL, all OCC) or use them for different transaction types, not for different operations within a single transaction based on key patterns.",
            "difference_note": "This integration is the main point of distinction. However, it's an engineering combination of known primitives rather than a fundamentally new control mechanism. The novelty is in the specific recipe, not the ingredients."
        },
        {
            "axis": "Commit Rule",
            "ours": "Commits are serialized by a coarse-grained, partition-level write lock. Inside this critical section, an OCC-style validation is performed for escrowed reads, followed by the application of all writes (both escrowed and buffered).",
            "typical_in_nearest_family": "Classic OCC validates against a global commit counter. 2PL has no validation phase. Silo-like systems use locking to protect the commit phase, but SPLE adds an explicit validation check before that.",
            "difference_note": "The combination of a coarse-grained commit lock with an intra-lock validation phase is a specific implementation choice, but it doesn't introduce a new correctness invariant. It's a way to bolt an optimistic component onto a locking-based commit serialization point."
        }
    ],
    "code_checks": {
        "uses_locks_or_lock_modes": true,
        "uses_versions_or_snapshots": false,
        "commit_time_validation": true,
        "timestamp_ordering_core": false,
        "deterministic_preordering": false,
        "dependency_graph_cycles": false,
        "other_key_constructs": [
            "Partition-level ReadWriteLock for commit serialization",
            "Public, live escrow accumulators (ConcurrentHashMap<String, AtomicLong>)",
            "Static, key-name-based heuristic to select CC mechanism"
        ]
    },
    "correctness_rationale_summary": "Correctness is asserted via a composite argument. Inter-partition serializability comes from disjointness. Intra-partition serializability relies on three pillars: 1) Strict 2PL for general data, 2) OCC-style validation for reads of escrowed data, and 3) a final, partition-wide exclusive lock that serializes all commit decisions and write applications, establishing a total order. This rationale is a standard composition of arguments for its constituent, well-known parts and is not novel in itself.",
    "risks_and_edge_cases": [
        "The protocol's performance is entirely dependent on the workload matching the static `isEscrowKey` heuristic. Changes in the workload could lead to severe contention.",
        "The use of a single partition-level write lock for all commits creates a serial bottleneck, limiting intra-partition scalability under high commit rates.",
        "The implementation immediately aborts any transaction that touches more than one partition, which is a significant functional limitation.",
        "The protocol degenerates to partitioned 2PL if no keys are escrow-able, or to a partitioned OCC variant if all keys are escrow-able, revealing it as a point on a spectrum of known designs."
    ],
    "suggestions_to_increase_novelty": [
        "Replace the static `isEscrowKey` heuristic with a dynamic or adaptive mechanism that learns which data items are hotspots suitable for escrow treatment.",
        "Eliminate the OCC-style validation step. This would require a new mechanism to ensure correctness for speculative reads, potentially by integrating versioning or creating a novel invariant that proves reads of `base + live_delta` are serializable without re-validation.",
        "Generalize the escrow mechanism to support a broader class of commutative operations, moving it from a simple counter to a more CRDT-like approach, and define conflict semantics accordingly."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java.previous_proposals.archive2;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.TimeUnit;

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, PartitionMetadata> partitions = new ConcurrentHashMap<>();

    private static final String PARTITION_ID_KEY = "partition_id";
    private static final String HELD_LOCKS_KEY = "held_locks";
    private static final String ESCROW_DELTAS_KEY = "escrow_deltas";
    private static final String ESCROW_BASE_READS_KEY = "escrow_base_reads"; // For validation
    private static final long LOCK_TIMEOUT_MS = 10;

    private static class PartitionMetadata {
        final ReadWriteLock partitionGate = new ReentrantReadWriteLock();
        final ConcurrentHashMap<String, AtomicLong> escrowValues = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, Lock> fineGrainedLocks = new ConcurrentHashMap<>();
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        return new Transaction(txid, (int) System.nanoTime());
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        if (txn.readSet.containsKey(key)) return txn.readSet.get(key);

        int partitionId = getWarehouseId(key);
        if (txn.meta.get(PARTITION_ID_KEY) == null) {
            txn.meta.put(PARTITION_ID_KEY, partitionId);
        } else if ((int) txn.meta.get(PARTITION_ID_KEY) != partitionId) {
            txn.status = TxStatus.ABORTED;
            return null;
        }
        PartitionMetadata pmd = partitions.computeIfAbsent(partitionId, k -> new PartitionMetadata());

        Object value;
        if (isEscrowKey(key)) {
            pmd.partitionGate.readLock().lock();
            try {
                Object baseValueObj = store.get(key);
                long baseValue = 0;
                if (baseValueObj instanceof AtomicLong) {
                    baseValue = ((AtomicLong) baseValueObj).get();
                } else if (baseValueObj instanceof Number) {
                    baseValue = ((Number) baseValueObj).longValue();
                }
                
                @SuppressWarnings("unchecked")
                Map<String, Long> baseReads = (Map<String, Long>) txn.meta.computeIfAbsent(ESCROW_BASE_READS_KEY, k -> new HashMap<>());
                baseReads.put(key, baseValue);

                long deltaValue = pmd.escrowValues.getOrDefault(key, new AtomicLong(0)).get();
                value = baseValue + deltaValue;
            } finally {
                pmd.partitionGate.readLock().unlock();
            }
        } else {
            Lock lock = pmd.fineGrainedLocks.computeIfAbsent(key, k -> new ReentrantLock());
            try {
                if (!lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    txn.status = TxStatus.ABORTED;
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                txn.status = TxStatus.ABORTED;
                return null;
            }

            @SuppressWarnings("unchecked")
            Set<Lock> heldLocks = (Set<Lock>) txn.meta.computeIfAbsent(HELD_LOCKS_KEY, k -> new HashSet<>());
            heldLocks.add(lock);

            Object baseValueObj = store.get(key);
            if (baseValueObj instanceof AtomicLong) {
                value = ((AtomicLong) baseValueObj).get();
            } else {
                value = baseValueObj;
            }
        }

        if (value != null) {
            txn.readSet.put(key, value);
        }
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        int partitionId = getWarehouseId(key);
        if (txn.meta.get(PARTITION_ID_KEY) == null) {
            txn.meta.put(PARTITION_ID_KEY, partitionId);
        } else if ((int) txn.meta.get(PARTITION_ID_KEY) != partitionId) {
            txn.status = TxStatus.ABORTED;
            return;
        }
        PartitionMetadata pmd = partitions.computeIfAbsent(partitionId, k -> new PartitionMetadata());

        if (isEscrowKey(key)) {
            Object oldValueObj = txn.readSet.get(key);
            if (oldValueObj == null) {
                txn.status = TxStatus.ABORTED;
                return;
            }

            long oldValue = ((Number) oldValueObj).longValue();
            long newValue = ((Number) value).longValue();
            long delta = newValue - oldValue;

            pmd.escrowValues.computeIfAbsent(key, k -> new AtomicLong(0)).addAndGet(delta);

            @SuppressWarnings("unchecked")
            Map<String, Long> deltas = (Map<String, Long>) txn.meta.computeIfAbsent(ESCROW_DELTAS_KEY, k -> new HashMap<>());
            deltas.put(key, deltas.getOrDefault(key, 0L) + delta);
        } else {
            Lock lock = pmd.fineGrainedLocks.computeIfAbsent(key, k -> new ReentrantLock());
            @SuppressWarnings("unchecked")
            Set<Lock> heldLocks = (Set<Lock>) txn.meta.get(HELD_LOCKS_KEY);
            if (heldLocks == null || !heldLocks.contains(lock)) {
                try {
                    if (!lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        txn.status = TxStatus.ABORTED;
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    txn.status = TxStatus.ABORTED;
                    return;
                }
                if (heldLocks == null) {
                    heldLocks = new HashSet<>();
                    txn.meta.put(HELD_LOCKS_KEY, heldLocks);
                }
                heldLocks.add(lock);
            }
        }
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return CommitResult.failure("Transaction aborted prior to commit");
        if (txn.status == TxStatus.COMMITTED) return CommitResult.failure("Transaction already committed");

        Integer partitionId = (Integer) txn.meta.get(PARTITION_ID_KEY);
        if (partitionId == null) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txn.startTs);
        }
        PartitionMetadata pmd = partitions.get(partitionId);

        pmd.partitionGate.writeLock().lock();
        try {
            // VALIDATION STEP for escrowed reads
            @SuppressWarnings("unchecked")
            Map<String, Long> baseReads = (Map<String, Long>) txn.meta.get(ESCROW_BASE_READS_KEY);
            if (baseReads != null) {
                for (Map.Entry<String, Long> entry : baseReads.entrySet()) {
                    String key = entry.getKey();
                    long valueAtReadTime = entry.getValue();
                    Object baseValueObj = store.get(key);
                    long currentValue = 0;
                    if (baseValueObj instanceof AtomicLong) {
                        currentValue = ((AtomicLong) baseValueObj).get();
                    } else if (baseValueObj instanceof Number) {
                        currentValue = ((Number) baseValueObj).longValue();
                    }
                    
                    if (valueAtReadTime != currentValue) {
                        rollbackEscrow(txn, pmd);
                        txn.status = TxStatus.ABORTED;
                        return CommitResult.failure("Validation failed on key: " + key);
                    }
                }
            }

            // APPLY STEP
            @SuppressWarnings("unchecked")
            Map<String, Long> deltas = (Map<String, Long>) txn.meta.get(ESCROW_DELTAS_KEY);
            if (deltas != null) {
                for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                    store.increment(entry.getKey(), entry.getValue());
                    pmd.escrowValues.get(entry.getKey()).addAndGet(-entry.getValue());
                }
            }

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                if (!isEscrowKey(entry.getKey())) {
                    store.update(entry.getKey(), entry.getValue());
                }
            }
            txn.status = TxStatus.COMMITTED;
        } finally {
            releaseLocks(txn);
            pmd.partitionGate.writeLock().unlock();
        }
        return CommitResult.success(txn.startTs);
    }
    
    private void rollbackEscrow(Transaction txn, PartitionMetadata pmd) {
        @SuppressWarnings("unchecked")
        Map<String, Long> deltas = (Map<String, Long>) txn.meta.get(ESCROW_DELTAS_KEY);
        if (deltas != null && pmd != null) {
            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                pmd.escrowValues.computeIfPresent(entry.getKey(), (k, v) -> {
                    v.addAndGet(-entry.getValue());
                    return v;
                });
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;

        Integer partitionId = (Integer) txn.meta.get(PARTITION_ID_KEY);
        if (partitionId != null) {
            PartitionMetadata pmd = partitions.get(partitionId);
            rollbackEscrow(txn, pmd);
        }
        releaseLocks(txn);
    }

    private void releaseLocks(Transaction txn) {
        @SuppressWarnings("unchecked")
        Set<Lock> heldLocks = (Set<Lock>) txn.meta.get(HELD_LOCKS_KEY);
        if (heldLocks != null) {
            for (Lock lock : heldLocks) {
                if (lock instanceof ReentrantLock) {
                    ReentrantLock rLock = (ReentrantLock) lock;
                    if (rLock.isHeldByCurrentThread()) {
                         rLock.unlock();
                    }
                }
            }
        }
    }

    private static int getWarehouseId(String key) {
        String[] parts = key.split("_");
        if (parts.length > 1 && (parts[0].equals("w") || parts[0].equals("d") || parts[0].equals("c") || parts[0].equals("s") || parts[0].equals("o"))) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isEscrowKey(String key) {
        return key.endsWith("_ytd") || key.endsWith("_bal");
    }
}
