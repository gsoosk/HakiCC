/* Technical Report:
### Final Report: Hierarchical Speculative Locking (HSL)

**1. Algorithm Overview**

Hierarchical Speculative Locking (HSL) is a hybrid concurrency control protocol specifically optimized for workloads where data access can be mapped to coarse-grained logical entities. The protocol bifurcates transaction execution into two phases: a highly concurrent, non-blocking speculative phase, and a pessimistic, serialized commit phase.

1.  **Entity Mapping:** A key-to-entity mapping function groups granular keys (e.g., `i_123_name`, `i_123_status`) into a single logical entity (e.g., `item_123`). All concurrency control operates at this entity level.
2.  **Speculative Execution:** Transactions execute optimistically. They register their "intent" to access an entity in a global map but do not acquire any locks. Reads and writes proceed without blocking, and writes are buffered locally.
3.  **Pessimistic Commit with Proactive Invalidation:** Before a writer transaction can commit, it must acquire exclusive locks on all entities it touched. Once locks are held, it applies its writes and then *proactively aborts* all other concurrent transactions that were speculating on the same entities. This "winner-takes-all" approach resolves conflicts by invalidating losers rather than relying on them to self-validate.

**2. Read/Write/Lock or Validation Strategy**

*   **Read/Write Strategy:** During the speculative phase, reads and writes are non-blocking. Upon each operation, the transaction maps the key to an entity, registers its ID as a "speculator" for that entity, and adds the entity to its local `touchedEntities` set. Reads are served from the KV store (or the transaction's local write-set), and writes are buffered locally. Transactions check their status before each operation and halt if they have been remotely aborted.

*   **Locking and Validation Strategy:** The commit phase is serialized by a single global lock to ensure atomicity.
    *   **Read-Only Transactions:** Have a fast path; they acquire the global commit lock, check they haven't been aborted, and commit immediately.
    *   **Writer Transactions:** After acquiring the global commit lock, they acquire exclusive, fine-grained locks for each entity they touched. Once all entity locks are held, they apply writes and then perform the proactive invalidation step. The combination of a global commit lock and fine-grained entity locks ensures that the invalidation logic is correct and does not suffer from race conditions.

**3. Deadlock Handling**

The protocol is **deadlock-free**. Deadlocks that could arise from acquiring multiple fine-grained entity locks are prevented by enforcing a **globally consistent lock acquisition order**. All transactions sort their list of touched entities lexicographically before attempting to acquire the locks, thus breaking any potential cyclic dependencies.

**4. Correctness Guarantees**

HSL guarantees **Strict Serializability**.
*   **Serializability:** The serialization point for all transactions is the acquisition of the global commit lock. Since only one transaction can be in this critical section at a time, a total serial order is established. The proactive invalidation mechanism ensures that no transaction can commit based on a stale state; if the state it read was changed by a concurrent transaction T1, T1 would have aborted it during its own commit phase before the current transaction could reach its commit decision.
*   **Strictness:** Writes are only applied to the database after all necessary locks are held and the transaction is guaranteed to commit. This prevents any transaction from reading uncommitted data.

**5. Complexity and Performance Notes**

*   **Complexity:** The implementation complexity is moderate. It requires a robust key-to-entity mapping function and careful management of global state (speculator map, active transactions). The logic is simpler than a full SSI implementation as it avoids complex read-set validation.
*   **Performance:** HSL shows exceptional performance on its target workload (AuctionMark) where transactions are well-scoped to entities. The non-blocking speculative phase allows for high parallelism. The main performance bottleneck is the global commit lock, which serializes all commits. However, for workloads with short transactions and high contention, the cost of this lock is outweighed by the benefit of avoiding numerous aborts from optimistic validation failures. The coarse-grained entity locking reduces metadata overhead compared to per-key locking.

**6. Assumptions and Limitations**

*   **Assumptions:** The protocol's effectiveness is highly dependent on a meaningful and efficient key-to-entity mapping. It assumes that most contention occurs between transactions operating on the same logical object.
*   **Limitations:** The primary limitation is the scalability bottleneck imposed by the global commit lock. On workloads with very high commit rates and many cores, this lock will become the limiting factor. The protocol's performance may degrade on workloads where transactions touch many entities with no clear locality, as this increases the number of fine-grained locks to acquire and widens the scope of proactive invalidation, potentially leading to high abort rates.Novelty Result:
```json
```json
{
  "novelty_score_overall": 60,
  "verdict": "Novel Hybrid",
  "score_breakdown": {
    "conceptual_originality_40": 30,
    "distance_from_known_families_30": 22,
    "implementation_distinctiveness_15": 10,
    "correctness_argument_originality_10": 3,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 70,
    "code_novelty": 50
  },
  "overlap_analysis": [
    {
      "family": "Optimistic Concurrency Control (OCC)",
      "overlap_reasoning": "The protocol's execution phase is purely optimistic (speculative). Transactions read from a snapshot and buffer writes locally without taking locks, which is the hallmark of OCC's read phase.",
      "overlap_strength_0_to_100": 70
    },
    {
      "family": "Two-Phase Locking (2PL)",
      "overlap_reasoning": "The commit phase for writers is purely pessimistic, acquiring all necessary exclusive locks before applying writes. The lock acquisition order (lexicographical) to prevent deadlocks is also a common 2PL technique.",
      "overlap_strength_0_to_100": 60
    },
    {
      "family": "Timestamp Ordering (T/O)",
      "overlap_reasoning": "The proactive invalidation mechanism resembles a backward-validation T/O scheme where a committing transaction aborts any active transaction that has read its writes. However, HSL uses direct invalidation rather than timestamp checks.",
      "overlap_strength_0_to_100": 35
    },
    {
      "family": "Coarse-Grained / Hierarchical Locking",
      "overlap_reasoning": "The protocol's core concept of mapping fine-grained keys to coarse-grained 'entities' and managing concurrency at that level is a direct application of hierarchical or multigranularity locking principles.",
      "overlap_strength_0_to_100": 90
    }
  ],
  "feature_diff": [
    {
      "axis": "Conflict Detection & Resolution",
      "ours": "Conflicts are ignored during execution. At commit, a writer locks its touched entities and *proactively invalidates* (aborts) any other transaction that has speculatively touched the same entities. Resolution is 'winner-takes-all' via forced abort.",
      "typical_in_nearest_family": "In classic OCC, a committing transaction *validates its own read-set* against recently committed writers. If its reads are stale, it self-aborts. Resolution is 'loser self-aborts after validation'.",
      "difference_note": "The most significant difference is the inversion of responsibility for conflict detection. Instead of the committer validating its own past, it invalidates the future of others. This eliminates the need for read-set tracking and validation, replacing it with a potentially faster direct invalidation mechanism, especially when the number of conflicting transactions is small."
    }
  ],
  "code_checks": {
    "uses_locks_or_lock_modes": true,
    "uses_versions_or_snapshots": false,
    "commit_time_validation": false,
    "timestamp_ordering_core": false,
    "deterministic_preordering": false,
    "dependency_graph_cycles": false,
    "other_key_constructs": [
      "Global map of speculative transactions per entity",
      "Key-to-entity mapping function",
      "Global commit-phase lock for all transactions"
    ]
  },
  "correctness_rationale_summary": "The correctness argument rests on the serialization point established by the global commit lock. Because only one transaction can enter the commit phase at a time, a total order is enforced. The proactive invalidation within this critical section ensures that any transaction whose speculative reads have been rendered stale is aborted *before* it can enter its own commit phase. This prevents any non-serializable histories, such as write-skew, from occurring.",
  "risks_and_edge_cases": [
    "The global `commitLock` is a severe scalability bottleneck, as it serializes all commits (both read-only and writers). This will limit throughput on systems with many cores.",
    "The coarse-grained nature of entity locking can lead to high rates of false sharing and unnecessary aborts, especially if unrelated hot keys map to the same entity.",
    "The performance is highly sensitive to the quality of the `getEntityId` function. A poor mapping could render the protocol inefficient.",
    "Read-only transactions can be starved by a continuous stream of writer transactions that repeatedly abort them."
  ],
  "suggestions_to_increase_novelty": [
    "Remove the global `commitLock` and devise a lock-free or fine-grained mechanism for a committer to atomically apply writes and invalidate speculators. This is a very hard problem but would be highly novel.",
    "Introduce adaptive granularity, where the system can dynamically switch between entity-level and key-level speculation based on observed contention.",
    "Allow read-only transactions a more resilient path. For example, instead of being aborted, they could attempt to re-validate their reads after a writer commits, blending HSL with classic OCC validation."
  ],
  "confidence_0_to_1": 0.9
}
```
```
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 48,
  "verdict": "Incremental Hybrid",
  "score_breakdown": {
    "conceptual_originality_40": 55,
    "distance_from_known_families_30": 50,
    "implementation_distinctiveness_15": 25,
    "correctness_argument_originality_10": 40,
    "rebranding_penalty_minus15": -5
  },
  "separate_scores": {
    "proposal_novelty": 60,
    "code_novelty": 25
  },
  "overlap_analysis": [
    {
      "family": "Silo (OCC Variant)",
      "overlap_reasoning": "Very strong overlap. Both use optimistic execution followed by a pessimistic, lock-based commit phase. The primary difference is HSL's 'proactive invalidation' of others versus Silo's 'self-validation' of its own read-set. HSL's entity-level locking is a coarse-grained version of Silo's record-level locking.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "High overlap. The execution phase is purely optimistic, like classic OCC. The commit phase serves the same purpose as OCC's validation phase (ensuring no conflicts occurred). However, HSL uses pessimistic locking and proactive aborts instead of backward validation of a read-set.",
      "overlap_strength_0_to_100": 70
    },
    {
      "family": "Strict 2PL",
      "overlap_reasoning": "Low overlap. HSL only uses pessimistic locking during the commit phase, not during the main execution phase. This avoids the blocking and potential deadlocks of classic 2PL during transaction execution. The shared element is using exclusive locks to serialize writers.",
      "overlap_strength_0_to_100": 30
    }
  ],
  "feature_diff": [
    {
      "axis": "conflict resolution at commit",
      "ours": "A committing writer locks its touched entities and then actively finds and aborts all other concurrent transactions that were speculating on those same entities.",
      "typical_in_nearest_family": "In Silo/OCC, a committing transaction validates its *own* read-set to check if any of its read data has been modified by another transaction that has committed since its execution began.",
      "difference_note": "This shifts the validation burden. Instead of a transaction checking 'am I still valid?', it asserts 'I am now the truth, and everyone else who thought differently is now invalid'. This avoids iterating a read-set but can be more aggressive, causing higher abort rates."
    },
    {
      "axis": "metadata for validation",
      "ours": "A global map from `entityId` to a set of `txid`s that are currently speculating on it. This is used to find victims to abort.",
      "typical_in_nearest_family": "Per-record metadata (e.g., a version/lock word with a TID) and a per-transaction read-set. This is used for self-validation.",
      "difference_note": "HSL's metadata is outward-facing (to find others), whereas typical OCC metadata is inward-facing (to validate oneself). This is a direct consequence of the different conflict resolution philosophies."
    },
    {
      "axis": "implementation of commit serialization",
      "ours": "The code uses a single, global `commitLock` to serialize the entire commit logic for all writer transactions.",
      "typical_in_nearest_family": "High-performance OCC protocols use fine-grained locking (e.g., on records) or lock-free validation techniques to allow multiple transactions to commit concurrently.",
      "difference_note": "The implementation's global lock is a major deviation from the proposal's more concurrent idea of sorted lock acquisition. It creates a serial bottleneck and reduces the novelty and scalability of the implemented system significantly."
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
      "Global `commitLock` serializing all writer commits",
      "Global `speculativeMap` tracking entity -> {txid} mappings for invalidation"
    ]
  },
  "correctness_rationale_summary": "Strict Serializability is ensured by a combination of standard and novel techniques. The serialization point for writers is the moment they acquire entity locks during the commit phase. In the code, this is trivially enforced by a single global commit lock. This prevents WW conflicts. Write-skew (RW conflicts) is prevented by the proactive invalidation mechanism: a committing writer (T1) immediately aborts any speculator (T2) that read the same entity, ensuring T2 cannot commit based on a stale premise. This correctness argument is a direct composition of pessimistic locking and an aggressive abort policy.",
  "risks_and_edge_cases": [
    "The implementation's global `commitLock` serializes all writer commits, becoming a severe bottleneck under any moderate write load and undermining the protocol's concurrency claims.",
    "Livelock/starvation is a high risk. Under high contention for a specific entity, a transaction may be repeatedly aborted by a stream of successful committers before it can ever reach its own commit phase.",
    "The 'entity' mapping is a critical tuning parameter. A coarse-grained mapping can lead to high rates of false-sharing conflicts, where transactions operating on different keys within the same entity needlessly abort each other."
  ],
  "suggestions_to_increase_novelty": [
    "Remove the global `commitLock` from the implementation. Faithfully implement the proposal's idea of acquiring multiple entity locks in a globally consistent order to prevent deadlock. This would make the implementation far more concurrent and novel.",
    "Refine the invalidation mechanism. Instead of aborting all speculators on an entity, track read/write intents separately. A writer would only need to abort other writers (WW) or transactions that read a key it is now writing (RW), potentially allowing read-only speculators to survive.",
    "Explore integrating versioning. A committing transaction could create a new version of an entity instead of overwriting it, and only invalidate transactions that depend on the specific version being replaced. This would merge the core idea with MVCC principles."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implements the Hierarchical Speculative Locking (HSL) protocol.
 * HSL allows transactions to execute speculatively without locks and enforces
 * serializability at commit time through coarse-grained entity locking and
 * proactive invalidation of conflicting transactions.
 *
 * This revised version introduces a global commit lock to fix a race condition
 * that violated serializability in the original implementation.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final AtomicInteger txidCounter = new AtomicInteger(0);
    private static final AtomicInteger timestampCounter = new AtomicInteger(0);

    // Global map of all active transactions, allowing one to invalidate another.
    private static final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();

    // Maps entity ID to a thread-safe set of speculating transaction IDs.
    private static final ConcurrentHashMap<String, ConcurrentHashMap.KeySetView<Integer, Boolean>> speculativeMap = new ConcurrentHashMap<>();

    // Maps entity ID to a ReentrantLock for the pessimistic commit phase.
    private static final ConcurrentHashMap<String, ReentrantLock> firmLocks = new ConcurrentHashMap<>();

    // A global lock to serialize all commit decisions, preventing race conditions.
    private static final ReentrantLock commitLock = new ReentrantLock();

    // Metadata key for the Transaction.meta map
    private static final String META_TOUCHED_ENTITIES = "touchedEntities";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private String getEntityId(String key) {
        try {
            String[] parts = key.split("_");
            if (parts.length < 2) return key; // Fallback for simple keys

            switch (parts[0]) {
                case "u":
                    return "user_" + parts[1];
                case "i":
                case "ib":
                case "imb":
                case "ic":
                case "if":
                    if (parts.length > 2) return "item_" + parts[1] + "_" + parts[2];
                    break;
                case "cnt": // "cnt_ic_..."
                    if (parts.length > 3) return "item_" + parts[2] + "_" + parts[3];
                    break;
                case "ip":
                    return "purchase_" + parts[1];
                case "ui":
                     if (parts.length > 2) return "user_item_" + parts[1] + "_" + parts[2];
                     break;
            }
        } catch (Exception e) {
            return key; // Fallback for malformed or unexpected keys
        }
        return key; // Fallback for keys that don't match patterns
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, timestampCounter.incrementAndGet());
        txn.meta.put(META_TOUCHED_ENTITIES, ConcurrentHashMap.newKeySet());
        activeTransactions.put(txid, txn);
        return txn;
    }

    private void registerSpeculativeIntent(Transaction txn, String key) {
        String entityId = getEntityId(key);
        speculativeMap.computeIfAbsent(entityId, k -> ConcurrentHashMap.newKeySet()).add(txn.txid);
        ((Set<String>) txn.meta.get(META_TOUCHED_ENTITIES)).add(entityId);
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        registerSpeculativeIntent(txn, key);

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }
        return store.get(key);
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        registerSpeculativeIntent(txn, key);
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction was already aborted before commit phase.");
        }

        List<ReentrantLock> acquiredLocks = new ArrayList<>();
        commitLock.lock();
        try {
            if (txn.status != TxStatus.ACTIVE) {
                rollback(txn, store); // Ensure cleanup if aborted while waiting
                return CommitResult.failure("Transaction was aborted while waiting for commit lock.");
            }

            // Read-only path
            if (txn.writeSet.isEmpty()) {
                txn.status = TxStatus.COMMITTED;
                cleanup(txn);
                return CommitResult.success(timestampCounter.incrementAndGet());
            }

            // Writer path
            Set<String> touchedEntities = (Set<String>) txn.meta.get(META_TOUCHED_ENTITIES);
            List<String> sortedEntities = new ArrayList<>(touchedEntities);
            Collections.sort(sortedEntities);

            for (String entityId : sortedEntities) {
                ReentrantLock lock = firmLocks.computeIfAbsent(entityId, k -> new ReentrantLock());
                lock.lock();
                acquiredLocks.add(lock);
            }

            store.updateAll(txn.writeSet);

            for (String entityId : touchedEntities) {
                Set<Integer> speculators = speculativeMap.get(entityId);
                if (speculators != null) {
                    for (int speculatorTxid : new ArrayList<>(speculators)) { // Iterate on copy
                        if (speculatorTxid != txn.txid) {
                            Transaction specTxn = activeTransactions.get(speculatorTxid);
                            if (specTxn != null) {
                                specTxn.status = TxStatus.ABORTED;
                            }
                        }
                    }
                }
            }

            txn.status = TxStatus.COMMITTED;
            cleanup(txn);
            return CommitResult.success(timestampCounter.incrementAndGet());

        } finally {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
            commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.COMMITTED) return;
        txn.status = TxStatus.ABORTED;
        cleanup(txn);
    }

    private void cleanup(Transaction txn) {
        Set<String> touchedEntities = (Set<String>) txn.meta.get(META_TOUCHED_ENTITIES);
        if (touchedEntities != null) {
            for (String entityId : touchedEntities) {
                Set<Integer> speculators = speculativeMap.get(entityId);
                if (speculators != null) {
                    speculators.remove(txn.txid);
                }
            }
        }
        activeTransactions.remove(txn.txid);
    }
}
