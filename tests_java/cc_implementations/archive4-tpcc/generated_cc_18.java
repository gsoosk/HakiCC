/* Technical Report:
### Final Technical Report: Semantic Constraint Validation OCC (SCV-OCC)

#### 1. Algorithm Overview

Semantic Constraint Validation Optimistic Concurrency Control (SCV-OCC) is a hybrid protocol that combines an optimistic execution phase with a pessimistic, lock-based commit phase. Transactions execute speculatively against a snapshot of the database, buffering all read values and write intentions locally. This allows for a highly parallel, non-blocking execution phase.

At commit time, the protocol transitions to a pessimistic strategy. It acquires exclusive locks on all keys in the transaction's read and write sets. Once locks are held, it performs two validation stages: first, a standard optimistic snapshot validation to ensure the transaction's reads are still valid, and second, an application-aware semantic validation for specific operations to enforce business logic integrity against the most current database state. If both validations pass, the buffered writes are applied, and the locks are released.

#### 2. Read/Write/Lock and Validation Strategy

*   **Read Strategy:** The protocol enforces **Repeatable Reads**. A transaction's first read of a key fetches the value from the `KVStore` and saves an immutable snapshot of it in a local `readSet`. All subsequent reads of that key within the same transaction are served from this local `readSet`, guaranteeing a consistent view. "Read-your-own-writes" is supported by first checking the local `writeSet`. Reads of non-existent keys are tracked to detect phantoms.

*   **Write Strategy:** Writes are buffered in a local `writeSet`, which stores the final intended value for each key. This allows multiple writes to the same key to be correctly resolved to the last write. For specific operations identified by key patterns (e.g., TPC-C stock updates), the write also registers a semantic predicate that encapsulates the business rule (e.g., `stock >= 0`).

*   **Commit Strategy (Locking & Validation):** The commit process is a serialized critical section:
    1.  **Locking:** Acquire exclusive locks on the union of the transaction's read and write sets.
    2.  **Snapshot Validation:** Perform a standard OCC validation. For every key in the `readSet`, compare its snapshotted value with the current value in the `KVStore`. If any mismatch is found, the transaction aborts. This step is universal and guarantees protection against read-write conflicts.
    3.  **Semantic Validation:** If snapshot validation passes, execute any registered semantic predicates against the current `KVStore` state. This acts as an additional, application-specific integrity check. If any predicate fails, the transaction aborts.
    4.  **Apply Writes:** If both validations succeed, apply the changes to the `KVStore`. The protocol correctly handles both the creation of new keys (`insert`) and the update of existing ones (by applying a calculated `delta`).
    5.  **Unlocking:** Release all locks.

#### 3. Deadlock Handling

The protocol is **deadlock-free**. Deadlocks are prevented by acquiring all necessary locks during the commit phase in a globally consistent, canonical (lexicographical) order. This strategy eliminates the possibility of circular wait dependencies between transactions.

#### 4. Correctness Guarantees

SCV-OCC provides **Strict Conflict-Serializability**.
*   **Serializability:** The serialization point for any transaction is the moment it successfully acquires all its locks. The mandatory and universal snapshot validation ensures that a transaction's view of the database is consistent with the serial history of all previously committed transactions.
*   **Strictness:** Writes are only applied to the main database after the commit decision is final and while holding exclusive locks. This prevents uncommitted data from being visible to other transactions (no dirty reads) and avoids cascading aborts.

#### 5. Complexity and Performance Notes

*   **Performance:** The protocol is well-suited for workloads with low to moderate data contention, where the optimistic execution phase can provide high parallelism. Performance is sensitive to the duration of the commit phase; transactions with large read/write sets will increase lock contention and may reduce throughput. The primary optimization is the non-blocking execution phase rather than a reduction in aborts.
*   **Complexity:** The protocol has higher implementation complexity than pure OCC or 2PL due to its hybrid nature and the management of multiple validation strategies.

#### 6. Assumptions and Limitations

*   **Assumptions:** The protocol assumes that writes are numeric and can be applied atomically as deltas or inserts. It also requires a mechanism (e.g., key-name pattern matching) to identify which operations require semantic validation.
*   **Limitations:** Under high data contention, the protocol's performance may degrade as the abort rate increases due to frequent snapshot validation failures. The locking phase can become a bottleneck if transactions consistently access a common set of hot-spot keys. The semantic validation feature, in its final correct implementation, serves as an additional integrity check rather than a mechanism to reduce aborts from read-write conflicts.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 5,
    "verdict": "Derivative",
    "score_breakdown": {
        "conceptual_originality_40": 8,
        "distance_from_known_families_30": 5,
        "implementation_distinctiveness_15": 3,
        "correctness_argument_originality_10": 1,
        "rebranding_penalty_minus15": -15
    },
    "separate_scores": {
        "proposal_novelty": 65,
        "code_novelty": 5
    },
    "overlap_analysis": [
        {
            "family": "OCC (Optimistic Concurrency Control)",
            "overlap_reasoning": "The implementation's commit logic is dominated by a classic OCC read-set validation phase. It acquires locks and then immediately checks if any value in the transaction's read-set has changed in the global store. This is the defining characteristic of OCC. The proposed 'semantic validation' is only performed *after* this standard check passes, making the protocol operationally a minor superset of OCC, not a replacement for it.",
            "overlap_strength_0_to_100": 95
        },
        {
            "family": "Escrow Locking / Semantic Locking",
            "overlap_reasoning": "The *proposal concept* is a form of optimistic escrow, where transactions proceed and validate a semantic invariant at commit time. However, the implementation fails to realize this by retaining a conflicting, stricter validation model (standard OCC). The use of predicates is the only remaining link to this family.",
            "overlap_strength_0_to_100": 20
        },
        {
            "family": "2PL (Two-Phase Locking)",
            "overlap_reasoning": "The protocol uses locks, but only in a short, pessimistic commit phase after an optimistic execution phase. This differs significantly from classic 2PL where locks are acquired during execution and held until commit. The locking here is an implementation detail for the OCC commit-phase critical section.",
            "overlap_strength_0_to_100": 15
        }
    ],
    "feature_diff": [
        {
            "axis": "commit rule / validation",
            "ours": "Locks the commit-set, performs a standard OCC read-set validation (aborting if any read value changed), and only if that passes, performs a second validation of semantic business-logic predicates.",
            "typical_in_nearest_family": "In OCC, the transaction enters a critical section and validates that its read-set is consistent with the current database state (i.e., no versions have changed).",
            "difference_note": "The implementation's fatal flaw is performing standard OCC validation *before* the novel semantic check. This completely negates the proposal's central claim of avoiding aborts when a value changes but the semantic constraint still holds. Any such change would cause an immediate abort from the first validation step, making the semantic check largely unreachable in high-contention scenarios."
        },
        {
            "axis": "metadata",
            "ours": "Per-transaction read-set, write-set, and a map of semantic predicates. No global versions or timestamps.",
            "typical_in_nearest_family": "OCC requires a per-transaction read-set and write-set. It relies on some mechanism (versions, timestamps, or direct comparison as done here) to validate the read-set.",
            "difference_note": "The addition of the `constraintSet` is the only novel piece of metadata. However, the claim of eliminating versioning is misleading; comparing read-set values against current store values is a form of version validation, just without explicit version numbers."
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
            "Per-transaction map of executable Predicates representing semantic constraints."
        ]
    },
    "correctness_rationale_summary": "Correctness is ensured by a standard OCC serializability argument. At commit, the transaction acquires exclusive locks on its read and write sets, creating a critical section. Inside, it validates that all data it read has not been modified by another committed transaction. This check serves as the serialization point. The subsequent semantic validation is an additional application-level check but is not fundamental to the serializability proof, which rests entirely on the classic read-set validation. The rationale itself is not novel.",
    "risks_and_edge_cases": [
        "The implementation fundamentally contradicts the proposal's core value proposition. It cannot avoid aborts on semantically-valid updates because its standard OCC validation phase will abort the transaction first.",
        "The semantic validation logic is effectively dead code in the very contention scenarios it was designed to handle.",
        "The protocol as written is strictly *more* prone to aborts than standard OCC, as it adds a second potential point of failure (the semantic check) after the first.",
        "Locking the entire read-set can cause significant commit-phase bottlenecks for read-heavy transactions."
    ],
    "suggestions_to_increase_novelty": [
        "Faithfully implement the proposal: Remove the entire standard OCC read-set validation loop from the `commit` method. The validation logic should consist *only* of acquiring locks and then executing the semantic predicates against the live store state. This would make the protocol truly novel.",
        "Refine the locking scope: Instead of locking the union of the read and write sets, a more novel approach would be to only lock keys that are part of the `deltaSet` or are explicitly referenced in the `constraintSet`, reducing the blocking footprint.",
        "Generalize the constraint mechanism: Move beyond hard-coded logic for stock keys. Create a formal API for transactions to register semantic invariants (e.g., `tx.addInvariant(key, predicate)`), making the protocol a general-purpose semantic CC mechanism."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final AtomicInteger clock = new AtomicInteger(0);
    private static final LockManager lockManager = new LockManager();

    private static final String CONSTRAINT_MAP_KEY = "constraintMap";
    private static final Object NULL_SENTINEL = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private static class LockManager {
        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        private ReentrantLock getLock(String key) {
            return locks.computeIfAbsent(key, k -> new ReentrantLock());
        }

        public void lockAll(Collection<String> keys) {
            keys.stream().sorted().forEach(key -> getLock(key).lock());
        }

        public void unlockAll(Collection<String> keys) {
            keys.stream().sorted(Comparator.reverseOrder()).forEach(key -> {
                ReentrantLock lock = getLock(key);
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            });
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.incrementAndGet();
        int startTs = clock.get();
        Transaction txn = new Transaction(txid, startTs);
        txn.meta.put(CONSTRAINT_MAP_KEY, new ConcurrentHashMap<String, Predicate<KVStore>>());
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        if (txn.readSet.containsKey(key)) {
            Object value = txn.readSet.get(key);
            return value == NULL_SENTINEL ? null : value;
        }

        Object value = store.get(key);
        if (value instanceof AtomicLong) {
            long snapshotValue = ((AtomicLong) value).get();
            txn.readSet.put(key, snapshotValue);
            return snapshotValue;
        } else {
            txn.readSet.put(key, value == null ? NULL_SENTINEL : value);
            return value;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if (!txn.readSet.containsKey(key)) {
            read(txn, key, store);
        }
        
        txn.writeSet.put(key, value);

        if (key.startsWith("s_") && key.endsWith("_qty")) {
            ConcurrentHashMap<String, Predicate<KVStore>> constraintMap = 
                (ConcurrentHashMap<String, Predicate<KVStore>>) txn.meta.get(CONSTRAINT_MAP_KEY);

            Predicate<KVStore> stockConstraint = (s) -> {
                Object currentStockObj = s.get(key);
                long currentStock = (currentStockObj instanceof AtomicLong) ? ((AtomicLong) currentStockObj).get() : 0;
                
                Object originalValueObj = txn.readSet.get(key);
                long originalValue = (originalValueObj instanceof Long) ? (Long) originalValueObj : 0;
                
                long finalValue = ((Number) txn.writeSet.get(key)).longValue();
                long delta = finalValue - originalValue;

                long finalStock = currentStock + delta;
                return finalStock >= 0;
            };
            constraintMap.put(key, stockConstraint);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        Set<String> keysToLock = new HashSet<>();
        keysToLock.addAll(txn.readSet.keySet());
        keysToLock.addAll(txn.writeSet.keySet());

        if (keysToLock.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(clock.incrementAndGet());
        }

        lockManager.lockAll(keysToLock);
        try {
            // 1. STANDARD READ-SET VALIDATION
            for (Map.Entry<String, Object> readEntry : txn.readSet.entrySet()) {
                String key = readEntry.getKey();
                Object snapshotValue = readEntry.getValue();
                Object currentValue = store.get(key);

                if (snapshotValue == NULL_SENTINEL) {
                    if (currentValue != null) {
                        txn.status = TxStatus.ABORTED;
                        return CommitResult.failure("Phantom read on key " + key);
                    }
                } else if (snapshotValue instanceof Long) {
                    if (!(currentValue instanceof AtomicLong) || !snapshotValue.equals(((AtomicLong) currentValue).get())) {
                        txn.status = TxStatus.ABORTED;
                        return CommitResult.failure("Read validation failed on key " + key);
                    }
                } else if (!Objects.equals(snapshotValue, currentValue)) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Read validation failed on key " + key);
                }
            }

            // 2. SEMANTIC CONSTRAINT VALIDATION
            ConcurrentHashMap<String, Predicate<KVStore>> constraintMap = 
                (ConcurrentHashMap<String, Predicate<KVStore>>) txn.meta.get(CONSTRAINT_MAP_KEY);
            for (Predicate<KVStore> constraint : constraintMap.values()) {
                if (!constraint.test(store)) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Semantic constraint violation");
                }
            }

            // 3. APPLY PHASE
            for (Map.Entry<String, Object> writeEntry : txn.writeSet.entrySet()) {
                String key = writeEntry.getKey();
                Object finalValue = writeEntry.getValue();
                Object snapshotObj = txn.readSet.get(key);

                if (snapshotObj == NULL_SENTINEL) {
                    // Key is being created.
                    store.insert(key, finalValue);
                } else {
                    // Key is being updated.
                    long finalNum = ((Number) finalValue).longValue();
                    long snapshotNum = (snapshotObj instanceof Long) ? (Long) snapshotObj : 0;
                    long delta = finalNum - snapshotNum;
                    if (delta != 0) {
                        store.increment(key, delta);
                    }
                }
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(clock.incrementAndGet());

        } finally {
            lockManager.unlockAll(keysToLock);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
        }
        txn.meta.clear();
        txn.readSet.clear();
        txn.writeSet.clear();
    }
}
