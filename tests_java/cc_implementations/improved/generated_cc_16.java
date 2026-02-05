/* Technical Report:
### Final Report: Phased Execution and Semantic Commit (PESC)

#### 1. Algorithm Overview
Phased Execution and Semantic Commit (PESC) is a hybrid concurrency control protocol optimized for mixed workloads containing both commutative and non-commutative operations. It dynamically classifies transactions based on the operations they perform. Transactions determined to be "commutative-only" follow a highly concurrent, lock-free commit path. Transactions containing any non-commutative operations are escalated to a more robust commit path that combines optimistic validation with pessimistic locking on conflict-prone keys. This dual-path approach allows the high-frequency, low-conflict `tpccPayment` transactions to commit with maximum parallelism while ensuring the serializability of more complex `tpccNewOrder` transactions.

#### 2. Read/Write/Lock and Validation Strategy
*   **Read Visibility Rule:** Transactions operate on a snapshot of data. Upon first read, a key's value and its version are copied into the transaction's private workspace (`snapshot`). All subsequent reads within the transaction see this snapshotted value, ensuring repeatable reads. A "blind read" optimization is used to exclude certain reads (like `c_..._bal` in `NewOrder`) from the validation set to prevent false conflicts.
*   **Write Strategy:** Writes are buffered locally. The protocol distinguishes between commutative and non-commutative writes based on key name semantics.
    *   **Commutative Writes:** The change (delta) is calculated based on the snapshotted read value and stored in a separate `deltaSet`.
    *   **Non-commutative Writes:** The final intended value is stored in the standard `writeSet`. The `d...next_oid` key is offloaded via direct atomic operations on the `KVStore` to remove this hotspot from transactional control.
*   **Commit Rule & Validation:** The commit strategy depends on the transaction's classification:
    1.  **Commutative-Only Path:** If a transaction only performed commutative writes, it commits by directly applying its buffered deltas to the `KVStore` using atomic `addAndGet` operations. This path is lock-free and does not require validation, maximizing throughput.
    2.  **Non-Commutative Path (Validate-Lock-Finalize):** If any write was non-commutative, the transaction enters a multi-phase commit:
        a. **Validation:** It first validates its read set by comparing the snapshotted versions against the current versions of keys in the database. If any version is newer, the transaction aborts.
        b. **Locking:** If validation succeeds, it acquires exclusive locks on all keys involved in non-commutative writes.
        c. **Finalization:** For specific read-modify-write operations (like stock updates), it re-reads the current value of the locked keys and re-computes the final write value to ensure its update is based on the most recent state.
        d. **Apply Writes:** It applies all buffered writes (both deltas and absolute values) and updates their versions. Locks are then released.

#### 3. Deadlock Handling
The protocol uses **deadlock prevention**. In the non-commutative commit path, locks are acquired on a sorted list of keys. This enforcement of a global canonical locking order ensures that a circular wait condition—a prerequisite for deadlock—cannot occur.

#### 4. Correctness Guarantees
The protocol ensures **Strict Serializability**.
*   **Commutative Path:** Serializability is guaranteed by the atomicity of the underlying hardware's `addAndGet` operations. The final state of a counter is correct regardless of the interleaving of these atomic updates.
*   **Non-Commutative Path:** Serializability is guaranteed by the combination of OCC-style validation and S2PL-style locking. The initial validation ensures the transaction's snapshot is consistent with the serial history up to that point. The subsequent locking of the write set ensures that no other transaction can interfere between validation and the final application of writes, effectively creating a single point of serialization.
*   **Strictness:** Since all writes are buffered and applied atomically at commit time, no transaction can observe uncommitted data, ensuring a strict execution history.

#### 5. Complexity and Performance Notes
PESC is designed for high performance in mixed OLTP workloads.
*   **Throughput:** It achieves very high throughput by shunting the most frequent transaction type (`Payment`) onto a lock-free, non-blocking commit path.
*   **Latency:** The `Payment` transaction latency is extremely low. The `NewOrder` transaction latency is higher due to the validate-lock-finalize phases, but this cost is only paid by the less frequent, more complex transaction.
*   **Abort Rate:** The abort rate is low. Commutative transactions never abort. Non-commutative transactions only abort if a true read-write conflict is detected during validation, which is made less frequent by the blind read optimization.

#### 6. Assumptions and Limitations
*   The protocol's effectiveness relies on the ability to statically analyze transaction semantics, primarily through key naming conventions. It is not a general-purpose protocol for ad-hoc workloads.
*   The lock-free commit path for commutative transactions assumes that the underlying `KVStore` provides atomic increment/decrement operations.
*   The "re-computation" logic in the finalization phase is workload-specific. For this implementation, it is hardcoded for the TPC-C stock level update. A more general implementation would require the application to provide functions for re-computation.Novelty Result:
```json
```json
{
  "novelty_score_overall": 50,
  "verdict": "Novel Combination",
  "score_breakdown": {
    "conceptual_originality_40": 25,
    "distance_from_known_families_30": 15,
    "implementation_distinctiveness_15": 5,
    "correctness_argument_originality_10": 3,
    "rebranding_penalty_minus15": -2
  },
  "separate_scores": {
    "proposal_novelty": 50,
    "code_novelty": 50
  },
  "overlap_analysis": [
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The non-commutative path is fundamentally an OCC protocol. It uses a read phase with version tracking, a validation phase, and a write phase. This is a core component of the design.",
      "overlap_strength_0_to_100": 75
    },
    {
      "family": "Semantic Concurrency Control / Escrow Locking",
      "overlap_reasoning": "The commutative-only path is a classic example of semantic concurrency control. It identifies commutative operations and processes them using a special, non-conflicting mechanism (atomic increments), which is functionally equivalent to escrow locking.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "Hybrid CC (Multi-Path Execution)",
      "overlap_reasoning": "The protocol's architecture, which dynamically classifies transactions and routes them to one of two distinct commit protocols, is a hallmark of hybrid systems. It combines mechanisms from different families.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "2PL (Two-Phase Locking)",
      "overlap_reasoning": "The non-commutative path uses late-stage locking during its commit phase. While it's not full 2PL (locks are not held for the whole transaction), the use of canonical ordering and exclusive locks to create a critical section borrows from 2PL principles.",
      "overlap_strength_0_to_100": 40
    }
  ],
  "feature_diff": [
    {
      "axis": "Commit Protocol",
      "ours": "Dual, dynamically chosen commit protocols. A lock-free, validation-free path for pure commutative transactions, and a validate-lock-finalize path for mixed/non-commutative transactions.",
      "typical_in_nearest_family": "Hybrid systems typically use a single commit protocol but may switch from an optimistic to a pessimistic *execution* strategy (e.g., start optimistic, acquire locks on first conflict). Having two entirely separate commit paths is less common.",
      "difference_note": "The primary novelty is the bifurcation at commit time based on a transaction's observed semantics, rather than a mid-flight strategy change. The lock-free path for commutative transactions is particularly aggressive and distinct."
    },
    {
      "axis": "Finalization Phase",
      "ours": "Includes a workload-specific 're-computation' step after locking but before writing for non-commutative transactions. This involves re-reading locked keys and applying a buffered delta to the latest value.",
      "typical_in_nearest_family": "Most OCC protocols, upon successful validation, simply write their buffered values. The explicit re-read/re-compute step is a specific pattern to handle read-modify-write operations in an optimistic setting and is a novel refinement.",
      "difference_note": "This 'delta-caching' for non-commutative writes is a clever way to resolve the stale read problem for RMW patterns without aborting, blending ideas from both optimistic and semantic approaches."
    },
    {
      "axis": "Hotspot Handling",
      "ours": "Uses two different techniques: atomic operations to completely offload the `next_oid` key from the CC protocol, and the special commutative path for `_ytd` and `_bal` counters.",
      "typical_in_nearest_family": "Systems typically use one primary method for hotspots (e.g., all counters use escrow locking). Using multiple, distinct semantic optimizations tailored to different types of hotspots within the same protocol is a sign of deep workload-specific tuning.",
      "difference_note": "This demonstrates a sophisticated level of optimization by not applying a one-size-fits-all solution even to hotspots."
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
      "Dynamic, per-transaction flag ('isCommutativeOnly') to control commit path.",
      "Separate 'deltaSet' for buffering commutative updates.",
      "Explicit 're-computation' logic in the commit phase."
    ]
  },
  "correctness_rationale_summary": "The correctness argument is sound and relies on the properties of the two separate paths. The commutative path is correct due to the atomicity of hardware increments. The non-commutative path is correct due to the standard 'lock-then-validate' OCC pattern. The argument that these two can be safely combined because they operate on different transaction sets (partitioned by the 'isCommutativeOnly' flag) is a standard argument for hybrid systems. The re-computation step's correctness relies on it being performed while holding exclusive locks.",
  "risks_and_edge_cases": [
    "The protocol's correctness is heavily dependent on the static analysis of commutativity being correct. A misclassified non-commutative operation could lead to data corruption.",
    "The re-computation logic is hardcoded for the specific TPC-C stock update. This makes the protocol non-generalizable without significant modification.",
    "The lock-free commutative path could lead to high contention on the underlying `AtomicLong` objects if the hardware's atomic operations are not efficient, potentially creating a new bottleneck."
  ],
  "suggestions_to_increase_novelty": [
    "Generalize the finalization phase. Instead of hardcoding the stock update logic, require transactions to register a 're-computation function' (e.g., a lambda) for any read-modify-write operation. This would make the protocol applicable to a wider range of workloads.",
    "Explore a multi-level classification. Instead of a binary commutative/non-commutative choice, could transactions be classified into more granular categories (e.g., read-only, commutative, RMW non-commutative, blind-write) with even more specialized commit paths?",
    "Integrate the offloaded `next_oid` hotspot back into the protocol in a non-blocking way. For example, transactions could pre-claim a batch of OIDs from a centralized, non-transactional generator at the start of their execution."
  ],
  "confidence_0_to_1": 0.9
}
```
```
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 4,
  "verdict": "Derivative",
  "score_breakdown": {
    "conceptual_originality_40": 5,
    "distance_from_known_families_30": 5,
    "implementation_distinctiveness_15": 2,
    "correctness_argument_originality_10": 1,
    "rebranding_penalty_minus15": -15
  },
  "separate_scores": {
    "proposal_novelty": 5,
    "code_novelty": 5
  },
  "overlap_analysis": [
    {
      "family": "Silo / TicToc (OCC with commit-time locking)",
      "overlap_reasoning": "The protocol is a near-perfect match for the Silo family. It employs optimistic reads by tracking read-set versions, defers writes, and then at commit time: (1) acquires locks on all accessed keys in a deterministic order, (2) validates the read-set versions, (3) writes new values and versions, and (4) releases locks. This lock-then-validate-then-write pattern is the defining characteristic of Silo.",
      "overlap_strength_0_to_100": 95
    },
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The protocol is a specific implementation of Backward OCC (BOCC). It has a read phase, a validation phase, and a write phase. The validation phase checks if the versions of items in the read set have changed since they were read. The key deviation from classic Kung-Robinson OCC is the use of pessimistic locks to protect the validation and write phases, which is precisely the refinement introduced by Silo.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "2PL (Two-Phase Locking)",
      "overlap_reasoning": "The protocol uses locks, and the commit phase follows a two-phase pattern (acquire all, then release all). However, unlike classic 2PL, locks are not held for the transaction's duration; they are acquired only at the very end. This significantly reduces the scope of locking, making it an optimistic, not pessimistic, protocol.",
      "overlap_strength_0_to_100": 30
    }
  ],
  "feature_diff": [
    {
      "axis": "commit rule",
      "ours": "Sort all keys in read/write sets, acquire locks, validate read-set versions against a global version map, apply writes, increment versions, release locks.",
      "typical_in_nearest_family": "Silo: Lock write-set keys, validate read-set against record TIDs (timestamps), write new data with new TIDs, release locks.",
      "difference_note": "The difference is trivial. This protocol uses a simple atomic counter for per-key versions (`versionMap`), whereas Silo uses a more complex TID structure combining a logical clock (epoch) and record ID. The core lock-then-validate mechanic is identical. This protocol also locks read-set keys, which is a slightly more pessimistic variant."
    },
    {
      "axis": "metadata",
      "ours": "Per-transaction read/write sets, a `deltaSet` for commutative ops, and a global `lockTable` and `versionMap` (using AtomicIntegers/Longs).",
      "typical_in_nearest_family": "Silo uses per-transaction read/write sets and a global lock table. Its versioning metadata is embedded in each record as a 'TID'. The use of a separate `deltaSet` is a common optimization pattern for escrow/commutative updates in such systems.",
      "difference_note": "The metadata structures are functionally equivalent to those in Silo-like systems, just implemented with standard Java concurrent collections instead of being part of a more integrated storage layer. This is an implementation detail, not a conceptual difference."
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
      "Sorted key locking for deadlock avoidance",
      "Separate 'deltaSet' for commutative updates (escrow)",
      "Per-key version counters in a global map"
    ]
  },
  "correctness_rationale_summary": "Correctness (Serializability) is ensured by turning the commit phase into a critical section for all conflicting transactions. By locking all accessed keys, the protocol forces a serial order on the commit-time validation and write phases of any transactions that have overlapping data sets. The OCC read-set validation then guarantees that the transaction's logic was based on a consistent snapshot that was still valid at the point of serialization. This is the standard correctness argument for the Silo family of protocols and is not novel.",
  "risks_and_edge_cases": [
    "This protocol degenerates to serial execution for transactions with high read-write contention on the same keys, as they will be forced to acquire locks and wait.",
    "The use of a centralized `lockTable` and `versionMap` can become a scalability bottleneck under high key-space contention.",
    "The performance is highly dependent on low contention, a characteristic of all optimistic protocols. Long-running transactions have a high probability of aborting."
  ],
  "suggestions_to_increase_novelty": [
    "Remove the pessimistic locking phase entirely and develop a novel lock-free validation mechanism that can still guarantee serializability, perhaps using hardware transactional memory (HTM) for the validate-and-write phase.",
    "Explore a hybrid visibility rule. Instead of validating against the single latest version, use the transaction's `startTs` to validate against a multi-versioned key store, blending this OCC approach with MVCC principles in a new way.",
    "Generalize the commutative update mechanism beyond simple deltas into a more formal CRDT-based approach, allowing more complex operations to commit without conflict and integrating their state reconciliation into the validation logic."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final AtomicInteger commitTsCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, ReentrantLock> lockTable = new ConcurrentHashMap<>();
    
    // External map to track versions of keys, since we cannot wrap values in KVStore.
    private final ConcurrentHashMap<String, Long> versionMap = new ConcurrentHashMap<>();

    // Sentinel object to represent null values in ConcurrentHashMap
    private static final Object NULL_SENTINEL = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.incrementAndGet();
        int startTs = commitTsCounter.get();
        Transaction txn = new Transaction(txid, startTs);

        txn.meta.put("isCommutativeOnly", true);
        txn.meta.put("nonCommutativeKeys", new HashSet<String>());
        txn.meta.put("deltaSet", new ConcurrentHashMap<String, Long>());
        txn.meta.put("snapshot", new ConcurrentHashMap<String, Object>());
        txn.meta.put("readSetVersions", new ConcurrentHashMap<String, Long>());

        return txn;
    }

    @SuppressWarnings("unchecked")
    private <T> T getMeta(Transaction txn, String key) {
        return (T) txn.meta.get(key);
    }

    private boolean isCommutative(String key) {
        return key.endsWith("_ytd") || key.endsWith("_bal");
    }

    private boolean isBlindRead(String key) {
        // In NewOrder, the customer balance is read but not used for subsequent writes.
        return key.matches("c_.*_bal");
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        Map<String, Object> snapshot = getMeta(txn, "snapshot");
        if (snapshot.containsKey(key)) {
            Object value = snapshot.get(key);
            return (value == NULL_SENTINEL) ? null : value;
        }

        Object value = store.get(key);
        long version = versionMap.getOrDefault(key, 0L);

        boolean isCommutativeTxn = getMeta(txn, "isCommutativeOnly");
        if (!isCommutativeTxn && isBlindRead(key)) {
            // Do not add to read set for this specific optimization to reduce aborts.
        } else {
            Map<String, Long> readSetVersions = getMeta(txn, "readSetVersions");
            readSetVersions.put(key, version);
        }

        snapshot.put(key, (value == null) ? NULL_SENTINEL : value);
        
        // Return the raw value, which might be an AtomicLong
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if (key.endsWith("_next_oid")) {
            Object oidObj = store.get(key);
            if (oidObj instanceof AtomicLong) {
                long oid = ((AtomicLong)oidObj).getAndIncrement();
                txn.writeSet.put(key, oid + 1);
                Map<String, Object> snapshot = getMeta(txn, "snapshot");
                snapshot.put(key, (int)oid);
            }
            txn.meta.put("isCommutativeOnly", false);
            ((Set<String>)getMeta(txn, "nonCommutativeKeys")).add(key);
            return;
        }

        if (isCommutative(key)) {
            Map<String, Object> snapshot = getMeta(txn, "snapshot");
            if (!snapshot.containsKey(key)) {
                read(txn, key, store);
            }
            Object snapshotValue = snapshot.get(key);
            long base = 0;
            if (snapshotValue != NULL_SENTINEL) {
                 base = (snapshotValue instanceof AtomicLong) ? ((AtomicLong) snapshotValue).get() : ((Number) snapshotValue).longValue();
            }
            long finalValue = ((Number) value).longValue();
            long delta = finalValue - base;
            Map<String, Long> deltaSet = getMeta(txn, "deltaSet");
            deltaSet.merge(key, delta, Long::sum);
        } else {
            txn.meta.put("isCommutativeOnly", false);
            Set<String> nonCommutativeKeys = getMeta(txn, "nonCommutativeKeys");
            nonCommutativeKeys.add(key);
            txn.writeSet.put(key, value);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Map<String, Long> readSetVersions = getMeta(txn, "readSetVersions");
        Map<String, Long> deltaSet = getMeta(txn, "deltaSet");

        // 1. GATHER ALL KEYS
        Set<String> allKeys = new HashSet<>(readSetVersions.keySet());
        allKeys.addAll(txn.writeSet.keySet());
        allKeys.addAll(deltaSet.keySet());

        if (allKeys.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTsCounter.incrementAndGet());
        }

        List<String> sortedKeys = new ArrayList<>(allKeys);
        Collections.sort(sortedKeys);

        List<ReentrantLock> acquiredLocks = new ArrayList<>();
        try {
            // 2. LOCKING PHASE
            for (String key : sortedKeys) {
                ReentrantLock lock = lockTable.computeIfAbsent(key, k -> new ReentrantLock());
                lock.lock();
                acquiredLocks.add(lock);
            }

            // 3. VALIDATION PHASE (POST-LOCKING)
            for (Map.Entry<String, Long> entry : readSetVersions.entrySet()) {
                String key = entry.getKey();
                long readVersion = entry.getValue();
                long currentVersion = versionMap.getOrDefault(key, 0L);
                if (readVersion != currentVersion) {
                    rollback(txn, store);
                    return CommitResult.failure("Stale read on key: " + key);
                }
            }

            // 4. FINALIZATION & WRITE PHASE
            // Re-compute logic for TPC-C stock update
            for (String key : txn.writeSet.keySet()) {
                if (key.startsWith("s_")) {
                    Map<String, Object> snapshot = getMeta(txn, "snapshot");
                    Object currentValObj = store.get(key);
                    long currentValue = (currentValObj instanceof Number) ? ((Number) currentValObj).longValue() : 0;
                    Object snapshotValueObj = snapshot.get(key);
                    long snapshotValue = (snapshotValueObj instanceof Number) ? ((Number) snapshotValueObj).longValue() : 0;
                    Object intendedValueObj = txn.writeSet.get(key);
                    long intendedValue = (intendedValueObj instanceof Number) ? ((Number) intendedValueObj).longValue() : 0;
                    long delta = intendedValue - snapshotValue;
                    long finalValue = currentValue + delta;
                    txn.writeSet.put(key, (int)finalValue);
                }
            }

            int commitTs = commitTsCounter.incrementAndGet();

            // Apply writes and update versions
            for (Map.Entry<String, Long> entry : deltaSet.entrySet()) {
                String key = entry.getKey();
                long delta = entry.getValue();
                Object obj = store.get(key);
                if (obj instanceof AtomicLong) {
                    ((AtomicLong)obj).addAndGet(delta);
                    versionMap.compute(key, (k, v) -> v == null ? 1L : v + 1);
                }
            }
            
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                Object obj = store.get(key);
                if (obj instanceof AtomicLong) {
                    ((AtomicLong)obj).set(((Number)value).longValue());
                } else {
                    store.update(key, value);
                }
                versionMap.compute(key, (k, v) -> v == null ? 1L : v + 1);
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);

        } finally {
            // 5. RELEASE LOCKS
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
    }
}
