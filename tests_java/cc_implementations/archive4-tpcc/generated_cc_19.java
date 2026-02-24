/* Technical Report:
### Final Technical Report: Temporal Semantic Partitioning (TSP)

**1. Algorithm Overview**
Temporal Semantic Partitioning (TSP) is a hybrid, multi-scheme concurrency control protocol specifically optimized for workloads with pre-known transaction semantics, such as the provided TPC-C benchmark. The core design partitions the keyspace by `warehouse_id` and classifies operations based on their semantic properties into one of two processing streams:

*   **Immediate Stream (Optimistic):** Handles conditional, non-commutative operations where the read value directly influences a subsequent write (e.g., checking and updating stock quantity). This stream uses a version-based optimistic concurrency control (OCC) protocol.
*   **Deferred Stream (Log-based):** Handles purely commutative arithmetic updates (e.g., updating balances and Year-To-Date figures). These operations are recorded as deltas in an in-memory, per-partition log and are considered committed immediately upon logging.
*   **Hotspot Offloading:** Manages high-contention, sequential ID generation (`d_..._next_oid`) using partition-local atomic counters, completely bypassing the transactional CC mechanism.

This dual-stream approach isolates the high-frequency, commutative `Payment` transactions from the more complex, contention-prone `NewOrder` transactions, drastically reducing conflicts and aborts.

**2. Read/Write and Validation Strategy**
*   **Read Strategy:**
    *   **Immediate Keys (`s_..._qty`):** Reads fetch a `VersionedValue` from the main store. The key and its version are stored in the transaction's `versionedReadSet` for later validation. The logic is robust to handle both its own `VersionedValue` format and the initial `AtomicLong` format from the data loader.
    *   **Deferred Keys (`..._bal`, `..._ytd`):** Reads are synthesized. The protocol reads a "base" value from the main store and then applies all relevant un-consolidated deltas from the partition's shared `deferredLog` and the transaction's private `deltaSet` to compute the current, correct value.
    *   **Hotspot Keys (`..._next_oid`):** A read triggers a non-transactional, atomic `getAndIncrement` on the partition's counter.
*   **Write Strategy:**
    *   **Immediate Keys:** Writes are buffered in the transaction's local `writeSet`. They are not applied to the store until the commit phase.
    *   **Deferred Keys:** Writes are converted into deltas (new value - old value) and buffered in the transaction's local `deltaSet`.
*   **Validation/Commit Strategy:**
    *   **Deferred-Only Transactions (`Payment`):** The commit operation is lock-free and validation-free. It consists of atomically appending the transaction's buffered deltas to the partition's shared `deferredLog`.
    *   **Immediate Transactions (`NewOrder`):** The commit is a two-phase process protected by a short-term, per-partition `ReentrantLock`:
        1.  **Validation Phase:** The protocol iterates through the transaction's `versionedReadSet`. For each key, it re-checks the current version in the main store against the version recorded at read time. If any mismatch is found, the transaction aborts.
        2.  **Write Phase:** If validation succeeds, the protocol applies all buffered writes from the `writeSet` to the main store, creating new `VersionedValue` objects with an incremented version number.

**3. Deadlock Handling**
The TSP protocol is **deadlock-free by design**. Deadlocks typically arise from circular lock-wait dependencies. TSP avoids this in two ways:
1.  The Deferred Stream is entirely lock-free.
2.  The Immediate Stream uses a single, short-term `commitLock` per partition, acquired only at the very end of the transaction. There is no scenario where a transaction holds one lock while waiting for another, thus preventing circular waits and deadlocks.

**4. Correctness Guarantees**
TSP guarantees **Strict Serializability** on a per-partition basis. Since the TPC-C workload is partitioned by warehouse and transactions do not cross partitions, this provides global serializability.
*   **Serializability:** The serialization order is established at the commit point. For Immediate transactions, the acquisition of the partition's `commitLock` provides a single, total ordering point. For Deferred transactions, the atomic append to the `deferredLog` establishes their logical commit order. The isolation between the two streams ensures that the state of deferred keys cannot invalidate an immediate transaction's read-set, preserving correctness.
*   **Strictness:** All writes are buffered locally and applied atomically at commit (either via logging or a locked write-back). No uncommitted data is ever visible to other transactions, satisfying the strictness property.

**5. Complexity and Performance Notes**
*   **Time Complexity:**
    *   `begin`, `write`, `rollback`: O(1).
    *   `read` (Deferred): O(L), where L is the current length of the partition's deferred log. This is a trade-off for high write throughput.
    *   `read` (Immediate/Hotspot): O(1).
    *   `commit` (Deferred): O(D), where D is the number of deltas in the transaction.
    *   `commit` (Immediate): O(R + W), where R and W are the sizes of the read and write sets, performed under a lock.
*   **Performance:** The protocol is highly performant for the target workload. The throughput for `Payment` transactions is exceptionally high due to the non-blocking, log-based commit. This significantly reduces overall system contention, allowing `NewOrder` transactions to commit with a lower abort rate. The performance scales well with the number of warehouses (partitions), as contention is contained within each partition.

**6. Assumptions and Limitations**
*   **Pre-Known Semantics:** The protocol's correctness and performance critically depend on the ability to statically analyze transaction code and classify operations as commutative, non-commutative, or read-only. It is not a general-purpose CC mechanism and would perform poorly or incorrectly on ad-hoc query workloads.
*   **Partitionable Workload:** The design assumes the workload can be cleanly partitioned (e.g., by `warehouse_id`) with no cross-partition transactions.
*   **Memory Usage:** The in-memory `deferredLog` consumes memory. In a system with a very high rate of deferred writes and slow consolidation, this could become a bottleneck. The current implementation does not include a consolidation mechanism, assuming the log size remains manageable for the benchmark's duration.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 42,
    "verdict": "Incremental Hybrid",
    "score_breakdown": {
        "conceptual_originality_40": 20.0,
        "distance_from_known_families_30": 15.0,
        "implementation_distinctiveness_15": 7.5,
        "correctness_argument_originality_10": 4.0,
        "rebranding_penalty_minus15": -5.0
    },
    "separate_scores": {
        "proposal_novelty": 45,
        "code_novelty": 40
    },
    "overlap_analysis": [
        {
            "family": "OCC (Optimistic Concurrency Control)",
            "overlap_reasoning": "The 'Immediate Stream' is a textbook, single-version OCC protocol. It uses private workspaces (read/write sets), a commit-time validation phase checking versions of keys in the read-set, and a locked write-phase. This is functionally identical to classic OCC.",
            "overlap_strength_0_to_100": 95
        },
        {
            "family": "Commutative Updates / Escrow",
            "overlap_reasoning": "The 'Deferred Stream' implements a classic commutative update pattern. Instead of locking or versioning, it logs deltas for operations known to be commutative (e.g., addition). This avoids conflict detection entirely for that class of operations, a well-known technique used in high-contention counters and CRDT-like systems.",
            "overlap_strength_0_to_100": 85
        },
        {
            "family": "Semantic Concurrency Control",
            "overlap_reasoning": "The protocol's core principle of using workload semantics (commutative vs. non-commutative) to select a CC strategy is a known approach. TSP is a specific, hardcoded instance of this general idea, tailored to a TPC-C-like workload.",
            "overlap_strength_0_to_100": 70
        },
        {
            "family": "Silo",
            "overlap_reasoning": "The use of a short-term lock to protect the atomic 'validate-then-write' phase of an OCC protocol is a key implementation detail of the Silo protocol. TSP adopts this same pattern for its Immediate Stream.",
            "overlap_strength_0_to_100": 60
        }
    ],
    "feature_diff": [
        {
            "axis": "Conflict Management Strategy",
            "ours": "A hybrid, static strategy. Operations on certain keys (e.g., `_bal`) are declared conflict-free and logged. Operations on other keys (e.g., `_qty`) are managed with OCC validation.",
            "typical_in_nearest_family": "A pure OCC system would treat all writes as potential conflicts, adding balance/YTD keys to the read-set and validating them at commit, causing aborts from concurrent updates.",
            "difference_note": "The key difference is the static partitioning of the keyspace into different CC schemes. This eliminates an entire class of conflicts (e.g., Payment vs. NewOrder) by design, trading generality for specialized performance."
        },
        {
            "axis": "Read Visibility",
            "ours": "Reads of 'Immediate' keys see the latest committed version. Reads of 'Deferred' keys must synthesize the value by combining the last consolidated value from the main store with a traversal of an in-memory delta log.",
            "typical_in_nearest_family": "In OCC, reads always see the latest committed version from the central store. In MVCC, they see a consistent snapshot. TSP's deferred read path is operationally distinct and more complex.",
            "difference_note": "This creates a performance trade-off: deferred writes are extremely fast, but deferred reads are potentially slow and their latency is non-deterministic, depending on the log length."
        }
    ],
    "code_checks": {
        "uses_locks_or_lock_modes": true,
        "uses_versions_or_snapshots": true,
        "commit_time_validation": true,
        "timestamp_ordering_core": false,
        "deterministic_preordering": false,
        "dependency_graph_cycles": false,
        "other_key_constructs": [
            "Per-partition in-memory delta log (ConcurrentLinkedQueue)",
            "Static, key-prefix-based semantic dispatcher (getKeyType)",
            "VersionedValue wrapper for OCC-managed keys"
        ]
    },
    "correctness_rationale_summary": "Correctness relies on isolating two well-understood mechanisms within partitions. The Immediate Stream's correctness comes from standard OCC validation, serialized by a commit lock. The Deferred Stream's correctness comes from the mathematical commutativity of its operations. The hybrid is correct because the workload semantics ensure no invalidation dependencies exist between streams (e.g., blind reads of deferred keys in immediate transactions). This rationale is sound but is an argument of composition and workload-specific properties, not a fundamentally new correctness proof.",
    "risks_and_edge_cases": [
        "The performance of reading 'Deferred' keys degrades linearly with the length of the `deferredLog`, creating a potential bottleneck if the consolidation process falls behind.",
        "The design is brittle and workload-specific. If a transaction's semantics change (e.g., a 'Payment' needs to conditionally read a stock level), the static classification fails, breaking the correctness model.",
        "The per-partition `commitLock` serializes all 'Immediate' transaction commits, which could become a bottleneck under high contention for `NewOrder` transactions, negating some of the parallelism gains.",
        "The memory footprint of the `deferredLog` is unbounded in this design, posing a risk in long-running partitions with many deferred writes."
    ],
    "suggestions_to_increase_novelty": [
        "Generalize the semantic classification. Instead of a static, hardcoded `getKeyType` function, develop a mechanism where transactions can declare operation semantics (e.g., 'commutative add', 'conditional write') dynamically, allowing the protocol to apply to ad-hoc workloads.",
        "Create a more integrated consistency model. Instead of complete isolation, design a validation rule where the state of the deferred log could influence the outcome of an immediate transaction (e.g., validating against a constraint that spans both streams).",
        "Improve the deferred read path. Instead of a linear scan of the log, use a versioned or indexed structure for the deferred deltas to provide fast, consistent snapshot reads, merging the benefits of MVCC with commutative logging."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements Temporal Semantic Partitioning (TSP), a concurrency control protocol
 * optimized for the provided TPC-C-like workload.
 *
 * Core Idea:
 * TSP partitions the workload by warehouse ID and processes operations in two streams:
 * 1. Immediate Stream (Optimistic): For conditional, non-commutative operations like
 *    stock level checks. These are validated at commit time using versioning.
 * 2. Deferred Stream (Log-based): For purely commutative updates like balance and
 *    YTD increments. These are logged as deltas and are considered committed
 *    immediately, avoiding locks and validation.
 *
 * This separation ensures that high-frequency Payment transactions (Deferred) do not
 * conflict with or block NewOrder transactions (Immediate), significantly boosting
 * throughput.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger nextTxid = new AtomicInteger(0);
    private final ConcurrentHashMap<Integer, PartitionState> partitions = new ConcurrentHashMap<>();

    private static final String META_PARTITION_ID = "partitionId";
    private static final String META_VERSIONED_READ_SET = "versionedReadSet";
    private static final String META_DELTA_SET = "deltaSet";

    // Helper classes
    private enum KeyType { IMMEDIATE, DEFERRED, HOTSPOT, READ_ONLY, UNKNOWN }

    private static class VersionedValue {
        final Object value;
        final long version;

        VersionedValue(Object value, long version) {
            this.value = value;
            this.version = version;
        }
    }

    private static class Delta {
        final String key;
        final long amount;

        Delta(String key, long amount) {
            this.key = key;
            this.amount = amount;
        }
    }

    private static class PartitionState {
        final ReentrantLock commitLock = new ReentrantLock();
        final AtomicInteger nextOidCounter = new AtomicInteger(1); // For d_..._next_oid
        final ConcurrentLinkedQueue<Delta> deferredLog = new ConcurrentLinkedQueue<>();
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        Transaction txn = new Transaction(nextTxid.getAndIncrement(), 0);
        txn.meta.put(META_VERSIONED_READ_SET, new ConcurrentHashMap<String, Long>());
        txn.meta.put(META_DELTA_SET, new ConcurrentHashMap<String, Long>());
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        PartitionState partition = getPartitionForTxn(txn, key);
        if (partition == null && getWarehouseIdFromKey(key) != null) {
             // Should not happen for valid keys, but handle defensively
            return store.get(key);
        }

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }
        Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(META_DELTA_SET);
        if (deltaSet.containsKey(key)) {
            Object baseValue = txn.readSet.getOrDefault(key, 0);
            long delta = deltaSet.get(key);
            return ((Number) baseValue).longValue() + delta;
        }

        Object value;
        KeyType type = getKeyType(key);

        switch (type) {
            case HOTSPOT:
                value = partition.nextOidCounter.getAndIncrement();
                break;
            case IMMEDIATE:
                Object rawObj = store.get(key);
                VersionedValue vv;
                if (rawObj instanceof VersionedValue) {
                    vv = (VersionedValue) rawObj;
                } else if (rawObj instanceof Number) {
                    // Handle initial data from initTpccData which is AtomicLong
                    vv = new VersionedValue(((Number) rawObj).longValue(), 0L);
                } else {
                    // Initialize if not present at all. TPC-C default is 100000.
                    vv = new VersionedValue(100000, 0L);
                }
                value = vv.value;
                ((Map<String, Long>) txn.meta.get(META_VERSIONED_READ_SET)).put(key, vv.version);
                break;
            case DEFERRED:
                Object baseValueObj = store.get(key);
                long baseValue = (baseValueObj instanceof Number) ? ((Number) baseValueObj).longValue() : 0;
                for (Delta d : partition.deferredLog) {
                    if (d.key.equals(key)) {
                        baseValue += d.amount;
                    }
                }
                value = baseValue;
                break;
            case READ_ONLY:
            default:
                value = store.get(key);
                break;
        }

        if (value != null) {
            txn.readSet.put(key, value);
        }
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        getPartitionForTxn(txn, key);
        
        KeyType type = getKeyType(key);

        if (type == KeyType.IMMEDIATE) {
            txn.writeSet.put(key, value);
        } else if (type == KeyType.DEFERRED) {
            Object originalValueObj = txn.readSet.get(key);
            if (originalValueObj == null) {
                originalValueObj = read(txn, key, store);
            }
            long originalValue = ((Number) originalValueObj).longValue();
            long newValue = ((Number) value).longValue();
            long delta = newValue - originalValue;
            
            Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(META_DELTA_SET);
            deltaSet.compute(key, (k, v) -> (v == null) ? delta : v + delta);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        PartitionState partition = getPartitionById((Integer) txn.meta.get(META_PARTITION_ID));
        if (partition == null) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);
        }

        if (txn.writeSet.isEmpty()) {
            Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(META_DELTA_SET);
            if (!deltaSet.isEmpty()) {
                for (Map.Entry<String, Long> entry : deltaSet.entrySet()) {
                    partition.deferredLog.add(new Delta(entry.getKey(), entry.getValue()));
                }
            }
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);
        }

        partition.commitLock.lock();
        try {
            Map<String, Long> versionedReadSet = (Map<String, Long>) txn.meta.get(META_VERSIONED_READ_SET);
            for (Map.Entry<String, Long> entry : versionedReadSet.entrySet()) {
                String key = entry.getKey();
                long readVersion = entry.getValue();
                
                Object rawObj = store.get(key);
                long currentVersion = 0L;
                if (rawObj instanceof VersionedValue) {
                    currentVersion = ((VersionedValue) rawObj).version;
                } // If it's a Number or null, its effective version is 0.

                if (readVersion != currentVersion) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Version conflict on key: " + key);
                }
            }

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                Object newValue = entry.getValue();
                
                Object rawObj = store.get(key);
                long currentVersion = 0L;
                if (rawObj instanceof VersionedValue) {
                    currentVersion = ((VersionedValue) rawObj).version;
                }

                long newVersion = currentVersion + 1;
                store.update(key, new VersionedValue(newValue, newVersion));
            }
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);

        } finally {
            partition.commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        txn.meta.clear();
    }

    private PartitionState getPartitionForTxn(Transaction txn, String key) {
        Integer partitionId = (Integer) txn.meta.get(META_PARTITION_ID);
        if (partitionId == null) {
            partitionId = getWarehouseIdFromKey(key);
            if (partitionId != null) {
                txn.meta.put(META_PARTITION_ID, partitionId);
            }
        }
        return getPartitionById(partitionId);
    }
    
    private PartitionState getPartitionById(Integer partitionId) {
        if (partitionId == null) return null;
        return partitions.computeIfAbsent(partitionId, k -> new PartitionState());
    }

    private static Integer getWarehouseIdFromKey(String key) {
        String[] parts = key.split("_");
        if (parts.length > 1) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static KeyType getKeyType(String key) {
        if (key.startsWith("d_") && key.endsWith("_next_oid")) return KeyType.HOTSPOT;
        if (key.startsWith("s_") && key.endsWith("_qty")) return KeyType.IMMEDIATE;
        if (key.startsWith("o_")) return KeyType.IMMEDIATE;
        if (key.endsWith("_ytd") || key.endsWith("_bal")) return KeyType.DEFERRED;
        if (key.startsWith("item_")) return KeyType.READ_ONLY;
        return KeyType.UNKNOWN;
    }
}
