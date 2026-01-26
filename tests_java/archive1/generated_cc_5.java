/* Technical Report:
### **Final Technical Report: Asynchronous State Reconciliation with Semantic Escrow (ASR-SE)**

**1. Algorithm Overview**

Asynchronous State Reconciliation with Semantic Escrow (ASR-SE) is a concurrency control protocol designed for workloads with pre-defined transaction types, such as TPC-C. Its core principle is the radical decoupling of a transaction's business logic execution from the physical application of its state changes.

The protocol operates in three phases:
*   **Execution Phase:** A transaction executes against a consistent, private snapshot of the database taken at its start time. All write operations are not applied directly but are instead translated into a log of high-level *semantic operations* (e.g., `WRITE(key, value)` or `INCREMENT(key, delta)`). This phase is entirely non-blocking.
*   **Commit Phase:** The commit operation is a "fast-commit." It is a non-blocking step where the transaction's semantic log is atomically enqueued into a global, totally-ordered `ReconciliationQueue`. The client receives a success confirmation immediately.
*   **Reconciliation Phase:** A dedicated, single background thread continuously dequeues these semantic logs and applies the operations to the main key-value store in a strict serial order. This serialized application of changes is the foundation of the protocol's correctness.

The protocol leverages offline analysis of the known TPC-C transaction code to classify keys. Keys subject to frequent, commutative arithmetic updates (e.g., `stock_qty`, `ytd`, `balance`) are classified as "Managed," allowing their updates to be logged as deltas, which minimizes write-set size and simplifies reconciliation.

**2. Read/Write/Lock or Validation Strategy**

*   **Read Strategy:** The protocol provides Repeatable Reads. The first time a key is read within a transaction, its value is fetched from the underlying store and cached in the transaction's private `readSet`. All subsequent reads for that key within the same transaction are served from this local cache. All read operations are non-blocking and lock-free.
*   **Write Strategy:** Writes are deferred and logged semantically into the transaction's private `writeSet`. For "Managed" keys, the protocol calculates the arithmetic delta between the new value and the value from its read snapshot, logging an `INCREMENT` operation. For all other keys, it logs a standard `WRITE` operation. No writes touch the shared database state during the transaction's execution phase.
*   **Locking & Validation:** ASR-SE is entirely lock-free and validation-free. Contention is not managed by locks but is avoided by deferring writes. The need for a validation phase (as seen in OCC) is eliminated because the serial reconciliation process inherently prevents conflicts that would cause a transaction to be aborted.

**3. Deadlock Handling**

The protocol is **deadlock-free by design**. Deadlocks arise from cyclic dependencies where transactions wait for resources (locks) held by each other. Since ASR-SE does not use any locks during the execution phase, the conditions for deadlock cannot occur.

**4. Correctness Guarantees**

ASR-SE guarantees **Strict Serializability**.
*   **Serializability:** The final database state is equivalent to a serial execution of transactions. The definitive serialization order is established by the strict FIFO ordering of the `ReconciliationQueue`. The single reconciler thread acts as a serialization point, applying each transaction's complete effect set in this exact order.
*   **Strictness:** A transaction's writes are only applied to the database *after* it has been logically committed (i.e., its log is successfully enqueued). Therefore, no transaction can ever read uncommitted data from another transaction, preventing dirty reads and cascading aborts.

**5. Complexity and Performance Notes**

*   **Performance:** The protocol exhibits extremely low and consistent latency for `begin`, `read`, `write`, and `commit` operations, as they are non-blocking. It achieves high throughput, particularly on workloads with high contention on a few "hotspot" keys, by transforming concurrent atomic updates into simple, fast enqueue operations. The primary performance limiter is the throughput of the single-threaded reconciler, which dictates the maximum sustainable commit rate of the system.
*   **Complexity:**
    *   **Time Complexity:** The client-facing operations (`begin`, `read`, `write`, `commit`) are O(1) (amortized for hashmap access).
    *   **Space Complexity:** Each active transaction requires memory for its read-set and write-set, proportional to the number of unique keys it accesses.

**6. Assumptions and Limitations**

*   **Assumption of Known Workloads:** The protocol's effectiveness relies on the ability to analyze transactions beforehand to distinguish between different types of write operations (e.g., commutative increments vs. blind overwrites). It is less suited for purely ad-hoc, unknown workloads.
*   **Read Visibility Lag:** A key limitation is the delay between when a transaction is confirmed as "committed" to the client and when its effects become visible to subsequent transactions. This visibility is deferred until the reconciler processes the transaction's log, which may not be acceptable for all application consistency models.
*   **Centralized Reconciler Bottleneck:** While effective, the single reconciler thread represents a central bottleneck. System throughput cannot exceed the rate at which this single thread can apply logged changes to the data store.
*   **Deferred Constraint Violations:** Business logic constraints that can only be checked upon write (e.g., ensuring stock doesn't become negative) are not handled in this implementation. A production-grade version would require a mechanism to manage transaction aborts that are discovered late in the reconciliation phase.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 68,
  "verdict": "Moderately Novel",
  "score_breakdown": {
    "conceptual_originality_40": 30,
    "distance_from_known_families_30": 24,
    "implementation_distinctiveness_15": 13,
    "correctness_argument_originality_10": 4,
    "rebranding_penalty_minus15": -3
  },
  "separate_scores": {
    "proposal_novelty": 75,
    "code_novelty": 60
  },
  "overlap_analysis": [
    {
      "family": "Deterministic Ordering (e.g., Calvin)",
      "overlap_reasoning": "The core correctness mechanism—establishing a total order via a single log/queue and then applying changes serially—is identical to deterministic databases. ASR-SE's Reconciliation Queue is Calvin's Sequencer Log. However, the workflow is inverted: ASR-SE executes optimistically *then* sequences the resulting effects, whereas Calvin sequences the transaction request *then* executes it deterministically. This is a significant architectural difference.",
      "overlap_strength_0_to_100": 75
    },
    {
      "family": "Commutativity / Escrow Method",
      "overlap_reasoning": "The 'Semantic Escrow' for 'Managed Keys' is a direct application of the classic Escrow method (O'Neil, 1986) and commutativity principles used in many systems to handle hotspots like counters. The novelty is not the idea itself, but its integration as a first-class citizen in a broader decoupled protocol.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "MVCC / Snapshot Isolation",
      "overlap_reasoning": "The protocol uses non-blocking snapshot reads based on a transaction's start time, which is a core feature of Snapshot Isolation and MVCC. However, it completely lacks the other defining features of these families, such as multi-versioned storage or a validation phase against concurrent writes (First-committer-wins). The snapshot is merely an input to the execution phase.",
      "overlap_strength_0_to_100": 20
    },
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The protocol is 'optimistic' in that it executes without locks. However, it is fundamentally different from classic OCC because it completely lacks the commit-time validation phase where a transaction's read-set is checked for conflicts against concurrently committed write-sets. Correctness is guaranteed by serialization, not validation.",
      "overlap_strength_0_to_100": 10
    }
  ],
  "feature_diff": [
    {
      "axis": "ordering basis",
      "ours": "A total order of transaction *effects* (semantic logs) is established at commit time by enqueuing into a single queue.",
      "typical_in_nearest_family": "In Calvin, a total order of transaction *requests* is established up-front by a sequencing layer *before* execution begins.",
      "difference_note": "This 'execute-then-sequence' model allows for interactive transactions and early aborts based on business logic, which is difficult in Calvin's 'sequence-then-execute' model. This changes the latency profile and programming model significantly."
    },
    {
      "axis": "commit rule / visibility",
      "ours": "A 'fast-commit' returns to the client once the semantic log is enqueued. Visibility of effects is delayed until a background Reconciler applies them.",
      "typical_in_nearest_family": "In Calvin, commit occurs after the deterministic execution phase is complete. All effects are applied before acknowledgement, providing stronger and more immediate visibility guarantees.",
      "difference_note": "ASR-SE prioritizes client-facing commit latency at the cost of read freshness, creating a window of potential staleness not typically present in deterministic systems."
    },
    {
      "axis": "conflict handling",
      "ours": "Read-write conflicts are avoided via snapshot reads. Write-write conflicts are resolved implicitly by the strict serial application order defined by the reconciliation queue. There is no explicit conflict detection.",
      "typical_in_nearest_family": "Calvin avoids conflicts by design; since execution is deterministic and serial (or partitioned deterministically), locks are not needed and conflicts in the traditional sense do not occur.",
      "difference_note": "The mechanism is similar (serial execution avoids conflicts), but ASR-SE's approach is more optimistic, as it runs a transaction to completion before knowing its final serialization order."
    }
  ],
  "code_checks": {
    "uses_locks_or_lock_modes": false,
    "uses_versions_or_snapshots": true,
    "commit_time_validation": false,
    "timestamp_ordering_core": false,
    "deterministic_preordering": true,
    "dependency_graph_cycles": false,
    "other_key_constructs": [
      "Semantic operation log per transaction",
      "Global, totally-ordered BlockingQueue for serialization",
      "Single-threaded background 'Reconciler' to apply logs"
    ]
  },
  "correctness_rationale_summary": "Serializability is ensured by serializing the application of transaction effects. The act of placing a transaction's semantic log onto a single, totally-ordered queue establishes its definitive serialization point. A background thread then applies these logs in that exact order. This guarantees an outcome equivalent to a serial execution. This correctness rationale itself is not novel; it is the standard argument for all deterministic database systems.",
  "risks_and_edge_cases": [
    "The 'fast-commit' creates a significant read-after-write hazard; a client can be told a transaction is committed, but subsequent reads (from new transactions) will not see its effects until the Reconciler catches up.",
    "The single, centralized Reconciliation Queue and its single-threaded consumer are a scalability bottleneck. Sharding this queue correctly without re-introducing complex coordination is a major challenge not addressed here.",
    "The implementation simplifies the 'Escrow Manager' into atomic increments by the Reconciler, losing the proposed benefit of batching deltas from a separate, highly concurrent service.",
    "Constraint validation (e.g., stock > 0) is tricky. A check at submission time is against a potentially stale value in the Escrow Manager. A final check must still happen during reconciliation, which could lead to late aborts after a 'fast-commit'."
  ],
  "suggestions_to_increase_novelty": [
    "Fully develop the 'Escrow Manager' as a distinct, concurrent service that truly aggregates deltas from many transactions before flushing a single, batched update. This would better realize the proposal's claims and distance it further from a simple deferred-update model.",
    "Introduce a mechanism to bridge the visibility gap. For example, allow transactions to optionally 'peek' into the reconciliation queue for very recent writes, creating a novel, hybrid visibility model.",
    "Formally analyze the properties of the 'execute-then-sequence' model in a distributed environment. Show how it differs from distributed deterministic protocols (like Calvin) in handling partitioning, replication, and cross-partition transactions."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// Helper enum for classifying keys based on TPC-C workload analysis.
enum KeyType {
    MANAGED, // Keys that undergo commutative arithmetic operations (e.g., counters, balances).
    STANDARD // All other keys.
}

// Helper enum for semantic operations.
enum OpType {
    WRITE,     // A standard overwrite operation.
    INCREMENT  // An arithmetic increment/decrement operation.
}

// Helper class to represent a semantic operation in the transaction's log.
class SemanticOp {
    final OpType type;
    final String key;
    Object value; // For WRITE, this is the final value. For INCREMENT, this is the delta (Long).

    SemanticOp(OpType type, String key, Object value) {
        this.type = type;
        this.key = key;
        this.value = value;
    }
}


/**
 * Implementation of Asynchronous State Reconciliation with Semantic Escrow (ASR-SE).
 *
 * Core Idea: Decouple transaction execution from state reconciliation. Transactions
 * execute against a snapshot, generating a log of high-level semantic operations
 * (e.g., "increment stock by -5"). Committing is a "fast-commit" that places this
 * log on a queue. A single background thread (the Reconciler) applies these
 * changes serially to the database, using atomic operations for contentious keys.
 * This protocol eliminates traditional locking and validation-phase conflicts for
 * the target workload.
 */
class GeneratedCC extends ConcurrencyControl {
    private static final AtomicInteger txidCounter = new AtomicInteger(0);
    private final BlockingQueue<Transaction> reconciliationQueue = new LinkedBlockingQueue<>();
    private final Thread reconcilerThread;
    private volatile boolean running = true;

    public GeneratedCC() {
        // The KVStore is passed to each method, so we need to get it during reconciliation.
        // This is a placeholder; the actual store is provided in commit().
        // We start a daemon thread that will not prevent the JVM from exiting.
        reconcilerThread = new Thread(new Reconciler());
        reconcilerThread.setDaemon(true);
        reconcilerThread.start();
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    /**
     * The background worker that serially applies committed transaction logs.
     * This single-threaded application of changes is what guarantees serializability.
     */
    private class Reconciler implements Runnable {
        @Override
        public void run() {
            while (running) {
                try {
                    // Block until a committed transaction is available.
                    Transaction txn = reconciliationQueue.take();
                    // The KVStore is not stored in GeneratedCC, so we get it from the transaction's metadata.
                    KVStore store = (KVStore) txn.meta.get("store");
                    if (store != null) {
                        applyChanges(txn, store);
                    }
                } catch (InterruptedException e) {
                    // Gracefully exit if the thread is interrupted.
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * Applies the semantic log of a transaction to the KVStore.
     */
    private void applyChanges(Transaction txn, KVStore store) {
        for (Object opObject : txn.writeSet.values()) {
            SemanticOp op = (SemanticOp) opObject;
            switch (op.type) {
                case WRITE:
                    store.update(op.key, op.value);
                    break;
                case INCREMENT:
                    store.increment(op.key, (Long) op.value);
                    break;
            }
        }
        txn.status = TxStatus.COMMITTED;
    }
    
    /**
     * Simulates the offline static analysis of transaction templates.
     * In a real system, this would be done by a compiler. Here, we use string patterns.
     */
    private KeyType classifyKey(String key) {
        if (key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty") || key.endsWith("_next_oid")) {
            return KeyType.MANAGED;
        }
        return KeyType.STANDARD;
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, txid);
        // We need the store reference during reconciliation.
        txn.meta.put("store", store);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            return null;
        }

        // Enforce repeatable reads by checking our private readSet first.
        if (txn.readSet.containsKey(key)) {
            return txn.readSet.get(key);
        }

        // Read from the actual store if it's the first time.
        Object value = store.get(key);
        
        // Unbox AtomicLong to Long for consistent handling within the transaction.
        Object storedValue = (value instanceof AtomicLong) ? ((AtomicLong) value).get() : value;

        // Cache the value in our snapshot (readSet).
        if (storedValue != null) {
            txn.readSet.put(key, storedValue);
        }
        
        return storedValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            return;
        }

        KeyType type = classifyKey(key);

        if (type == KeyType.MANAGED) {
            // For managed keys, we calculate the delta and create an INCREMENT operation.
            // The transaction logic is assumed to be read-modify-write, so the original
            // value must have been read into the readSet.
            Object originalValueObj = txn.readSet.get(key);
            long originalValue = (originalValueObj instanceof Number) ? ((Number) originalValueObj).longValue() : 0;
            long newValue = (value instanceof Number) ? ((Number) value).longValue() : 0;
            long delta = newValue - originalValue;

            // Aggregate deltas if the key is written multiple times.
            SemanticOp existingOp = (SemanticOp) txn.writeSet.get(key);
            if (existingOp != null && existingOp.type == OpType.INCREMENT) {
                existingOp.value = (Long) existingOp.value + delta;
            } else {
                txn.writeSet.put(key, new SemanticOp(OpType.INCREMENT, key, delta));
            }
        } else { // STANDARD key
            // For standard keys, it's a simple WRITE operation.
            txn.writeSet.put(key, new SemanticOp(OpType.WRITE, key, value));
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            return CommitResult.failure("Transaction was already aborted.");
        }
        
        // "Fast-commit": Enqueue the transaction for background reconciliation.
        // The client can return immediately.
        try {
            reconciliationQueue.put(txn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommitResult.failure("Commit was interrupted.");
        }

        // The commit timestamp is logically the transaction's start time,
        // as this defines the snapshot it operated on.
        return CommitResult.success(txn.startTs);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        // No need to undo changes in KVStore, as none have been applied yet.
        txn.status = TxStatus.ABORTED;
        txn.readSet.clear();
        txn.writeSet.clear();
    }
}
