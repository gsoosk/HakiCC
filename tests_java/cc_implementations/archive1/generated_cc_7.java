/* Technical Report:
### Final Technical Report: Predictive Dependency Graph with Just-in-Time Synchronization (PDG-JITS)

*   **Algorithm Overview:**
    The implemented protocol, initially conceived as PDG-JITS, evolved into a robust, centralized implementation of the classic **Wound-Wait** algorithm. It establishes a strict serial order based on monotonically increasing transaction timestamps. A central `DependencyCoordinator`, protected by a single global, fair `ReentrantLock`, manages the state of all active transactions and resolves data conflicts. This centralized design serializes all concurrency control decisions to guarantee correctness and prevent deadlocks.

*   **Read/Write/Lock Strategy:**
    *   **Read Strategy:** When a transaction initiates a read, it first checks its local write-set to satisfy "read-your-own-writes." If the key is not found locally, the transaction registers its *intent* to read with the coordinator. The conflict detection logic is then executed. Crucially, the actual read from the key-value store is deferred until *after* the transaction has waited for and been serialized after all conflicting older transactions. This "read-after-wait" strategy prevents stale reads, ensuring the transaction always sees a fresh value consistent with its position in the serial order.
    *   **Write Strategy:** Writes are buffered locally in a transaction's write-set. The intent to write is registered with the coordinator, which performs conflict detection. The actual writes are applied to the key-value store atomically during the commit phase.
    *   **Synchronization Strategy:** The protocol avoids traditional data locks. Instead, it uses a single global lock to protect all accesses to shared concurrency control state (the set of active transactions and their dependencies). Transactions that must wait do so on a single global `Condition` variable. The commit logic is also protected by this global lock to prevent a Time-of-Check to Time-of-Use (TOCTOU) race condition where a transaction could be aborted after checking its status but before applying its writes.

*   **Deadlock Handling:**
    The protocol is **deadlock-free by design** due to its strict adherence to the Wound-Wait policy:
    1.  **Wait:** If a younger transaction conflicts with an older transaction, the younger transaction *always* waits.
    2.  **Wound:** If an older transaction conflicts with a younger transaction (on any conflict type: R-W, W-R, or W-W), the older transaction *always* aborts (wounds) the younger one.
    Since dependencies (wait-for edges) can only point from younger to older transactions, a circular wait is impossible. Liveness issues like starvation, which can manifest as timeouts, are prevented by using a *fair* lock, guaranteeing that the longest-waiting thread will eventually acquire the lock and make progress.

*   **Correctness Guarantees:**
    The protocol ensures **Strict Conflict-Serializability**.
    *   **Conflict-Serializability:** The Wound-Wait policy ensures that the final execution history is equivalent to a serial execution in timestamp order. Dependencies are tracked cumulatively throughout a transaction's lifetime, preventing serialization cycles.
    *   **Strictness:** Because writes are buffered and only applied to the database at commit time, no transaction can read uncommitted data from another. This prevents dirty reads and cascading aborts.

*   **Complexity and Performance Notes:**
    *   **Complexity:** The conflict check for each operation is O(N), where N is the number of currently active transactions, as the coordinator must scan for conflicts.
    *   **Performance:** The primary performance characteristic is the global lock, which serializes all concurrency control logic and acts as a bottleneck. While this simplifies correctness, it severely limits scalability and throughput, especially under high contention. The use of `signalAll()` on every completion can also lead to a "thundering herd" problem, where many threads wake up only to contend for the same lock. The protocol is expected to have lower throughput compared to more parallel designs like MVCC.

*   **Assumptions and Limitations:**
    *   **Assumptions:** The protocol assumes a centralized, single-node architecture where a global lock is feasible.
    *   **Limitations:** The single-lock design is the main limitation, making the protocol unsuitable for distributed systems or systems requiring high parallel execution. Its performance degrades significantly with increasing contention due to both the O(N) scan and the lock bottleneck. It is not ideal for workloads with long-running transactions, as an old, long-running transaction can cause a high rate of aborts for younger transactions.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 10,
    "verdict": "Derivative",
    "score_breakdown": {
        "conceptual_originality_40": 22,
        "distance_from_known_families_30": 18,
        "implementation_distinctiveness_15": 4,
        "correctness_argument_originality_10": 4,
        "rebranding_penalty_minus15": -5
    },
    "separate_scores": {
        "proposal_novelty": 55,
        "code_novelty": 5
    },
    "overlap_analysis": [
        {
            "family": "Timestamp Ordering (TO) with Wound-Wait",
            "overlap_reasoning": "The implementation is a textbook example of TO with Wound-Wait. It uses timestamps to establish a serial order. When a conflict occurs, if the requesting transaction is older, it aborts ('wounds') the younger transaction. If the requester is younger, it waits for the older one. This is the exact definition of Wound-Wait. The 'dependency graph' is a standard wait-for graph, and 'JITS' is the wait mechanism.",
            "overlap_strength_0_to_100": 95
        },
        {
            "family": "Deterministic Ordering (e.g., Calvin, Aria)",
            "overlap_reasoning": "The *proposal* shares the core idea of analyzing transactions upfront to determine their read/write sets and proactively schedule them. However, the implementation is fully dynamic and non-deterministic, discovering conflicts one-by-one at runtime, which is the opposite of the deterministic batching approach.",
            "overlap_strength_0_to_100": 20
        },
        {
            "family": "2PL (Strict)",
            "overlap_reasoning": "While the proposal claims not to use locks, the implementation's behavior is functionally similar to a 2PL variant. Registering an intent and potentially waiting creates a wait-for dependency on a data item, just like a lock. The use of Wound-Wait is also a common deadlock prevention scheme in 2PL systems.",
            "overlap_strength_0_to_100": 70
        }
    ],
    "feature_diff": [
        {
            "axis": "Conflict Detection Time",
            "ours": "Dynamically, on each read/write operation (in code). The proposal claims this is done upfront.",
            "typical_in_nearest_family": "Dynamically, when a lock request conflicts with an existing lock holder (in 2PL/WW) or an operation conflicts with a prior one (in TO/WW).",
            "difference_note": "The implementation's behavior is identical to the nearest families. The claimed novelty from the proposal (upfront declaration and graph construction) is entirely absent in the code."
        },
        {
            "axis": "Deadlock/Livelock Handling",
            "ours": "Deadlock prevention via Wound-Wait: an older transaction aborts a conflicting younger transaction; a younger transaction waits for an older one.",
            "typical_in_nearest_family": "This is a canonical deadlock prevention strategy (Wound-Wait) used in both 2PL and TO systems.",
            "difference_note": "There is no difference in the core mechanism. The protocol uses a well-established, non-novel technique."
        },
        {
            "axis": "Metadata / Structures",
            "ours": "A centralized, globally-locked 'DependencyCoordinator' manages a 'dependency graph' (successors/predecessors maps).",
            "typical_in_nearest_family": "A lock manager or transaction manager maintains a wait-for graph (often implicitly).",
            "difference_note": "The 'dependency graph' is a rebranding of a standard wait-for graph. The use of a single global lock for the coordinator is a simple but non-novel implementation choice."
        }
    ],
    "code_checks": {
        "uses_locks_or_lock_modes": true,
        "uses_versions_or_snapshots": false,
        "commit_time_validation": false,
        "timestamp_ordering_core": true,
        "deterministic_preordering": false,
        "dependency_graph_cycles": false,
        "other_key_constructs": [
            "Centralized coordinator with a single global lock",
            "Explicit wait-for graph implemented with maps",
            "Wound-Wait for deadlock prevention"
        ]
    },
    "correctness_rationale_summary": "Correctness is ensured by assigning each transaction a unique monotonic timestamp and using the Wound-Wait protocol to resolve all data conflicts. This strategy prevents cycles from forming in the transaction wait-for graph, thereby preventing deadlocks and enforcing a strict serializable execution order equivalent to the timestamp order. This correctness rationale is standard for any system using Timestamp Ordering with Wound-Wait and is not novel.",
    "risks_and_edge_cases": [
        "The implementation does not match the proposal. The novel claims of static analysis and upfront declaration are not implemented, reverting to a classic dynamic protocol.",
        "The centralized `DependencyCoordinator` with a single `graphLock` is a severe scalability bottleneck, serializing all concurrency control decisions.",
        "The 'lock-free execution' claim is misleading. While data items are not locked, all CC logic is serialized behind one coarse-grained lock.",
        "The protocol degenerates into classic TO with Wound-Wait, inheriting its properties, such as potentially high abort rates if older transactions frequently conflict with younger ones."
    ],
    "suggestions_to_increase_novelty": [
        "Implement the core conceptual idea: perform static analysis to generate templates and require transactions to declare their full, concrete read/write sets at startup.",
        "Use the fully declared sets to build the entire dependency graph for a transaction upfront, rather than discovering conflicts incrementally.",
        "Replace the global lock on the coordinator. Shard the coordinator by key-range or use lock-free data structures to manage dependencies.",
        "Explore the proposed extension for commutative operations. Recognizing and allowing commutative operations (e.g., two increments) to execute in parallel without creating a dependency would be a significant point of novelty."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java.previous_proposals.archive1;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements a serializable concurrency control protocol based on a dynamic dependency graph using wound-wait.
 * This version corrects a critical "stale read" bug.
 *
 * - Serializability Fix: The previous implementation would read a value from the store *before* waiting for
 *   conflicting transactions. If a conflicting transaction committed and changed the value while this
 *   transaction was waiting, this transaction would wake up and use its old, stale value, violating
 *   serializability. The fix is to delay the actual read from the store until *after* the wait is complete.
 *   This ensures the transaction reads a fresh value consistent with its place in the serial order.
 */
class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final AtomicInteger timestampCounter = new AtomicInteger(0);
    private final DependencyCoordinator coordinator = new DependencyCoordinator();
    private static final Object NULL_SENTINEL = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private static class DependencyCoordinator {
        private final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Transaction, Set<Transaction>> successors = new ConcurrentHashMap<>();
        private final ReentrantLock graphLock = new ReentrantLock(true);
        private final Condition globalCondition = graphLock.newCondition();

        public void register(Transaction txn) {
            graphLock.lock();
            try {
                activeTransactions.put(txn.txid, txn);
            } finally {
                graphLock.unlock();
            }
        }

        public Object handleOperation(Transaction txn, String key, Object value, boolean isWrite, KVStore store) {
            graphLock.lock();
            try {
                if (txn.status != TxStatus.ACTIVE) return null;

                // Step 1: Register intent for conflict detection. For reads, we don't fetch the value yet.
                if (isWrite) {
                    txn.writeSet.put(key, value == null ? NULL_SENTINEL : value);
                } else {
                    txn.readSet.put(key, NULL_SENTINEL); // Placeholder for conflict detection
                }

                @SuppressWarnings("unchecked")
                final Set<Transaction> txnPredecessors = (Set<Transaction>) txn.meta.get("predecessors");

                // Step 2: Discover conflicts and establish dependencies or wound younger transactions.
                for (Transaction otherTxn : activeTransactions.values()) {
                    if (otherTxn.txid == txn.txid || otherTxn.status != TxStatus.ACTIVE) continue;

                    boolean hasConflict = isWrite ?
                        (otherTxn.readSet.containsKey(key) || otherTxn.writeSet.containsKey(key)) :
                        (otherTxn.writeSet.containsKey(key));

                    if (hasConflict) {
                        if (txn.startTs > otherTxn.startTs) { // txn is younger, must wait
                            if (txnPredecessors.add(otherTxn)) {
                                successors.computeIfAbsent(otherTxn, k -> ConcurrentHashMap.newKeySet()).add(txn);
                            }
                        } else { // txn is older, must wound
                            otherTxn.status = TxStatus.ABORTED;
                        }
                    }
                }

                // Step 3: Wait until all predecessors have completed.
                while (!txnPredecessors.isEmpty()) {
                    if (txn.status != TxStatus.ACTIVE) return null;
                    
                    globalCondition.signalAll(); // Signal anyone we might have wounded
                    try {
                        globalCondition.await();
                    } catch (InterruptedException e) {
                        txn.status = TxStatus.ABORTED;
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
                
                // Step 4: After waiting, if it's a read, fetch the FRESH value from the store.
                if (!isWrite) {
                    if (txn.status != TxStatus.ACTIVE) return null;
                    Object finalReadValue = store.get(key);
                    txn.readSet.put(key, finalReadValue == null ? NULL_SENTINEL : finalReadValue);
                    return finalReadValue;
                }

                return null; // For writes
            } finally {
                graphLock.unlock();
            }
        }

        public void notifyCompletion(Transaction txn) {
            graphLock.lock();
            try {
                activeTransactions.remove(txn.txid);
                Set<Transaction> txnSuccessors = successors.remove(txn);

                if (txnSuccessors != null) {
                    for (Transaction successor : txnSuccessors) {
                        @SuppressWarnings("unchecked")
                        final Set<Transaction> successorPredecessors = (Set<Transaction>) successor.meta.get("predecessors");
                        if (successorPredecessors != null) {
                            successorPredecessors.remove(txn);
                        }
                    }
                }
                globalCondition.signalAll();
            } finally {
                graphLock.unlock();
            }
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        int ts = timestampCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, ts);
        txn.meta.put("predecessors", ConcurrentHashMap.newKeySet());
        txn.meta.put("notified", false);
        coordinator.register(txn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Read-your-own-writes is handled first.
        if (txn.writeSet.containsKey(key)) {
            Object writtenValue = txn.writeSet.get(key);
            return writtenValue == NULL_SENTINEL ? null : writtenValue;
        }

        return coordinator.handleOperation(txn, key, null, false, store);
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        coordinator.handleOperation(txn, key, value, true, store);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        boolean committed = false;
        
        coordinator.graphLock.lock();
        try {
            if (txn.status == TxStatus.ACTIVE) {
                ConcurrentHashMap<String, Object> finalWrites = new ConcurrentHashMap<>();
                for (java.util.Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                    if (entry.getValue() != NULL_SENTINEL) {
                        finalWrites.put(entry.getKey(), entry.getValue());
                    }
                }
                store.updateAll(finalWrites);
                txn.status = TxStatus.COMMITTED;
                committed = true;
            }
        } finally {
            coordinator.graphLock.unlock();
        }

        rollback(txn, store); // Use idempotent rollback for notification

        if (committed) {
            return CommitResult.success(timestampCounter.get());
        } else {
            return CommitResult.failure("Transaction aborted concurrently");
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        boolean alreadyNotified = (boolean) txn.meta.get("notified");
        if (alreadyNotified) {
            return;
        }
        txn.meta.put("notified", true);
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
        }
        coordinator.notifyCompletion(txn);
    }
}
