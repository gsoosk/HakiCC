/* Technical Report:
### Final Report: Lock-Protected Optimistic Concurrency Control (LP-OCC)

#### 1. Algorithm Overview
This protocol is a robust implementation of Optimistic Concurrency Control (OCC) that prioritizes correctness and serializability by eliminating race conditions found in more complex, lock-free designs. The core principle is "read-validate-write," but with the critical addition of locking to protect the atomicity of both the read and commit phases. Transactions execute optimistically, buffering their reads and writes in a private workspace. At commit time, a validation phase ensures that no data read by the transaction has been modified by other concurrently committing transactions. The protocol's main innovation lies in its use of short-term locks during the read phase to guarantee a consistent snapshot, thereby preventing subtle race conditions that can undermine the validation logic.

#### 2. Read/Write/Lock and Validation Strategy
*   **Read Operation:** To ensure a transaction reads a consistent snapshot of a value and its associated version number, a short-term lock is acquired on the key being read. While holding the lock, the value is read from the underlying store, and its version is read from the protocol's version manager. Both are stored in the transaction's private workspace. The lock is released immediately afterward. This lock-protected read prevents reader/writer race conditions where a transaction might observe a partially committed write from another transaction.
*   **Write Operation:** All writes are buffered in the transaction's private `writeSet`. A "write-implies-read" rule is enforced: if a transaction's first access to a key is a write, it must first perform a consistent, lock-protected read (as described above) to establish a version dependency for that key. This ensures all written keys are validated at commit time, preventing lost updates.
*   **Validation and Commit Strategy:** The commit phase follows a strict "lock-validate-write" sequence.
    1.  **Locking:** The transaction acquires locks for the union of all keys in its read and write sets. Locks are acquired in a canonical (lexicographical) order to prevent deadlocks.
    2.  **Validation:** While holding the locks, the transaction iterates through its read set, comparing the version number it recorded for each key against the current version in the global version manager. If any version has changed, the transaction aborts, and its changes are discarded.
    3.  **Writing:** If validation succeeds, the transaction applies all buffered writes to the data store and increments the version number for each modified key.
    4.  **Unlocking:** All locks are released, making the changes visible to other transactions.

#### 3. Deadlock Handling
The protocol primarily relies on **deadlock prevention**. During the commit phase, all required locks are acquired in a globally consistent canonical order (lexicographical by key name). This prevents the circular-wait condition necessary for a deadlock to occur among committing transactions. Since locks acquired during the read phase are short-lived and do not overlap with other long-term locks, they do not introduce a risk of deadlock.

#### 4. Correctness Guarantees
The protocol guarantees **Strict Conflict-Serializability**.
*   **Conflict-Serializability:** This is ensured by the atomic commit phase. By locking the entire read and write set before validation, the transaction guarantees that its snapshot is valid at the moment of validation and that no other transaction can interfere between validation and writing. This effectively establishes a single, total serialization point for all transactions at their commit time.
*   **Strictness:** The protocol is strict because writes are buffered and only applied to the database after a successful commit. This ensures that no transaction can read the uncommitted writes of another transaction, thus avoiding cascading aborts.

#### 5. Complexity and Performance Notes
The main performance trade-off of this protocol is sacrificing lock-free reads for guaranteed correctness. Acquiring a lock for every initial read introduces overhead and potential contention compared to purely optimistic or seqlock-based approaches. However, it completely eliminates a class of subtle race conditions that are difficult to debug and can lead to data corruption. The performance is sensitive to the duration of the commit phase, as locks are held during validation and writing. Its performance is best suited for workloads with low to moderate data contention, where the overhead of read-locking is minimal and the abort rate remains low. Under high contention, it may suffer from increased lock-waiting latency.

#### 6. Assumptions and Limitations
*   The protocol assumes that the overhead of acquiring a short-term lock during the read phase is acceptable. In environments with extremely low-latency read requirements, this could be a bottleneck.
*   It does not include any semantic optimizations (e.g., for commutative operations). All operations are treated uniformly, which simplifies the design but may miss opportunities for higher throughput in specific workloads like TPC-C.
*   The protocol is not deadlock-free by design but relies on a strict locking discipline for deadlock prevention, which is effective but requires careful implementation.Novelty Result:
```json
```json
{
  "novelty_score_overall": 20,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 5,
    "distance_from_known_families_30": 8,
    "implementation_distinctiveness_15": 5,
    "correctness_argument_originality_10": 1,
    "rebranding_penalty_minus15": -1
  },
  "separate_scores": {
    "proposal_novelty": 20,
    "code_novelty": 20
  },
  "overlap_analysis": [
    {
      "family": "OCC (Silo variant)",
      "overlap_reasoning": "This is the closest family. The commit-time logic of locking the write-set (extended here to the read-set as well), validating the read-set, and then writing is the core of locking-based OCC protocols like Silo. The protocol is essentially a slightly more pessimistic version of this.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "Strict 2PL (S2PL)",
      "overlap_reasoning": "The protocol shares the use of locks to create critical sections and canonical ordering to prevent deadlocks. However, it is not 2PL because locks are not held for the entire transaction duration (they are acquired late, at commit time), and read locks are very short-lived.",
      "overlap_strength_0_to_100": 40
    },
    {
      "family": "MVCC-based OCC (e.g., classic PostgreSQL SSI)",
      "overlap_reasoning": "The protocol uses version numbers (analogous to transaction IDs) to validate a transaction's snapshot, which is a core concept in MVCC-based OCC. However, it is a single-version system and uses locking instead of creating multiple data versions.",
      "overlap_strength_0_to_100": 30
    },
    {
      "family": "Semantic Concurrency Control",
      "overlap_reasoning": "The implementation has completely abandoned all semantic optimizations (like handling commutative operations or blind reads) in favor of a uniform, general-purpose mechanism. There is very little overlap with this family.",
      "overlap_strength_0_to_100": 5
    }
  ],
  "feature_diff": [
    {
      "axis": "Read Operation Strategy",
      "ours": "Reads are protected by a short-term lock to ensure an atomic read of a value and its version. This is a pessimistic step within an otherwise optimistic protocol.",
      "typical_in_nearest_family": "Reads are typically lock-free. Consistency is often ensured with a seqlock-like mechanism (read version, read value, re-read version) or by relying on atomic memory operations (e.g., 64-bit atomic reads of a version+pointer).",
      "difference_note": "The choice to use an actual mutex for reads is a significant deviation from typical high-performance OCC implementations. It prioritizes simplicity and correctness over minimizing read-path overhead, making it more conservative than its nearest neighbors."
    },
    {
      "axis": "Commit-Time Locking Scope",
      "ours": "Locks are acquired for the union of the read-set and write-set.",
      "typical_in_nearest_family": "It is more common to lock only the write-set (e.g., Silo). Locking the read-set as well is a more pessimistic choice that provides stronger protection against write-skew at the cost of increased blocking and potential for lower throughput.",
      "difference_note": "This is a known, more conservative variant within the OCC family, often used to achieve strict serializability more easily than more complex dependency-tracking mechanisms."
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
      "The 'write-implies-read' rule is explicitly implemented, forcing a version dependency for write-only keys."
    ]
  },
  "correctness_rationale_summary": "The correctness argument is standard for locking-based OCC. Serializability is guaranteed by creating an atomic critical section at commit time (via locking the read/write set) during which validation and writes occur. The short-term lock on reads ensures that the information used for validation is consistent in the first place. The argument is sound but follows established patterns for proving OCC protocols correct.",
  "risks_and_edge_cases": [
    "The primary risk is performance, not correctness. The short-term lock on reads could become a bottleneck under high read contention for a single key.",
    "The protocol has no mechanism for handling long-running read-only transactions efficiently. Such a transaction would hold its read-set versions for a long time and have a high probability of aborting, a classic weakness of OCC."
  ],
  "suggestions_to_increase_novelty": [
    "Reintroduce semantic awareness in a safe way. For example, allow commutative updates to bypass the pessimistic read-lock, but still require them to be validated at commit time. This would recover some performance without sacrificing correctness.",
    "Explore a finer-grained locking strategy. Instead of locking the entire read-set at commit, only lock read-set keys that are known to be part of common high-contention read-write patterns (like write-skew), which could be determined from the workload.",
    "Combine the lock-protected read with a version-based fallback. A transaction could first try a lock-free seqlock read, and only if it fails multiple times (indicating high contention) would it fall back to taking a short-term lock. This would make the read path adaptive."
  ],
  "confidence_0_to_1": 0.95
}
```
```
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 10,
    "verdict": "Derivative",
    "score_breakdown": {
        "conceptual_originality_40": 18,
        "distance_from_known_families_30": 10,
        "implementation_distinctiveness_15": 0,
        "correctness_argument_originality_10": 1,
        "rebranding_penalty_minus15": 0
    },
    "separate_scores": {
        "proposal_novelty": 45,
        "code_novelty": 0
    },
    "overlap_analysis": [
        {
            "family": "OCC (Locking-Based)",
            "overlap_reasoning": "The *implementation* is a textbook example of locking-based OCC, following the lock-validate-write-unlock pattern. The proposal's GENERAL path is also explicitly defined as OCC. The overlap for the implemented code is 100%.",
            "overlap_strength_0_to_100": 100
        },
        {
            "family": "Escrow Locking / Commutative Updates",
            "overlap_reasoning": "The *proposal's* COMMUTATIVE_ONLY path is a classic escrow mechanism. It defers delta application until commit time, where it re-reads the base value and applies the change atomically. This is a well-known technique for handling hotspots on counters.",
            "overlap_strength_0_to_100": 85
        },
        {
            "family": "Hybrid CC (Multi-Path Execution)",
            "overlap_reasoning": "The *proposal's* core architectural idea of classifying transactions and routing them to different CC mechanisms is a known pattern. Examples include special handling for read-only transactions or using different protocols for short vs. long transactions.",
            "overlap_strength_0_to_100": 70
        }
    ],
    "feature_diff": [
        {
            "axis": "Core Mechanism",
            "ours": "Proposal: Dual, parallel CC mechanisms based on static whole-transaction classification (Commutative-Escrow vs. OCC). Implementation: A single, uniform locking-based OCC mechanism for all transactions.",
            "typical_in_nearest_family": "A single OCC mechanism for all transactions.",
            "difference_note": "The proposal's core idea is entirely absent from the implementation. The code does not have dual paths or transaction classification, making it operationally identical to a standard OCC protocol."
        },
        {
            "axis": "Commit Rule",
            "ours": "Proposal: COMMUTATIVE txns use a lock-reread-apply_delta-write rule (abort-free). GENERAL txns use a lock-validate-write rule. Implementation: All txns use the lock-validate-write rule.",
            "typical_in_nearest_family": "Lock-validate-write is the canonical commit rule for locking-based OCC.",
            "difference_note": "The proposed novel, abort-free commit path for commutative transactions was not implemented. The code uses a standard, single commit logic."
        },
        {
            "axis": "Metadata",
            "ours": "Proposal: Per-transaction `tx_class`. Per-key `version` and `lock`. Implementation: Per-key `version` and `lock`. No `tx_class` metadata exists.",
            "typical_in_nearest_family": "Per-key versions and locks are standard metadata for locking-based OCC.",
            "difference_note": "The key piece of metadata (`tx_class`) required to enable the proposed protocol is missing in the implementation, confirming it does not follow the proposal."
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
            "Canonical key sorting for deadlock avoidance",
            "Brief lock acquisition during read phase"
        ]
    },
    "correctness_rationale_summary": "The proposal argues for correctness by combining two serializable paths, a standard approach for hybrid systems. The implemented code, however, ensures serializability via a single, textbook locking-based OCC protocol. It creates a critical section during commit by locking the entire read/write set, validating read versions, and then writing. This is a classic, well-understood, and non-novel correctness mechanism.",
    "risks_and_edge_cases": [
        "The primary issue is the complete disconnect between the proposed novel protocol (TCDC) and the implemented classic protocol (locking-OCC). The novelty exists only on paper.",
        "The implementation's `performConsistentRead` locks on every first read, which deviates from pure OCC and can reduce concurrency by introducing blocking in the read phase.",
        "The proposal's 'blind read exemption' is a subtle optimization that, if misapplied via incorrect static analysis, could easily violate serializability."
    ],
    "suggestions_to_increase_novelty": [
        "First and foremost, actually implement the TCDC protocol as described in the proposal. The code must reflect the core ideas of transaction classification and dual execution paths.",
        "Generalize the classification beyond a static, binary choice. Explore dynamic classification based on the operations a transaction performs, allowing for more flexible routing.",
        "Instead of a simple re-read and apply-delta, the commutative path could use more advanced CRDT-like operations or non-blocking techniques to further reduce contention at commit time.",
        "Explore the interaction of the commutative path with a more advanced general-purpose protocol like SSI or MVCC instead of basic OCC."
    ],
    "confidence_0_to_1": 1.0
}
```
*/


package tests_java;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implements a robust Optimistic Concurrency Control (OCC) protocol.
 *
 * This version corrects fundamental serializability bugs by simplifying the design
 * to a classic OCC implementation with lock-protected reads. Previous attempts
 * with complex, lock-free seqlocks and transaction classifications introduced
 * subtle race conditions leading to widespread failures.
 *
 * Key principles of this corrected implementation:
 * 1.  **No Transaction Specialization:** All transactions are treated uniformly.
 *     The complex and buggy commutative/delta logic has been removed.
 * 2.  **Lock-Protected Reads:** To guarantee that a transaction reads a
 *     consistent snapshot of a value and its version, `performConsistentRead`
 *     briefly acquires a lock on the key. This serializes the read against any
 *     concurrent commit writing to the same key, eliminating reader/writer races.
 * 3.  **Standard OCC Commit Phase:** The commit logic follows the standard
 *     "lock-validate-write-unlock" pattern. It acquires locks for all keys in
 *     the read and write sets to create a critical section, validates read
 *     versions, applies writes, and releases the locks.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final Object NULL_SENTINEL = new Object();
    private final ConcurrentHashMap<String, ReentrantLock> lockManager = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> versionManager = new ConcurrentHashMap<>();
    private final AtomicInteger transactionIdCounter = new AtomicInteger(0);
    private final AtomicInteger globalTimestamp = new AtomicInteger(0);

    private static final String VERSIONED_READ_SET_KEY = "versioned_read_set";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = transactionIdCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, globalTimestamp.get());
        txn.meta.put(VERSIONED_READ_SET_KEY, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @SuppressWarnings("unchecked")
    private Object performConsistentRead(Transaction txn, String key, KVStore store) {
        Map<String, Integer> versionedReadSet = (Map<String, Integer>) txn.meta.get(VERSIONED_READ_SET_KEY);
        ReentrantLock lock = lockManager.computeIfAbsent(key, k -> new ReentrantLock());
        
        lock.lock();
        try {
            Object value = store.get(key);
            int version = versionManager.computeIfAbsent(key, k -> new AtomicInteger(0)).get();

            Object snapshotValue = (value instanceof AtomicLong) ? ((AtomicLong) value).get() : value;
            txn.readSet.put(key, (snapshotValue == null) ? NULL_SENTINEL : snapshotValue);
            versionedReadSet.put(key, version);
            return snapshotValue;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        if (txn.readSet.containsKey(key)) {
            Object cachedValue = txn.readSet.get(key);
            return (cachedValue == NULL_SENTINEL) ? null : cachedValue;
        }
        return performConsistentRead(txn, key, store);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // "Write implies read": Ensure a version dependency exists before any write.
        Map<String, Integer> versionedReadSet = (Map<String, Integer>) txn.meta.get(VERSIONED_READ_SET_KEY);
        if (!versionedReadSet.containsKey(key)) {
            performConsistentRead(txn, key, store);
        }
        txn.writeSet.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Map<String, Integer> versionedReadSet = (Map<String, Integer>) txn.meta.get(VERSIONED_READ_SET_KEY);
        List<String> keysToLock = Stream.concat(versionedReadSet.keySet().stream(), txn.writeSet.keySet().stream())
                .distinct().sorted().collect(Collectors.toList());

        if (keysToLock.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(globalTimestamp.incrementAndGet());
        }

        List<ReentrantLock> heldLocks = new ArrayList<>();
        try {
            // Phase 1: Lock all keys in canonical order
            for (String key : keysToLock) {
                ReentrantLock lock = lockManager.computeIfAbsent(key, k -> new ReentrantLock());
                lock.lock();
                heldLocks.add(lock);
            }

            // Phase 2: Validate the read set
            for (Map.Entry<String, Integer> entry : versionedReadSet.entrySet()) {
                int readVersion = entry.getValue();
                int currentVersion = versionManager.computeIfAbsent(entry.getKey(), k -> new AtomicInteger(0)).get();
                if (readVersion != currentVersion) {
                    return CommitResult.failure("Validation failed for key: " + entry.getKey());
                }
            }

            // Phase 3: Write to the store
            int commitTs = globalTimestamp.incrementAndGet();
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                store.update(entry.getKey(), entry.getValue());
                versionManager.computeIfAbsent(entry.getKey(), k -> new AtomicInteger(0)).incrementAndGet();
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);
        } finally {
            // Phase 4: Release all locks
            for (ReentrantLock lock : heldLocks) {
                lock.unlock();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        ((Map<?, ?>) txn.meta.get(VERSIONED_READ_SET_KEY)).clear();
    }
}
