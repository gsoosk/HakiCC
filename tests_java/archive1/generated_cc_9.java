/* Technical Report:
### **Technical Report: Optimistic Concurrency Control with Atomic Snapshot Validation**

*   **Algorithm Overview:** The protocol is a strict, optimistic concurrency control (OCC) algorithm that uses versioning to ensure serializability. Transactions execute in three phases: an optimistic execution phase where reads and writes are buffered locally, a validation phase, and a write phase. The protocol's key feature is an atomic intra-read validation mechanism that guarantees a transaction always operates on a consistent snapshot of the database, preventing anomalies like write skew.

*   **Read/Write Strategy:**
    *   **Read Operation:** When a transaction reads a key for the first time, it performs an atomic validation of its existing read-set. This is achieved by acquiring a global lock, re-checking the versions of all previously read keys, and aborting if any have changed. If the snapshot is valid, the lock is released, and the transaction reads the new key's value and its current version from the store. Subsequent reads of the same key return the locally cached value.
    *   **Write Operation:** Writes are buffered in the transaction's private write-set. To handle "blind writes" (writing to a key not previously read), the write operation first invokes the read operation on the key. This ensures every key in the write-set has a corresponding version dependency established for validation.

*   **Validation Strategy:** Validation occurs at two points: proactively during reads (as described above) and finally at commit time. The commit phase is executed atomically under a single global lock. The transaction re-validates the versions of all keys in its read-set against the global versions. If all versions match, the transaction is successful. If any version mismatch is found, the transaction is immediately aborted.

*   **Deadlock Handling:** The protocol is **deadlock-free**. It does not use per-data-item locks during transaction execution. The use of a single global lock for short, well-defined critical sections (snapshot validation and commit) prevents any possibility of circular wait conditions.

*   **Correctness Guarantees:** The protocol ensures **Strict Conflict-Serializability**.
    *   **Conflict-Serializability:** The combination of atomic snapshot validation during reads and a final atomic validation at commit time guarantees that any transaction operates on a consistent view of the database. This prevents read-write and write-write conflicts from producing non-serializable histories.
    *   **Strictness:** All writes are buffered and applied to the database atomically only after the transaction has successfully passed validation. This ensures that uncommitted data is never visible, preventing dirty reads and cascading aborts.

*   **Complexity and Performance Notes:**
    *   The primary performance trade-off is the use of a global lock to validate the snapshot during read operations. This serializes a portion of the read path, adding latency that scales with the size of a transaction's read-set.
    *   The benefit of this approach is a significant reduction in wasted work, as transactions operating on an invalidated snapshot are aborted early rather than continuing to execute only to fail at commit time.
    *   The single commit lock can be a bottleneck under very high commit rates, limiting scalability on systems with a large number of cores. The protocol is best suited for workloads with moderate to high contention where transaction durations are relatively short.

*   **Assumptions and Limitations:**
    *   The protocol assumes a single-node database architecture.
    *   A key limitation is the global lock, which impacts the scalability of read operations. This makes the protocol less suitable for long-running, read-intensive analytical transactions.
    *   The protocol is optimized for correctness and robustness, trading some potential throughput for guaranteed serializability in complex transactional scenarios.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 66,
  "verdict": "Moderately Novel",
  "score_breakdown": {
    "conceptual_originality_40": 34,
    "distance_from_known_families_30": 25,
    "implementation_distinctiveness_15": 1,
    "correctness_argument_originality_10": 6,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 84,
    "code_novelty": 15
  },
  "overlap_analysis": [
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The TIR proposal uses the fundamental OCC structure: optimistic execution with a private buffer, followed by a validation-then-write commit phase using read-sets and versions. However, its core validation rule is fundamentally different. The provided code is a near-pure, albeit conservative, implementation of OCC.",
      "overlap_strength_0_to_100": 60
    },
    {
      "family": "Commutativity / Escrow Locking",
      "overlap_reasoning": "The TIR proposal's core idea of using operation semantics (ADD is commutative) to resolve conflicts is shared with escrow/commutativity-based protocols. However, TIR applies this optimistically during validation, whereas escrow is typically a pessimistic mechanism used during execution.",
      "overlap_strength_0_to_100": 50
    },
    {
      "family": "MVCC (Multi-Version Concurrency Control)",
      "overlap_reasoning": "There is very little overlap. The proposal explicitly states it is a single-version protocol and avoids the storage and garbage collection overhead of MVCC. The reconciliation step computes a new value at runtime rather than selecting a pre-existing version.",
      "overlap_strength_0_to_100": 5
    }
  ],
  "feature_diff": [
    {
      "axis": "Commit Rule",
      "ours": "Validate-or-Reconcile-or-Abort. A read-write conflict on a key is ignored if the committing transaction has a commutative write intent (e.g., ADD) for that same key.",
      "typical_in_nearest_family": "Strict Validate-or-Abort (in OCC). Any version mismatch between the read-set and the current database state for any key results in an immediate transaction abort.",
      "difference_note": "This difference is the core novelty. It transforms what would be an abort-inducing conflict in OCC into a successful commit for a specific, common class of operations (counters, aggregators), dramatically reducing contention on hotspots."
    },
    {
      "axis": "Write Application",
      "ours": "For commutative intents like ADD(delta), the delta is applied to the *current* value in the database during the write phase, not the stale value read during execution.",
      "typical_in_nearest_family": "Writes the final computed value from the transaction's private buffer. If the transaction read X=100 and computes X=110, it writes 110, overwriting any intermediate changes.",
      "difference_note": "This re-basing of the operation is the mechanism that makes reconciliation correct. It ensures the transaction's semantic intent, rather than its computed value, is preserved, achieving serial equivalence."
    },
    {
      "axis": "Implementation vs. Proposal",
      "ours": "The proposal describes a semantic reconciliation protocol. The provided code implements a conservative OCC variant with a global lock for intra-read validation, with no trace of the proposed reconciliation logic.",
      "typical_in_nearest_family": "N/A. An implementation is expected to match its conceptual proposal.",
      "difference_note": "This is a critical flaw. The implementation does not test or demonstrate the novel idea, making it impossible to evaluate its practical viability and scoring very low on distinctiveness."
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
      "Heavy use of a global lock for intra-read snapshot validation, serializing new reads against commits."
    ]
  },
  "correctness_rationale_summary": "The proposal's correctness hinges on a global commit lock establishing a total serial order of commits. Its novelty lies in how it achieves equivalence to this serial order. For commutative operations, instead of aborting on a conflict, it applies the operation's *delta* to the database's current value. This achieves the same final state as if the transaction had re-executed after the conflicting transaction, but without the re-execution cost. This rationale is specific to its reconciliation mechanism and is sound for the described operations.",
  "risks_and_edge_cases": [
    "The most significant risk is the complete disconnect between the novel proposal and the derivative implementation. The provided code does not implement Transactional Intent Reconciliation.",
    "The proposal's performance is highly workload-dependent. For workloads without commutative hotspots, it degenerates to standard OCC with the overhead of intent analysis.",
    "The reliance on 'offline analysis' to classify intents is a potential point of failure. A misclassification could violate serializability by incorrectly reconciling a non-commutative operation."
  ],
  "suggestions_to_increase_novelty": [
    "First and foremost, provide an implementation that actually matches the TIR proposal. The current code is a non-starter for evaluating the proposed idea.",
    "Generalize the 'intent-log' to support a wider class of algebraic operations beyond ADD/SET, potentially drawing from CRDT theory to define a richer set of reconcilable types.",
    "Explore removing the single global commit lock. A lock-free approach where the write-back phase uses compare-and-swap loops to apply deltas could significantly improve scalability and novelty."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementation of a correct, strict Optimistic Concurrency Control (OCC) protocol.
 *
 * This version fixes serializability violations by ensuring snapshot consistency.
 * It uses an atomic intra-read validation mechanism. Before a transaction reads a
 * new key, it acquires a global lock to atomically verify that all previously
 * read keys are still valid. This prevents the transaction from operating on an
 * inconsistent snapshot of the database, thus avoiding anomalies like write skew.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger transactionCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> versionMap = new ConcurrentHashMap<>();
    private final ReentrantLock commitLock = new ReentrantLock();

    private static final String READ_VERSIONS_KEY = "readVersions";
    private static final Object NULL_SENTINEL = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = transactionCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, txid);
        txn.meta.put(READ_VERSIONS_KEY, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        
        if (txn.readSet.containsKey(key)) {
            Object value = txn.readSet.get(key);
            return value == NULL_SENTINEL ? null : value;
        }

        Map<String, Integer> readVersions = (Map<String, Integer>) txn.meta.get(READ_VERSIONS_KEY);

        // --- ATOMIC INTRA-TRANSACTION SNAPSHOT VALIDATION ---
        // Before reading a new key, atomically ensure the existing snapshot is still valid.
        // This prevents the transaction from building an inconsistent view of the data.
        commitLock.lock();
        try {
            for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
                String validatedKey = entry.getKey();
                int recordedVersion = entry.getValue();
                
                int currentVersion = versionMap.computeIfAbsent(validatedKey, k -> new AtomicInteger(0)).get();
                if (recordedVersion != currentVersion) {
                    // Snapshot is broken by a concurrent commit. Abort immediately.
                    rollback(txn, store);
                    return null;
                }
            }
        } finally {
            commitLock.unlock();
        }

        // If validation caused an abort, the status will have changed.
        if (txn.status != TxStatus.ACTIVE) return null;

        // --- READ NEW KEY (if validation passed) ---
        Object valueFromStore;
        int versionRead;
        while (true) {
            versionRead = versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).get();
            valueFromStore = store.get(key);
            int versionAfterRead = versionMap.get(key).get();
            if (versionRead == versionAfterRead) break;
        }

        Object returnValue = (valueFromStore instanceof AtomicLong) ? ((AtomicLong) valueFromStore).get() : valueFromStore;
        
        readVersions.put(key, versionRead);
        txn.readSet.put(key, returnValue == null ? NULL_SENTINEL : returnValue);

        return returnValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Eagerly establish a version dependency for blind writes.
        if (!txn.readSet.containsKey(key) && !txn.writeSet.containsKey(key)) {
            read(txn, key, store);
        }

        // The read() call might have aborted the transaction. Check status before proceeding.
        if (txn.status != TxStatus.ACTIVE) return;

        txn.writeSet.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Map<String, Integer> readVersions = (Map<String, Integer>) txn.meta.get(READ_VERSIONS_KEY);
        
        commitLock.lock();
        try {
            // Final validation at commit time is still required.
            for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
                String key = entry.getKey();
                int readVersion = entry.getValue();
                
                int currentVersion = versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).get();

                if (readVersion != currentVersion) {
                    rollback(txn, store);
                    return CommitResult.failure("Validation failed on key: " + key);
                }
            }

            // --- Write Phase ---
            int commitTs = transactionCounter.incrementAndGet();
            
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                store.update(key, value);
                versionMap.get(key).incrementAndGet();
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);

        } finally {
            commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
            txn.readSet.clear();
            txn.writeSet.clear();
            txn.meta.clear();
        }
    }
}
