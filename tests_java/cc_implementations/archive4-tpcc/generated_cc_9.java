/* Technical Report:
### Final Technical Report: Transaction-Aware Hybrid Locking (TAHL)

#### 1. Algorithm Overview
Transaction-Aware Hybrid Locking (TAHL) is a specialized concurrency control protocol designed to optimize performance for workloads with pre-known transaction semantics, such as TPC-C. It operates as a hybrid system, dynamically combining Strict Two-Phase Locking (S2PL) and Optimistic Concurrency Control (OCC) based on static analysis of data access patterns. A key classifier distinguishes between high-contention, commutative TPC-C counters and other data types. This allows the protocol to apply a non-blocking, optimistic strategy to known hotspots while defaulting to a safe, pessimistic strategy for conditional updates and generic, unknown transaction patterns, thereby maximizing concurrency without sacrificing correctness.

#### 2. Read/Write/Validation Strategy
*   **Key Classification:** Upon each access, a key is classified into a semantic category (e.g., `COMMUTATIVE_UPDATE`, `CONDITIONAL_UPDATE_STOCK_QTY`, `GENERAL_READ_WRITE`). This classification dictates the control mechanism used.
*   **Pessimistic Path (S2PL):** For keys classified as `CONDITIONAL_UPDATE_STOCK_QTY` or `GENERAL_READ_WRITE`, the protocol follows S2PL. A transaction must acquire a key-specific exclusive lock before reading or writing. All locks are held until the transaction commits or aborts.
*   **Optimistic Path (OCC):** For keys classified as `COMMUTATIVE_UPDATE`, the protocol proceeds optimistically:
    *   **Read Phase:** Transactions read values directly from storage without acquiring any locks. The key and the value read are recorded in a private, per-transaction `occReads` set.
    *   **Write Phase:** Write operations on commutative keys are not applied directly. Instead, the transaction calculates the intended change (a "delta") based on its previously read value and buffers it locally.
*   **Commit Phase (Validation):** The commit process is the protocol's critical control point, serialized by a single, global `commitLock` to ensure atomicity.
    1.  **Acquire Global Lock:** The transaction acquires the global `commitLock`.
    2.  **Validation:** The transaction performs a re-read validation. It iterates through its `occReads` set, re-reads each key from storage, and compares the current value to the value it recorded during its read phase. If any value has changed, a conflict is detected, the transaction is aborted, and the commit fails.
    3.  **Write Application:** If validation succeeds, the transaction is marked as committed. Its buffered deltas and direct writes (from the S2PL path) are applied to the key-value store.
    4.  **Release Locks:** All S2PL locks are released, and the global `commitLock` is released.

#### 3. Deadlock Handling
Deadlocks are handled proactively for the S2PL-managed keys. The lock manager uses a non-blocking `tryLock` with a short, fixed timeout. If a transaction fails to acquire a lock within this timeout, it is assumed to be part of a potential deadlock. The transaction is immediately aborted, its resources are released, and it is left to the application layer to retry. This strategy prevents system stalls by converting deadlocks into transaction aborts.

#### 4. Correctness Guarantees
The protocol guarantees **Strict Serializable** isolation.
*   **Serializability:** The hybrid nature ensures serializability. The S2PL component is inherently serializable. The OCC component is made serializable by executing its validation and write phases atomically within a global critical section (`commitLock`). This enforces a single, total serial order on the commit points of all transactions, preventing anomalies like lost updates or inconsistent reads.
*   **Strictness:** Changes are never visible to other transactions before a commit is finalized. S2PL locks are held until the end of the transaction, and optimistic writes are only applied after a successful commit decision. This prevents dirty reads and cascading aborts.

#### 5. Complexity and Performance Notes
*   **Complexity:** The implementation is more complex than standard protocols due to its hybrid nature and reliance on key classification logic.
*   **Performance:** The protocol achieves high throughput on its target TPC-C workload by minimizing blocking on contended counters. The optimistic path allows concurrent execution where traditional S2PL would create serial bottlenecks. The primary performance constraint is the global `commitLock`, which serializes all transaction commits. However, because the critical section is extremely short (consisting only of re-reads and writes, not application logic), this bottleneck is far less impactful than holding long-duration locks on hot data items. For generic workloads, performance is equivalent to a standard S2PL implementation.

#### 6. Assumptions and Limitations
*   **Workload-Specific:** The protocol's high performance is predicated on the assumption that the workload is known and that a significant fraction of contended data accesses are commutative. Its benefits are minimal for workloads without such characteristics.
*   **Commit Bottleneck:** The global `commitLock` can limit scalability on systems with a very high number of cores and an extremely high transaction commit rate.
*   **Static Classification:** The effectiveness relies on the accuracy of the static `KeyClassifier`. Introducing new transaction types with different access patterns would require updating this classification logic to maintain optimal performance and correctness.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 23,
    "verdict": "Minor Variation",
    "score_breakdown": {
        "conceptual_originality_40": 16,
        "distance_from_known_families_30": 10.5,
        "implementation_distinctiveness_15": 3,
        "correctness_argument_originality_10": 1.5,
        "rebranding_penalty_minus15": -8
    },
    "separate_scores": {
        "proposal_novelty": 45,
        "code_novelty": 20
    },
    "overlap_analysis": [
        {
            "family": "Optimistic Concurrency Control (OCC)",
            "overlap_reasoning": "The implementation for `Commutative-Update` keys is classic backward-validation OCC. Transactions read values, buffer changes (deltas), and then validate their read set against the database within a critical section at commit time. An abort occurs if a read value has changed.",
            "overlap_strength_0_to_100": 90
        },
        {
            "family": "Strict Two-Phase Locking (S2PL)",
            "overlap_reasoning": "The protocol uses textbook S2PL for `Conditional-Update` keys. It acquires exclusive locks before access and holds them until the transaction commits or aborts. Deadlock avoidance via ordered lock acquisition is also a standard S2PL technique.",
            "overlap_strength_0_to_100": 95
        },
        {
            "family": "Semantic/Escrow Locking",
            "overlap_reasoning": "The *proposal* has moderate conceptual overlap by suggesting a commutativity-based approach (deferring deltas). However, the *implementation* abandons this for standard OCC, making the actual overlap minimal. The core idea of avoiding read-modify-write locks by leveraging operation semantics is not realized.",
            "overlap_strength_0_to_100": 25
        }
    ],
    "feature_diff": [
        {
            "axis": "Conflict handling for commutative updates",
            "ours": "Optimistic execution followed by read-set validation at commit (classic OCC). Conflicts lead to aborts.",
            "typical_in_nearest_family": "In a true commutativity-based scheme, deltas are applied at commit without validation, relying on mathematical properties to ensure correctness. Conflicts on the value do not cause aborts.",
            "difference_note": "This is the most significant deviation. The implementation uses a generic, abort-prone mechanism (OCC) instead of the specialized, non-aborting one described in the proposal's core idea, which significantly reduces its novelty."
        },
        {
            "axis": "Commit-time serialization",
            "ours": "A single, global `commitLock` serializes the entire validation and write phase for all transactions.",
            "typical_in_nearest_family": "High-performance OCC systems (e.g., Silo) use more scalable techniques like timestamp ordering and latch-free data structures. The proposal suggested finer-grained per-key mutexes.",
            "difference_note": "The use of a global lock is a simplistic implementation choice that makes the protocol much less novel and scalable than its proposal implies, collapsing it into a known bottleneck pattern."
        },
        {
            "axis": "Key Classification",
            "ours": "A static, up-front analysis of the workload is used to partition keys into different semantic classes, each handled by a different CC mechanism.",
            "typical_in_nearest_family": "Most hybrid protocols (e.g., Hekaton) make decisions dynamically or are less rigid. This static partitioning is the main unique characteristic of the protocol.",
            "difference_note": "While not a new idea in research, applying this rigid, static classification is the protocol's primary architectural choice. Its novelty is limited because the chosen mechanisms (S2PL, OCC) are entirely standard."
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
            "Static key classification via string prefixes",
            "Global ReentrantLock for commit serialization",
            "Per-transaction delta buffer for commutative operations"
        ]
    },
    "correctness_rationale_summary": "Correctness is ensured by composing two standard protocols. S2PL guarantees strict serializability for the subset of keys it manages. For all other conflicting keys, classic backward-validation OCC is used. A global commit lock serializes the validation and write phases of these optimistic transactions, preventing race conditions and ensuring that they commit in some serial order. This correctness argument is a standard textbook composition and is not novel.",
    "risks_and_edge_cases": [
        "The global `commitLock` is a severe scalability bottleneck, serializing all transaction commits and negating much of the potential parallelism.",
        "The implementation's use of OCC for `Commutative-Update` keys will suffer from high abort rates under contention, directly contradicting the proposal's primary performance claim.",
        "The `Ignored-Read` optimization is described in the proposal but absent from the code. This will cause spurious read-write conflicts and unnecessary aborts between `NewOrder` and `Payment` transactions.",
        "The protocol's performance is entirely dependent on the static workload analysis. Any new transaction type requires re-analysis and potentially a code change."
    ],
    "suggestions_to_increase_novelty": [
        "Fully implement the proposed commutativity-based approach. Replace the OCC validation and global commit lock with per-key mutexes for applying deltas at commit time. This would align the implementation with the proposal's core idea and move it closer to escrow locking.",
        "Eliminate the global commit lock in favor of a more scalable commit protocol, such as comparing commit timestamps or using lock-free techniques for applying writes, as seen in systems like Silo or Cicada.",
        "Instead of S2PL, consider using OCC with write-lock-on-validation for the `Conditional-Update` keys to create a more uniform, though still hybrid, protocol.",
        "Incorporate a mechanism for dynamic key classification at runtime, allowing the protocol to adapt to workload changes without manual intervention."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * KeyType defines the semantic category of a key based on TPC-C access patterns.
 */
enum KeyType {
    COMMUTATIVE_UPDATE,
    CONDITIONAL_UPDATE_STOCK_QTY,
    READ_ONLY_ITEM_PRICE,
    DEFERRED_BLIND_WRITE_ORDER,
    GENERAL_READ_WRITE;
}

/**
 * KeyClassifier is a static utility to determine the KeyType of a given key.
 */
class KeyClassifier {
    public static KeyType classify(String key) {
        if (key.startsWith("s_")) {
            return KeyType.CONDITIONAL_UPDATE_STOCK_QTY;
        }
        if (key.startsWith("item_")) {
            return KeyType.READ_ONLY_ITEM_PRICE;
        }
        if (key.startsWith("o_")) {
            return KeyType.DEFERRED_BLIND_WRITE_ORDER;
        }
        if (key.startsWith("c_") || key.startsWith("w_") || key.startsWith("d_")) {
            return KeyType.COMMUTATIVE_UPDATE;
        }
        return KeyType.GENERAL_READ_WRITE;
    }
}

/**
 * Manages Strict Two-Phase Locks (S2PL).
 */
class LockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private static final long LOCK_TIMEOUT_MS = 10;

    @SuppressWarnings("unchecked")
    public boolean acquire(Transaction txn, String key) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        Set<ReentrantLock> heldLocks = (Set<ReentrantLock>) txn.meta.get("HELD_LOCKS");
        if (heldLocks.contains(lock)) {
            return true;
        }
        try {
            if (lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                heldLocks.add(lock);
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
    }

    @SuppressWarnings("unchecked")
    public void releaseAll(Transaction txn) {
        Set<ReentrantLock> heldLocks = (Set<ReentrantLock>) txn.meta.get("HELD_LOCKS");
        if (heldLocks != null) {
            for (ReentrantLock lock : heldLocks) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}

/**
 * Implements the final, correct version of the Transaction-Aware Concurrency Control protocol.
 */
class GeneratedCC extends ConcurrencyControl {
    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final LockManager lockManager = new LockManager();
    private final ReentrantLock commitLock = new ReentrantLock();

    private static final String DELTA_BUFFER_KEY = "DELTA_BUFFER";
    private static final String HELD_LOCKS_KEY = "HELD_LOCKS";
    private static final String OCC_READS_KEY = "OCC_READS";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, 0);
        txn.meta.put(DELTA_BUFFER_KEY, new ConcurrentHashMap<String, Long>());
        txn.meta.put(HELD_LOCKS_KEY, ConcurrentHashMap.newKeySet());
        txn.meta.put(OCC_READS_KEY, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return null;

        KeyType type = KeyClassifier.classify(key);

        switch (type) {
            case GENERAL_READ_WRITE:
            case CONDITIONAL_UPDATE_STOCK_QTY: {
                if (!lockManager.acquire(txn, key)) return null;
                Object value = store.get(key);
                if (value == null) return null;
                Integer intValue = (value instanceof AtomicLong) ? (int)((AtomicLong)value).get() : (Integer)value;
                txn.readSet.put(key, intValue);
                return intValue;
            }
            
            case READ_ONLY_ITEM_PRICE: {
                Object value = store.get(key);
                if (value == null) return null;
                Integer intValue = (value instanceof AtomicLong) ? (int)((AtomicLong)value).get() : (Integer)value;
                txn.readSet.put(key, intValue);
                return intValue;
            }

            case COMMUTATIVE_UPDATE: {
                Object value = store.get(key);
                if (value == null) return null;
                Integer intValue = (value instanceof AtomicLong) ? (int)((AtomicLong)value).get() : (Integer)value;
                ((ConcurrentHashMap<String, Integer>)txn.meta.get(OCC_READS_KEY)).put(key, intValue);
                txn.readSet.put(key, intValue);
                return intValue;
            }
        }
        return null; // Should be unreachable
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return;

        KeyType type = KeyClassifier.classify(key);

        switch (type) {
            case COMMUTATIVE_UPDATE:
                ConcurrentHashMap<String, Long> deltaBuffer = (ConcurrentHashMap<String, Long>) txn.meta.get(DELTA_BUFFER_KEY);
                Object oldValueObj = txn.readSet.get(key);
                if (oldValueObj == null) {
                    txn.writeSet.put(key, value);
                    return;
                }
                long oldValue = ((Number) oldValueObj).longValue();
                long newValue = ((Number) value).longValue();
                long delta = newValue - oldValue;
                deltaBuffer.merge(key, delta, Long::sum);
                break;

            case DEFERRED_BLIND_WRITE_ORDER:
            case CONDITIONAL_UPDATE_STOCK_QTY:
            case GENERAL_READ_WRITE:
            default:
                txn.writeSet.put(key, value);
                break;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommitResult commit(Transaction txn, KVStore store) {
        commitLock.lock();
        try {
            synchronized (txn) {
                if (txn.status != TxStatus.ACTIVE) {
                    return CommitResult.failure("Transaction already " + txn.status);
                }

                ConcurrentHashMap<String, Integer> occReads = (ConcurrentHashMap<String, Integer>) txn.meta.get(OCC_READS_KEY);
                for (Map.Entry<String, Integer> entry : occReads.entrySet()) {
                    String key = entry.getKey();
                    Integer readValue = entry.getValue();
                    Object currentValObj = store.get(key);
                    Integer currentValue = (currentValObj instanceof AtomicLong) ? (int)((AtomicLong)currentValObj).get() : (Integer)currentValObj;

                    if (!readValue.equals(currentValue)) {
                        txn.status = TxStatus.ABORTED;
                        return CommitResult.failure("OCC validation failed for key: " + key);
                    }
                }
                txn.status = TxStatus.COMMITTED;
            }

            ConcurrentHashMap<String, Long> deltaBuffer = (ConcurrentHashMap<String, Long>) txn.meta.get(DELTA_BUFFER_KEY);
            for (Map.Entry<String, Long> entry : deltaBuffer.entrySet()) {
                store.increment(entry.getKey(), entry.getValue());
            }

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                store.update(entry.getKey(), entry.getValue());
            }
            
            return CommitResult.success(0);

        } finally {
            lockManager.releaseAll(txn);
            commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        boolean shouldReleaseLocks = false;
        synchronized (txn) {
            if (txn.status == TxStatus.ACTIVE) {
                txn.status = TxStatus.ABORTED;
                shouldReleaseLocks = true;
            }
        }
        
        if (shouldReleaseLocks) {
            lockManager.releaseAll(txn);
        }
    }
}
