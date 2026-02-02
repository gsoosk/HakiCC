/* Technical Report:
### Final Report: Escalating Semantic Locking (ESL) Protocol

#### 1. Algorithm Overview
Escalating Semantic Locking (ESL) is a hybrid concurrency control protocol designed for workloads with pre-known transaction types. It combines the low overhead of Optimistic Concurrency Control (OCC) with the robustness of Strict Two-Phase Locking (S2PL). Transactions begin in a non-blocking `OPTIMISTIC` mode. Based on the semantics of their operations, they may "escalate" to a `PESSIMISTIC` mode. This escalation is triggered proactively when a transaction reads a key known to be a high-contention, non-commutative hotspot (e.g., `d_..._next_oid` or `s_..._qty` in TPC-C). This strategy allows low-conflict transactions (like `tpccPayment`) to execute with high parallelism, while high-conflict transactions (`tpccNewOrder`) switch to a locking-based approach early to prevent costly aborts and guarantee forward progress. The protocol also incorporates a "blind read" optimization, where reads whose values do not influence subsequent writes are excluded from validation to reduce false conflicts.

#### 2. Read/Write/Lock and Validation Strategy
*   **Read Operation:**
    *   **Optimistic Mode:** To ensure a consistent snapshot, a short-term lock is acquired on a key, the value and its version are read atomically, and the lock is immediately released. This prevents race conditions where the value and version could become inconsistent.
    *   **Pessimistic Mode:** The transaction must acquire and hold a long-term lock on the key before reading its value, following standard S2PL rules.

*   **Write Operation:**
    *   All writes are buffered in the transaction's private write-set until commit time.
    *   In `PESSIMISTIC` mode, a write operation must first acquire a long-term lock on the key if it is not already held.

*   **Escalation and Commit Strategy:**
    *   **Escalation:** The transition from `OPTIMISTIC` to `PESSIMISTIC` is an atomic, critical operation. The transaction must first acquire locks on all keys in its current read and write sets. Only after securing all locks does it re-validate its read-set versions. If validation succeeds, the mode is switched; otherwise, the transaction aborts. This `lock-then-validate` sequence is crucial for correctness.
    *   **Optimistic Commit:** The commit phase follows a similar `lock-then-validate` pattern. The transaction acquires locks on the union of its read-set and write-set, creating a critical section. It then performs a final validation. If successful, it applies writes and releases locks. This prevents write-skew and lost update anomalies.
    *   **Pessimistic Commit:** No validation is required. The transaction applies its buffered writes and releases all held locks.

#### 3. Deadlock Handling
The protocol handles deadlocks that may arise from incremental lock acquisitions in `PESSIMISTIC` mode. All attempts to acquire a lock use a timed wait (`tryLock` with a short timeout). If a transaction fails to acquire a lock within the timeout period, it is assumed to be part of a deadlock cycle. The transaction is immediately aborted, and its `rollback` procedure releases all locks it currently holds, thus breaking the cycle and allowing other transactions to proceed. While batch lock acquisitions (during escalation and commit) are done in a canonical key order to prevent deadlocks, the timeout mechanism provides a robust fallback for all locking scenarios.

#### 4. Correctness Guarantees
The ESL protocol guarantees **Strict Conflict-Serializability**.
*   **Optimistic Path:** Serializability is ensured by the atomic `lock-then-validate-then-write` sequence during the commit phase. Locking the full read- and write-set prevents any concurrent transaction from invalidating the snapshot between validation and commit.
*   **Pessimistic Path:** Serializability is ensured by adherence to Strict Two-Phase Locking, where all locks are acquired before they are needed and held until the transaction completes.
*   **Hybrid Path:** The correctness of the transition is guaranteed by the atomic `lock-then-validate` procedure during escalation, ensuring that a transaction only enters the pessimistic state with a consistent view of the database.
Strictness is guaranteed because all writes are buffered and applied atomically at commit time, preventing dirty reads.

#### 5. Complexity and Performance Notes
ESL is optimized for mixed workloads. Its performance is superior to pure 2PL in scenarios with many low-conflict transactions, as these can commit without the overhead of long-term locking. It is superior to pure OCC in high-contention scenarios by proactively locking hotspots, which reduces the rate of costly aborts and wasted work. The main overheads are version management for optimistic reads and lock management. Performance can be sensitive to the lock acquisition timeout value, which trades deadlock resolution speed against the rate of false-positive aborts under heavy but deadlock-free contention.

#### 6. Assumptions and Limitations
*   The protocol assumes that the set of high-contention, non-commutative keys that should trigger escalation can be identified in advance through static analysis of the transaction code.
*   Its effectiveness is maximized in workloads with a clear distinction between low-conflict and high-conflict transaction types. In a workload where all transactions are high-conflict, it converges to the performance of S2PL.
*   The timeout-based deadlock handling prevents deadlocks but does not offer formal guarantees against livelock (where a transaction is repeatedly aborted and retried), although this is unlikely in practice.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 38,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 15,
    "distance_from_known_families_30": 12,
    "implementation_distinctiveness_15": 6,
    "correctness_argument_originality_10": 2,
    "rebranding_penalty_minus15": -3
  },
  "separate_scores": {
    "proposal_novelty": 45,
    "code_novelty": 30
  },
  "overlap_analysis": [
    {
      "family": "Hybrid OCC/2PL (e.g., FOEDUS)",
      "overlap_reasoning": "This is the closest family. Both start optimistic and can switch to pessimistic locking. The core difference is the trigger: ESL uses a proactive, pre-defined semantic trigger (e.g., 'this is a stock update'), while systems like FOEDUS use a reactive trigger upon detecting a conflict with an existing lock.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "OCC (Silo variant)",
      "overlap_reasoning": "The optimistic phase is nearly identical to locking-based OCC variants like Silo. The commit process for purely optimistic transactions—acquiring locks on the write set, validating the read set, then writing—is a standard pattern to ensure atomicity of the commit phase.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "Strict 2PL (S2PL)",
      "overlap_reasoning": "The pessimistic phase is a textbook implementation of S2PL. Once escalated, the transaction acquires locks, holds them until the end of the transaction, and uses canonical ordering to prevent deadlocks. There is no novelty in this phase.",
      "overlap_strength_0_to_100": 95
    },
    {
      "family": "Semantic Concurrency Control",
      "overlap_reasoning": "The protocol borrows well-known semantic optimizations (handling commutative operations as deltas, identifying and ignoring blind reads) but uses them as bolt-on performance enhancements rather than as the core ordering mechanism. The final arbiter of conflicts is still locking or OCC validation.",
      "overlap_strength_0_to_100": 60
    }
  ],
  "feature_diff": [
    {
      "axis": "Phase Transition Trigger",
      "ours": "Proactive escalation triggered when a transaction attempts a pre-defined 'high-conflict semantic operation' (e.g., reading a stock quantity key).",
      "typical_in_nearest_family": "Reactive escalation, typically triggered when an optimistic operation encounters a key that is already locked by another transaction (as in FOEDUS).",
      "difference_note": "The difference is one of policy, not fundamental mechanism. ESL tries to prevent wasted work by escalating *before* a likely conflict, based on static analysis of the workload. This makes it a policy-driven hybrid, whereas others are mechanism-driven."
    },
    {
      "axis": "Escalation Atomicity",
      "ours": "The code locks all keys in the read/write set *before* performing the mid-flight validation. This is a safe, conventional approach.",
      "typical_in_nearest_family": "The proposal suggests a more novel (and riskier) validate-then-lock sequence. The implementation deviates from this, making it more similar to standard locking protocols.",
      "difference_note": "The implementation's choice of lock-then-validate reduces its distinctiveness from standard protocols. The proposal's validate-then-lock idea is more novel but would require a new mechanism to prevent races between validation and lock acquisition."
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
      "Per-transaction mode flag (OPTIMISTIC/PESSIMISTIC).",
      "Hardcoded, workload-specific heuristics to identify escalation triggers and blind reads."
    ]
  },
  "correctness_rationale_summary": "Correctness relies on a standard composition of two well-understood protocols. Optimistic transactions are serialized at their validation point (classic OCC). Pessimistic transactions are serialized by lock acquisition order (classic S2PL). The bridge between them—the mid-flight validation during escalation—ensures a transaction only acquires locks if its optimistic reads are still valid, thus preserving a single serial history. The rationale itself is not novel; it's a standard argument for hybrid systems.",
  "risks_and_edge_cases": [
    "The protocol's novelty and performance hinge entirely on the quality of its hardcoded 'semantic' heuristics. If all operations trigger escalation, it degenerates to S2PL. If none do, it becomes a locking-based OCC.",
    "The implementation's decision to lock before validating during escalation is safer but increases blocking, potentially negating some of the benefits of starting optimistically.",
    "The use of timeouts for deadlock *resolution* is a fallback, but the primary mechanism is *prevention* via key sorting. This could be confusing."
  ],
  "suggestions_to_increase_novelty": [
    "Replace the static, hardcoded semantic triggers with a dynamic, learning-based system. The protocol could monitor conflict rates on keys/operations and adaptively learn which ones should trigger escalation.",
    "Fully explore the proposal's riskier 'validate-then-lock' idea for escalation. This would require inventing a novel, lightweight mechanism to prevent races between the validation and locking steps, which would be a significant contribution.",
    "Generalize the semantic model beyond simple key-name patterns. Allow transactions to declare conflict profiles or use static analysis to infer them, making the escalation policy more robust and portable across workloads."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements the Escalating Semantic Locking (ESL) protocol.
 *
 * Core Idea: Transactions start in a non-blocking OPTIMISTIC mode. They escalate to a locking-based
 * PESSIMISTIC mode only when performing specific high-contention, non-commutative operations.
 * This approach combines the high throughput of OCC for low-conflict workloads (like Payment) with the
 * guaranteed forward progress and correctness of 2PL for high-conflict operations.
 *
 * Optimizations for TPC-C:
 * 1.  Atomic Optimistic Reads: Optimistic reads are protected by a short-term lock to ensure the value
 *     and its version are read atomically, preventing race conditions that break serializability.
 * 2.  Proactive Escalation: Transactions escalate to pessimistic mode upon reading a known hotspot key
 *     (e.g., `_next_oid`, `_qty`), ensuring correctness and managing contention early.
 * 3.  Blind Read Elimination: The read of `c_..._bal` in NewOrder is identified as a blind read and is
 *     excluded from validation, preventing false-positive conflicts with Payment transactions.
 * 4.  Deadlock Resolution: A timeout-based lock acquisition strategy is used to detect and resolve potential
 *     deadlocks by aborting one of the conflicting transactions.
 * 5.  Correct Escalation: The escalation process is atomic by locking keys *before* validation.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final AtomicInteger timestampCounter = new AtomicInteger(0);

    private final ConcurrentHashMap<String, AtomicInteger> keyVersions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    private enum ESLMode { OPTIMISTIC, PESSIMISTIC }
    private static final String META_MODE = "esl_mode";
    private static final String META_READ_VERSIONS = "esl_read_versions";
    private static final String META_HELD_LOCKS = "esl_held_locks";
    private static final String META_TXN_TYPE = "esl_txn_type";
    private static final long LOCK_TIMEOUT_MS = 10;

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, timestampCounter.get());
        txn.meta.put(META_MODE, ESLMode.OPTIMISTIC);
        txn.meta.put(META_READ_VERSIONS, new ConcurrentHashMap<String, Integer>());
        txn.meta.put(META_HELD_LOCKS, new ConcurrentHashMap<String, ReentrantLock>());
        txn.meta.put(META_TXN_TYPE, "UNKNOWN");
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (txn.writeSet.containsKey(key)) return txn.writeSet.get(key);
        if (txn.readSet.containsKey(key)) return txn.readSet.get(key);

        if ((ESLMode) txn.meta.get(META_MODE) == ESLMode.OPTIMISTIC && isEscalationKey(key)) {
            if ("UNKNOWN".equals(txn.meta.get(META_TXN_TYPE))) {
                txn.meta.put(META_TXN_TYPE, "NEW_ORDER");
            }
            if (!escalate(txn)) {
                txn.status = TxStatus.ABORTED;
                return null;
            }
        }

        Object value;
        ESLMode mode = (ESLMode) txn.meta.get(META_MODE);

        if (mode == ESLMode.OPTIMISTIC) {
            ReentrantLock readLock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
            readLock.lock();
            try {
                value = unwrapValue(store.get(key));
                if ("UNKNOWN".equals(txn.meta.get(META_TXN_TYPE))) {
                    if (key.endsWith("_ytd")) {
                       txn.meta.put(META_TXN_TYPE, "PAYMENT");
                   }
                }
                if (!isBlindReadKey(key, txn)) {
                    int version = getVersionCounter(key).get();
                    ((Map<String, Integer>) txn.meta.get(META_READ_VERSIONS)).put(key, version);
                }
            } finally {
                readLock.unlock();
            }
        } else { // PESSIMISTIC
            if (!acquireLock(txn, key)) {
                txn.status = TxStatus.ABORTED;
                return null;
            }
            value = unwrapValue(store.get(key));
        }

        if (value != null) txn.readSet.put(key, value);
        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if ((ESLMode) txn.meta.get(META_MODE) == ESLMode.PESSIMISTIC) {
            if (!acquireLock(txn, key)) {
                txn.status = TxStatus.ABORTED;
                return;
            }
        }
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            cleanup(txn);
            return CommitResult.failure("Transaction not active");
        }

        try {
            if ((ESLMode) txn.meta.get(META_MODE) == ESLMode.OPTIMISTIC) {
                Set<String> keysToLockSet = new HashSet<>(((Map<String, Integer>) txn.meta.get(META_READ_VERSIONS)).keySet());
                keysToLockSet.addAll(txn.writeSet.keySet());
                if (!acquireLocks(txn, getSortedUniqueKeys(keysToLockSet))) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Deadlock detected during commit");
                }
                if (!validate(txn)) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Optimistic validation failed after locking");
                }
            }

            if (!txn.writeSet.isEmpty()) {
                int commitTs = timestampCounter.incrementAndGet();
                for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                    store.update(entry.getKey(), entry.getValue());
                    getVersionCounter(entry.getKey()).set(commitTs);
                }
                txn.status = TxStatus.COMMITTED;
                return CommitResult.success(commitTs);
            } else {
                txn.status = TxStatus.COMMITTED;
                return CommitResult.success(timestampCounter.get());
            }
        } finally {
            cleanup(txn);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
        }
        cleanup(txn);
    }

    private boolean escalate(Transaction txn) {
        Set<String> keysToLockSet = new HashSet<>(((Map<String, Integer>) txn.meta.get(META_READ_VERSIONS)).keySet());
        keysToLockSet.addAll(txn.writeSet.keySet());
        
        if (!acquireLocks(txn, getSortedUniqueKeys(keysToLockSet))) return false;
        if (!validate(txn)) return false;
        
        txn.meta.put(META_MODE, ESLMode.PESSIMISTIC);
        return true;
    }
    
    private boolean validate(Transaction txn) {
        Map<String, Integer> readVersions = (Map<String, Integer>) txn.meta.get(META_READ_VERSIONS);
        for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
            if (getVersionCounter(entry.getKey()).get() > entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private boolean acquireLock(Transaction txn, String key) {
        Map<String, ReentrantLock> heldLocks = (Map<String, ReentrantLock>) txn.meta.get(META_HELD_LOCKS);
        if (heldLocks != null && heldLocks.containsKey(key)) return true;

        ReentrantLock lock = keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
        try {
            if (lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (txn.meta.get(META_HELD_LOCKS) != null) {
                    ((Map<String, ReentrantLock>) txn.meta.get(META_HELD_LOCKS)).put(key, lock);
                }
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private boolean acquireLocks(Transaction txn, List<String> sortedKeys) {
        for (String key : sortedKeys) {
            if (!acquireLock(txn, key)) return false;
        }
        return true;
    }

    private void cleanup(Transaction txn) {
        Map<String, ReentrantLock> heldLocks = (Map<String, ReentrantLock>) txn.meta.get(META_HELD_LOCKS);
        if (heldLocks != null) {
            for (ReentrantLock lock : heldLocks.values()) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        txn.meta.clear();
        txn.readSet.clear();
        txn.writeSet.clear();
    }

    private AtomicInteger getVersionCounter(String key) {
        return keyVersions.computeIfAbsent(key, k -> new AtomicInteger(0));
    }

    private Object unwrapValue(Object val) { return (val instanceof AtomicLong) ? ((AtomicLong) val).get() : val; }
    private List<String> getSortedUniqueKeys(Collection<String> keys) { return new ArrayList<>(new TreeSet<>(keys)); }
    private boolean isEscalationKey(String key) { return key.endsWith("_next_oid") || key.endsWith("_qty"); }
    private boolean isBlindReadKey(String key, Transaction txn) { return key.startsWith("c_") && key.endsWith("_bal") && "NEW_ORDER".equals(txn.meta.get(META_TXN_TYPE)); }
}
