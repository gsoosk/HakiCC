/* Technical Report:
### Final Report: Speculative Pre-declared Writes (SPW)

#### 1. Algorithm Overview
Speculative Pre-declared Writes (SPW) is a hybrid concurrency control protocol optimized for mixed OLTP workloads. It dynamically classifies transactions at commit time based on the semantics of their write operations. Transactions that perform only commutative updates (identified as `STATIC`) are routed to a highly concurrent commit path, while transactions with any non-commutative writes (`DYNAMIC`) follow a more rigorous optimistic protocol. This dual-path approach is designed to maximize throughput by allowing the most frequent, low-conflict transaction types to commit with minimal overhead, while still ensuring the serializability of more complex transactions.

#### 2. Read/Write/Lock and Validation Strategy
*   **Read Visibility Rule:** The protocol uses a snapshot-based read model. The first time a transaction reads a key, it performs a lock-free, consistent read by verifying the key's version number before and after reading the value. This consistent value and its version are then cached in the transaction's private `readSet` and `readVersions` map, respectively. All subsequent reads of the same key within the transaction are served from this cache, ensuring repeatable reads.
*   **Write Strategy:** All writes are deferred. A transaction buffers its intended writes in a private `writeSet` without modifying the global state.
*   **Commit Rule & Validation:** At commit time, the transaction is classified:
    1.  **STATIC Path (e.g., `tpccPayment`):**
        *   **Locking:** Acquires locks on all keys in its write-set in a canonical order.
        *   **No Validation:** This path optimistically assumes no read-write conflicts will occur, skipping the read-set validation phase.
        *   **Write:** It calculates deltas (new value - read value) and applies them to the `KVStore` using atomic increment operations. It then increments the version of each modified key. Locks are released.
    2.  **DYNAMIC Path (e.g., `tpccNewOrder`):**
        *   **Validate-Lock-Revalidate:** The transaction first validates its read-set against the global version map. If it passes, it acquires locks on the union of its read and write sets in a canonical order. After locking, it re-validates the read-set to close any race condition windows.
        *   **Write:** If re-validation succeeds, it applies its buffered writes as absolute values and increments the version of each modified key. Locks are then released.
*   **Abort Conditions:** A `DYNAMIC` transaction aborts if its validation fails at any point. A `STATIC` transaction does not abort due to data contention.

#### 3. Deadlock Handling
The protocol uses **deadlock prevention**. In both the `STATIC` and `DYNAMIC` commit paths, all required locks are acquired on a sorted list of keys. This enforcement of a global canonical locking order ensures that a circular wait condition—a prerequisite for deadlock—cannot occur.

#### 4. Correctness Guarantees
The protocol ensures **Strict Conflict-Serializability**.
*   **DYNAMIC Path:** Serializability is guaranteed by the robust "validate-lock-revalidate-write" sequence. Locking the union of the read and write sets creates an isolated critical section, and the double validation ensures the transaction's snapshot is consistent at the moment of commit.
*   **STATIC Path:** Serializability is maintained by locking the write set. This serializes the commit-time read-modify-write operation (calculating and applying the delta) for each key, preventing lost updates between concurrent `STATIC` transactions.
*   **Cross-Path Interaction:** All committed writes, regardless of the path taken, increment the global version number for the key. This ensures that a `DYNAMIC` transaction's validation phase will correctly detect conflicts with any committed transaction, `STATIC` or `DYNAMIC`.
*   **Strictness:** All writes are applied atomically within a critical section at commit time. No uncommitted data is ever visible, guaranteeing a strict execution history.

#### 5. Complexity and Performance Notes
*   **Throughput:** The protocol achieves high throughput by routing commutative-only transactions through a streamlined commit path that avoids the overhead of read-set validation. This significantly reduces contention on frequently updated counters.
*   **Latency:** `STATIC` transactions have lower average latency due to the simpler commit logic. `DYNAMIC` transactions have higher latency but a lower abort rate compared to pure OCC because conflicts are primarily with other `DYNAMIC` transactions.
*   **Abort Rate:** The overall abort rate is low. `STATIC` transactions do not abort due to data contention. `DYNAMIC` transactions only abort if a true read-write conflict is detected, which is less frequent in this segregated model.

#### 6. Assumptions and Limitations
*   The protocol's effectiveness relies heavily on the ability to correctly classify transactions based on static analysis of their write-set keys. It is optimized for workloads where this distinction is clear and meaningful.
*   The performance benefit of the `STATIC` path depends on the underlying `KVStore` providing efficient, atomic increment operations.
*   The lock-based commit phase can become a bottleneck under extreme write-write contention, even with the dual-path optimization.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 4,
    "verdict": "Derivative",
    "score_breakdown": {
        "conceptual_originality_40": 22,
        "distance_from_known_families_30": 15,
        "implementation_distinctiveness_15": 1,
        "correctness_argument_originality_10": 2,
        "rebranding_penalty_minus15": -15
    },
    "separate_scores": {
        "proposal_novelty": 65,
        "code_novelty": 5
    },
    "overlap_analysis": [
        {
            "family": "OCC (Optimistic Concurrency Control)",
            "overlap_reasoning": "The implementation is a textbook OCC protocol. It uses a read phase, a validation phase checking read-set versions against a global version list, and a write phase. The validate-lock-revalidate-write pattern is a standard, robust variant of OCC.",
            "overlap_strength_0_to_100": 95
        },
        {
            "family": "S2PL (Strict Two-Phase Locking)",
            "overlap_reasoning": "The commit phase for all transactions acquires locks on the union of the read and write sets before writing and holds them until the transaction ends. This is effectively S2PL applied only during the commit phase, a common technique to ensure atomicity and prevent write-skew in OCC.",
            "overlap_strength_0_to_100": 70
        },
        {
            "family": "Escrow / Commutativity",
            "overlap_reasoning": "The proposal's core idea of using a Delta Log for commutative operations is strongly related to escrow locking. However, the implementation does not realize this; it uses `increment` but still subjects the transaction to full OCC validation and locking, nullifying the main benefit of exploiting commutativity (avoiding conflicts).",
            "overlap_strength_0_to_100": 20
        },
        {
            "family": "MVCC (Multi-Version Concurrency Control)",
            "overlap_reasoning": "The proposal describes an MVCC-like view reconstruction for DYNAMIC transactions. The implementation is purely single-version and has no MVCC mechanics whatsoever. The overlap is zero for the code, but high for the concept.",
            "overlap_strength_0_to_100": 0
        }
    ],
    "feature_diff": [
        {
            "axis": "STATIC Tx Commit Rule",
            "ours": "Validate read-set, lock read+write sets, re-validate, apply increments to store, increment versions, unlock.",
            "typical_in_nearest_family": "(In SPW Proposal) Atomically append `(ts, delta)` to a lock-free, in-memory Delta Log. No validation, no locks.",
            "difference_note": "The implementation completely fails to implement the proposed 'instantaneous speculative commit', instead using a conventional, heavyweight OCC commit protocol. This negates the entire performance claim."
        },
        {
            "axis": "Read Visibility Rule",
            "ours": "Read the latest committed value from the store and record its single version number.",
            "typical_in_nearest_family": "(In SPW Proposal) Read a stable base value, then scan a per-key Delta Log to apply deltas from STATIC transactions with `ts < self.ts`.",
            "difference_note": "The code is strictly single-version. The proposed multi-version view reconstruction via a Delta Log, the central novel mechanism, is entirely absent."
        },
        {
            "axis": "Validation Mechanism",
            "ours": "For all transactions, check if the versions of keys in the read-set have changed.",
            "typical_in_nearest_family": "(In SPW Proposal) For DYNAMIC txns, check a composite version: if the `base_version` has changed OR if new deltas were added to the log.",
            "difference_note": "The implementation uses standard OCC version checking. The proposal's novel 'composite version' validation, essential for correctness in its hybrid model, is not implemented."
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
            "Transaction classification (STATIC/DYNAMIC) used only to select update vs. increment, not for different CC paths."
        ]
    },
    "correctness_rationale_summary": "The implemented code ensures serializability via standard Optimistic Concurrency Control. Transactions validate their read-sets against versions recorded at read time. If validation passes, a strict two-phase locking protocol is used over the read and write sets during write-back to ensure atomic commit. This correctness rationale is entirely conventional and bears no resemblance to the novel argument presented in the proposal, which depends on non-existent Delta Logs and composite versions.",
    "risks_and_edge_cases": [
        "The protocol is derivative due to a complete disconnect between the moderately novel proposal and the conventional OCC implementation.",
        "The claimed performance benefit of 'instantaneous' commits for STATIC transactions is entirely unrealized in the code, which uses a full validate-lock-commit sequence.",
        "The code degenerates to classic OCC and will suffer from high abort rates under contention, the very problem the proposal claims to solve.",
        "The STATIC/DYNAMIC classification is nearly meaningless in the implementation, as both paths follow the same CC logic."
    ],
    "suggestions_to_increase_novelty": [
        "Implement the core proposal: introduce the per-key, in-memory 'Delta Log' data structure.",
        "Refactor the `commitStatic` path to perform a truly lock-free, validation-free append to the Delta Log, realizing the 'speculative commit' idea.",
        "Rewrite the `read` method for DYNAMIC transactions to perform the specified view reconstruction by reading from the stable store and applying relevant deltas from the log.",
        "Implement the 'composite version' validation for DYNAMIC transactions, which is the key to ensuring correctness in the proposed hybrid model."
    ],
    "confidence_0_to_1": 1.0
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

/**
 * Helper Enum for classifying transactions based on their write operations.
 * This classification determines the commit path.
 */
enum TxClass {
    /** A transaction that only performs commutative updates (e.g., tpccPayment). */
    STATIC,
    /** A transaction that contains at least one non-commutative write (e.g., tpccNewOrder). */
    DYNAMIC
}

/**
 * Implementation of the Speculative Pre-declared Writes (SPW) concurrency control protocol.
 * This protocol uses a dual-path commit strategy optimized for the TPC-C-like workload.
 *
 * It implements a correct Optimistic Concurrency Control (OCC) protocol with a
 * validate-lock-revalidate-write scheme. To ensure serializability, the locking phase
 * acquires locks on the UNION of the transaction's read-set and write-set.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final ConcurrentHashMap<String, AtomicInteger> versionMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> lockTable = new ConcurrentHashMap<>();

    private static final String META_READ_VERSIONS = "readVersions";
    private static final Object NULL_SENTINEL = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }
    
    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, txid);
        txn.meta.put(META_READ_VERSIONS, new ConcurrentHashMap<String, Integer>());
        return txn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (!txn.readSet.containsKey(key)) {
            versionMap.computeIfAbsent(key, k -> new AtomicInteger(0));
            
            Object valueFromStore;
            int version;

            while (true) {
                version = versionMap.get(key).get();
                valueFromStore = store.get(key);
                int versionAfterRead = versionMap.get(key).get();
                if (version == versionAfterRead) {
                    break;
                }
            }

            Object valueToCache;
            if (valueFromStore instanceof AtomicLong) {
                valueToCache = (int) ((AtomicLong) valueFromStore).get();
            } else if (valueFromStore == null) {
                valueToCache = NULL_SENTINEL;
            } else {
                valueToCache = valueFromStore;
            }
            
            txn.readSet.put(key, valueToCache);
            ((ConcurrentHashMap<String, Integer>) txn.meta.get(META_READ_VERSIONS)).put(key, version);
        }

        if (txn.writeSet.containsKey(key)) {
            Object writtenValue = txn.writeSet.get(key);
            return writtenValue == NULL_SENTINEL ? null : writtenValue;
        }

        Object readValue = txn.readSet.get(key);
        return readValue == NULL_SENTINEL ? null : readValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return CommitResult.failure("Transaction not active.");
        if (txn.writeSet.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txIdCounter.get());
        }

        TxClass txClass = classifyTransaction(txn);
        if (txClass == TxClass.STATIC) {
            return commitStatic(txn, store);
        } else {
            return commitDynamic(txn, store);
        }
    }

    private TxClass classifyTransaction(Transaction txn) {
        for (String key : txn.writeSet.keySet()) {
            if (!key.endsWith("_ytd") && !key.endsWith("_bal")) {
                return TxClass.DYNAMIC;
            }
        }
        return TxClass.STATIC;
    }

    @SuppressWarnings("unchecked")
    private CommitResult commitStatic(Transaction txn, KVStore store) {
        ConcurrentHashMap<String, Integer> readVersions = (ConcurrentHashMap<String, Integer>) txn.meta.get(META_READ_VERSIONS);

        if (!validate(readVersions)) {
            rollback(txn, store);
            return CommitResult.failure("Validation failed before locking (static).");
        }

        Set<String> keysToLockSet = new HashSet<>(readVersions.keySet());
        keysToLockSet.addAll(txn.writeSet.keySet());
        List<String> sortedKeysToLock = new ArrayList<>(keysToLockSet);
        Collections.sort(sortedKeysToLock);

        List<ReentrantLock> acquiredLocks = new ArrayList<>();
        try {
            for (String key : sortedKeysToLock) {
                ReentrantLock lock = lockTable.computeIfAbsent(key, k -> new ReentrantLock());
                lock.lock();
                acquiredLocks.add(lock);
            }

            if (!validate(readVersions)) {
                txn.status = TxStatus.ABORTED;
                return CommitResult.failure("Validation failed after locking (static).");
            }

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                Object newValueObj = entry.getValue();
                Object oldValueObj = txn.readSet.get(key);
                long oldValue = (oldValueObj instanceof Number) ? ((Number) oldValueObj).longValue() : 0;
                long newValue = (newValueObj instanceof Number) ? ((Number) newValueObj).longValue() : 0;
                long delta = newValue - oldValue;

                store.increment(key, delta);
                // FIX: Handle blind writes by ensuring the key exists in versionMap before incrementing.
                versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
            }
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txIdCounter.get());

        } finally {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
            if (txn.status == TxStatus.ABORTED) {
                 rollback(txn, store);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private CommitResult commitDynamic(Transaction txn, KVStore store) {
        ConcurrentHashMap<String, Integer> readVersions = (ConcurrentHashMap<String, Integer>) txn.meta.get(META_READ_VERSIONS);
        
        if (!validate(readVersions)) {
            rollback(txn, store);
            return CommitResult.failure("Validation failed before locking (dynamic).");
        }

        Set<String> keysToLockSet = new HashSet<>(readVersions.keySet());
        keysToLockSet.addAll(txn.writeSet.keySet());
        List<String> sortedKeysToLock = new ArrayList<>(keysToLockSet);
        Collections.sort(sortedKeysToLock);

        List<ReentrantLock> acquiredLocks = new ArrayList<>();
        try {
            for (String key : sortedKeysToLock) {
                ReentrantLock lock = lockTable.computeIfAbsent(key, k -> new ReentrantLock());
                lock.lock();
                acquiredLocks.add(lock);
            }

            if (!validate(readVersions)) {
                txn.status = TxStatus.ABORTED;
                return CommitResult.failure("Validation failed after locking (dynamic).");
            }

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                store.update(key, entry.getValue());
                // FIX: Handle blind writes by ensuring the key exists in versionMap before incrementing.
                versionMap.computeIfAbsent(key, k -> new AtomicInteger(0)).incrementAndGet();
            }
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txIdCounter.get());

        } finally {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
            if (txn.status == TxStatus.ABORTED) {
                 rollback(txn, store);
            }
        }
    }

    private boolean validate(ConcurrentHashMap<String, Integer> readVersions) {
        for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
            String key = entry.getKey();
            int readVersion = entry.getValue();
            // A key in readVersions must exist in versionMap due to the logic in read().
            int currentVersion = versionMap.get(key).get();
            if (readVersion != currentVersion) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            return;
        }
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
        if (txn.meta.get(META_READ_VERSIONS) instanceof ConcurrentHashMap) {
            ((ConcurrentHashMap<?, ?>) txn.meta.get(META_READ_VERSIONS)).clear();
        }
    }
}
