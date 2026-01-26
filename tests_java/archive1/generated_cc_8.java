/* Technical Report:
### **Technical Report: Pre-Analyzed Commutative Execution (PACE) Protocol**

*   **Algorithm Overview**
    The implemented protocol, Pre-Analyzed Commutative Execution (PACE), is an optimistic concurrency control (OCC) mechanism optimized for workloads with pre-defined transaction types. It combines a standard OCC validation phase with a semantic write-phase optimization. During an offline analysis phase (simulated with regular expressions), write operations are classified as either `COMMUTATIVE` (e.g., increments, decrements) or `OVERWRITE`. During transaction execution, writes are buffered locally as semantic operations (`ATOMIC_ADD` or `SET`). At commit time, the protocol performs a validation check and, if successful, applies the buffered operations, using efficient, lock-free atomic instructions for commutative writes.

*   **Read/Write/Validation Strategy**
    *   **Read Strategy:** Reads are optimistic and non-blocking. When a transaction reads a key, it first records the key's current version from a global version map and then reads the data value from the store. This specific ordering is crucial to prevent race conditions with concurrent commits. Read-your-own-writes are supported by checking the transaction's private write set before accessing the global store.
    *   **Write Strategy:** Writes are deferred. They are recorded in a transaction-local write set and buffered in a private operation log. The log stores the semantic intent of the write (e.g., `ATOMIC_ADD` with a delta, or `SET` with a final value). Writes are only applied to the key-value store upon a successful commit.
    *   **Validation Strategy:** The protocol uses forward optimistic validation. The entire commit process (validation and write-back) is serialized under a single global lock to ensure atomicity. During validation, the version of each key in the transaction's read set (recorded at read time) is compared against the key's current version in the global version map. If any key has been modified by another transaction that has since committed (i.e., the current version is greater than the read version), a read-write conflict is detected, and the transaction aborts.

*   **Deadlock Handling**
    The PACE protocol is **deadlock-free**. As an optimistic protocol, it does not acquire data locks during the execution phase. Transactions run in isolation and only check for conflicts at commit time. Resource contention is resolved through transaction aborts rather than blocking, thereby preventing the circular-wait conditions necessary for deadlocks to occur.

*   **Correctness Guarantees**
    The protocol guarantees **Strict Conflict-Serializability**.
    *   **Conflict-Serializability:** The validation mechanism ensures that any transaction whose read set intersects with the write set of a concurrently committed transaction will be aborted. This correctly resolves all read-write and write-read conflicts. The global commit lock establishes a total serial order of committed transactions equivalent to their commit-time sequence.
    *   **Strictness:** All writes are buffered and applied to the database atomically only after the transaction has successfully passed validation. This ensures that uncommitted data is never visible to other transactions, thus preventing dirty reads.

*   **Complexity and Performance Notes**
    *   **Complexity:** Read and write operations are O(1). The commit operation's complexity is proportional to the size of the transaction's read set (for validation) and write set (for applying changes). The single global commit lock is the primary scalability bottleneck, though its hold time is minimized.
    *   **Performance:** The protocol excels in workloads with high contention on keys updated via commutative arithmetic (like counters or account balances in TPC-C). By applying these changes with a single atomic `increment` operation instead of a read-modify-write cycle under lock, it reduces the time the global commit lock is held, improving throughput. For generic workloads without such hotspots, its performance is comparable to a standard OCC implementation. High data contention leads to increased abort rates, which is characteristic of optimistic schemes.

*   **Assumptions and Limitations**
    *   **Assumptions:** The protocol's performance benefit relies on the underlying key-value store providing a highly efficient, hardware-level atomic increment/decrement operation.
    *   **Limitations:** The static analysis of transaction types is currently hardcoded via regular expressions, limiting its applicability to other workloads without modification. The single global commit lock can limit scalability on systems with a very high number of cores and high commit rates. Like all optimistic protocols, it may suffer from high abort rates and transaction starvation under extreme data contention.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 28,
    "verdict": "Minor Variation",
    "score_breakdown": {
        "conceptual_originality_40": 12,
        "distance_from_known_families_30": 6,
        "implementation_distinctiveness_15": 2,
        "correctness_argument_originality_10": 8,
        "rebranding_penalty_minus15": -5
    },
    "separate_scores": {
        "proposal_novelty": 65,
        "code_novelty": 15
    },
    "overlap_analysis": [
        {
            "family": "OCC (Optimistic Concurrency Control)",
            "overlap_reasoning": "The implementation is a textbook OCC protocol. It uses a read-set, write-set, and a commit-time validation phase where read versions are checked against a global version map. The entire commit phase (validation + write) is serialized by a single global lock, a common but basic OCC implementation strategy. The proposal's core ideas are layered on top of this fundamental OCC structure.",
            "overlap_strength_0_to_100": 90
        },
        {
            "family": "Commutativity / Escrow Locking",
            "overlap_reasoning": "The conceptual proposal heavily relies on identifying and separating commutative operations, a well-known technique for reducing contention on hotspots. Systems like Calvin and Deuteronomy, and concepts like CRDTs, use this principle. PACE's innovation is applying this idea to relax the validation phase of an OCC protocol, rather than using it for scheduling or pessimistic locking.",
            "overlap_strength_0_to_100": 60
        },
        {
            "family": "Silo / TicToc",
            "overlap_reasoning": "Like these protocols, PACE is an optimistic, single-version system designed for high performance. However, its implemented commit mechanism (a single global lock) is far simpler and less scalable than the fine-grained, lock-free validation and commit techniques used in Silo (epochs and TID validation) or TicToc (data-driven timestamp adjustment).",
            "overlap_strength_0_to_100": 30
        }
    ],
    "feature_diff": [
        {
            "axis": "Commit-time Validation Rule",
            "ours": "Proposal: Validate a transaction's read-set ONLY against committed OVERWRITE operations. Code: Validates read-set against ALL committed operations (both OVERWRITE and COMMUTATIVE).",
            "typical_in_nearest_family": "OCC: Validate the transaction's read-set against the write-sets of all transactions that committed between this transaction's start and validation phases.",
            "difference_note": "The proposal's primary claim to novelty is this selective validation rule. However, the code fails to implement it, instead using a standard OCC validation rule where any concurrent write to a read key causes an abort. This discrepancy is the single biggest weakness of the submission."
        },
        {
            "axis": "Write Application",
            "ours": "Commutative writes are applied as deltas using atomic hardware instructions. Overwrites are applied as direct value replacements.",
            "typical_in_nearest_family": "OCC: All writes are typically buffered as final values and applied by overwriting data in the store during a critical section (the write phase).",
            "difference_note": "This is a valid optimization of the write phase, leveraging operation semantics. While not a new CC mechanism in itself, it's a practical enhancement. The novelty is limited because it only affects the write phase, not the core conflict resolution logic (validation)."
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
            "Static analysis patterns (regex)",
            "Per-transaction operation log (`op_log`) storing semantic operations ('ATOMIC_ADD', 'SET')",
            "Global version map (`keyVersionMap`) for OCC validation"
        ]
    },
    "correctness_rationale_summary": "The proposal's correctness argument is novel: it claims serializability is maintained by ignoring read-write conflicts on commutative updates, relying on their order-insensitivity. The total order is enforced by a sequencer. This is a departure from standard OCC proofs. However, the implementation abandons this rationale and ensures correctness via traditional, non-novel OCC validation, where a transaction's read-set is validated against all concurrent writes. The code is correct, but for a trivial reason that undermines the proposal's core idea.",
    "risks_and_edge_cases": [
        "The implementation's global `commitLock` serializes all commits, creating a severe scalability bottleneck that negates the parallelism gained from atomic writes.",
        "There is a critical disconnect between the proposal (selective validation) and the code (standard validation). The code will cause unnecessary aborts on read-write conflicts involving only commutative operations, which the proposal was designed to avoid.",
        "The static analysis is brittle; any transaction type not matching the pre-defined regex patterns will default to `OVERWRITE`, potentially leading to poor performance if new, commutative workloads are added without updating the analysis."
    ],
    "suggestions_to_increase_novelty": [
        "Implement the selective validation as described in the proposal. This requires separating version tracking for overwrites from other updates (e.g., use a `LastOverwriteVersionMap`) and modifying the validation loop to only check against that map.",
        "Eliminate the global `commitLock`. The validation and write phase must be made more concurrent, for example by using per-key locks or lock-free `compare-and-swap` techniques during the write phase, which is characteristic of more advanced OCC protocols.",
        "Generalize the static analysis beyond simple arithmetic to handle a richer set of semantic operations (e.g., set unions, list appends) and demonstrate how the validation rules can be extended for them."
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
import java.util.regex.Pattern;

// --- Helper Enums and Classes for PACE Protocol ---

/**
 * Defines the type of a buffered operation in the PACE protocol.
 */
enum PaceOperationType {
    ATOMIC_ADD,
    SET
}

/**
 * Represents a single buffered operation within a transaction's op_log.
 */
class PaceOperation {
    public final PaceOperationType type;
    public final String key;
    public final Object value; // Represents delta for ATOMIC_ADD, final value for SET

    public PaceOperation(PaceOperationType type, String key, Object value) {
        this.type = type;
        this.key = key;
        this.value = value;
    }
}

/**
 * Defines the semantic type of a key based on static analysis of the TPC-C workload.
 */
enum KeyType {
    COMMUTATIVE,
    INSERT_ONLY,
    OVERWRITE
}


/**
 * Pre-Analyzed Commutative Execution (PACE) Concurrency Control Protocol.
 * This version implements a correct, strict OCC validation phase to ensure serializability.
 */
class GeneratedCC extends ConcurrencyControl {

    // --- Global State for the PACE Protocol ---

    private static final AtomicInteger transactionIdCounter = new AtomicInteger(0);
    private static final AtomicInteger globalTimestamp = new AtomicInteger(0);
    private static final ReentrantLock commitLock = new ReentrantLock();
    private static final ConcurrentHashMap<String, AtomicInteger> keyVersionMap = new ConcurrentHashMap<>();
    private static final Object NULL_SENTINEL = new Object();

    // --- Static Analysis (Pre-computed) ---

    private static final Pattern COMMUTATIVE_PATTERN = Pattern.compile("^(w_\\d+_ytd|d_\\d+_\\d+_ytd|d_\\d+_\\d+_next_oid|c_\\d+_\\d+_\\d+_bal|c_\\d+_\\d+_\\d+_ytd|s_\\d+_\\d+_qty)$");
    private static final Pattern INSERT_ONLY_PATTERN = Pattern.compile("^o_\\d+_\\d+_\\d+_total$");

    private static KeyType getOperationType(String key) {
        if (COMMUTATIVE_PATTERN.matcher(key).matches()) {
            return KeyType.COMMUTATIVE;
        }
        if (INSERT_ONLY_PATTERN.matcher(key).matches()) {
            return KeyType.INSERT_ONLY;
        }
        return KeyType.OVERWRITE;
    }

    // --- Metadata Keys for Transaction.meta ---

    private static final String META_OP_LOG = "op_log";
    private static final String META_READ_VERSIONS = "read_versions";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = transactionIdCounter.incrementAndGet();
        int startTs = globalTimestamp.incrementAndGet();
        Transaction txn = new Transaction(txid, startTs);

        txn.meta.put(META_OP_LOG, new ArrayList<PaceOperation>());
        txn.meta.put(META_READ_VERSIONS, new ConcurrentHashMap<String, Integer>());

        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        if (txn.writeSet.containsKey(key)) {
            Object writtenValue = txn.writeSet.get(key);
            return (writtenValue == NULL_SENTINEL) ? null : writtenValue;
        }

        // FIX: Read version FIRST to prevent race condition with concurrent commits.
        // This ensures that if a commit happens between reading the version and the data,
        // the validation will correctly fail.
        @SuppressWarnings("unchecked")
        Map<String, Integer> readVersions = (Map<String, Integer>) txn.meta.get(META_READ_VERSIONS);
        AtomicInteger version = keyVersionMap.get(key);
        readVersions.put(key, version != null ? version.get() : 0);

        // Read data SECOND.
        Object value = store.get(key);
        if (value instanceof AtomicLong) {
            value = ((AtomicLong) value).get();
        }

        Object valueToStore = (value == null) ? NULL_SENTINEL : value;
        txn.readSet.put(key, valueToStore);

        return value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return;
        }

        Object valueToStore = (value == null) ? NULL_SENTINEL : value;
        txn.writeSet.put(key, valueToStore);

        KeyType type = getOperationType(key);
        @SuppressWarnings("unchecked")
        List<PaceOperation> opLog = (List<PaceOperation>) txn.meta.get(META_OP_LOG);

        if (type == KeyType.COMMUTATIVE) {
            Object oldValueObj = txn.readSet.get(key);
            
            long oldValue = 0;
            if (oldValueObj != null && oldValueObj != NULL_SENTINEL) {
                oldValue = ((Number) oldValueObj).longValue();
            }

            long newValue = ((Number) value).longValue();
            long delta = newValue - oldValue;
            opLog.add(new PaceOperation(PaceOperationType.ATOMIC_ADD, key, delta));
        } else {
            opLog.add(new PaceOperation(PaceOperationType.SET, key, value));
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        @SuppressWarnings("unchecked")
        List<PaceOperation> opLog = (List<PaceOperation>) txn.meta.get(META_OP_LOG);

        commitLock.lock();
        try {
            // --- 1. Validation Phase (Strict OCC) ---
            @SuppressWarnings("unchecked")
            Map<String, Integer> readVersions = (Map<String, Integer>) txn.meta.get(META_READ_VERSIONS);

            for (Map.Entry<String, Integer> entry : readVersions.entrySet()) {
                String key = entry.getKey();
                int readVersion = entry.getValue();

                AtomicInteger currentVersion = keyVersionMap.get(key);
                int currentVersionVal = (currentVersion != null) ? currentVersion.get() : 0;

                if (currentVersionVal > readVersion) {
                    txn.status = TxStatus.ABORTED;
                    return CommitResult.failure("Validation failed on key: " + key);
                }
            }

            // --- 2. Write Phase ---
            int commitTs = globalTimestamp.incrementAndGet();

            for (PaceOperation op : opLog) {
                switch (op.type) {
                    case ATOMIC_ADD:
                        store.increment(op.key, (long) op.value);
                        break;
                    case SET:
                        store.update(op.key, op.value);
                        break;
                }
            }
            
            // --- 3. Version Update Phase ---
            for (PaceOperation op : opLog) {
                 keyVersionMap.computeIfAbsent(op.key, k -> new AtomicInteger(0)).set(commitTs);
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
        }
        txn.readSet.clear();
        txn.writeSet.clear();
        txn.meta.clear();
    }
}
