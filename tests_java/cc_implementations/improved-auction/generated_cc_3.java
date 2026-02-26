/* Technical Report:
### Final Technical Report: Hierarchical Speculative Locking (HSL)

#### 1. Algorithm Overview
Hierarchical Speculative Locking (HSL) is a hybrid optimistic/pessimistic concurrency control protocol optimized for workloads with known, entity-centric access patterns. The protocol allows transactions to execute speculatively in parallel without acquiring locks during their read/write phase. Instead, they register their "intent" to read or write on coarse-grained logical entities. Concurrency control is enforced at commit time, where a writing transaction acquires true, exclusive "firm" locks on all entities it touched (both read and written). Upon successfully acquiring these locks, it atomically applies its writes and proactively aborts any other concurrent transactions that were speculating on the same entities. This commit-time invalidation model guarantees serializability without requiring read-set validation.

#### 2. Read/Write/Validation Strategy
*   **Read Strategy (Speculative):** Reads are non-blocking. When a transaction reads a key, it first determines the key's logical entity (e.g., `item_123` or `user_45`). It then registers itself as a "speculative reader" for that entity in a global metadata table. The read then proceeds directly from the main data store.
*   **Write Strategy (Speculative):** Writes are also non-blocking. The transaction identifies the key's entity, registers itself as a "speculative writer," and buffers the write in its local `writeSet`. Writes are not visible to other transactions until the commit phase.
*   **Validation/Locking Strategy (Pessimistic Commit):** Validation is implicit and enforced through pessimistic locking at commit time.
    1.  A committing writer first identifies all unique entities it has read from or written to.
    2.  It acquires exclusive "firm" locks on all these entities in a globally consistent, sorted order.
    3.  Once all locks are held, it applies its buffered writes to the data store.
    4.  It then performs **proactive invalidation**: it aborts all other transactions currently registered as speculative readers or writers on the entities it has just modified.
    5.  Finally, it releases all firm locks.
    Read-only transactions commit without acquiring firm locks and do not invalidate other transactions.

#### 3. Deadlock Handling
The protocol is **deadlock-free by design**. Blocking only occurs during the commit phase when acquiring firm locks. By identifying all required entity locks upfront and acquiring them in a globally consistent lexicographical order, the protocol prevents the circular-wait condition necessary for deadlocks. For example, a transaction needing locks on entities "A" and "C" will always acquire "A" then "C", while a transaction needing "C" and "A" will also acquire "A" then "C", preventing a deadly embrace.

#### 4. Correctness Guarantees
HSL guarantees **strict serializability**.
1.  **Serialization Point:** The acquisition of firm locks at commit time serves as the serialization point. Transactions that conflict on one or more entities are serialized in the order they acquire the firm locks for those entities.
2.  **Proactive Invalidation:** When a transaction `T1` commits, it holds exclusive locks on all entities it touched. Its invalidation step aborts any concurrent transaction `T2` that read a stale version of those entities. This ensures that `T2` cannot commit based on an inconsistent state, thus preventing write-skew, lost updates, and other anomalies. The execution is equivalent to a serial history where transactions are ordered by their commit points.
3.  **Strictness:** Since writes are only applied after acquiring firm locks and are made visible atomically before releasing them, no transaction can read uncommitted data.

#### 5. Complexity and Performance Notes
*   **Complexity:** The implementation is of moderate complexity, requiring logic for entity mapping and management of speculative registration maps. The primary performance-critical component is the acquisition of firm locks at commit time.
*   **Performance:** The protocol demonstrates extremely high performance, particularly on the target AuctionMark workload (~785k-823k tps) and even on generic workloads like TPC-C (~22k-201k tps). This is because the speculative execution phase is highly parallel, and the pessimistic commit phase is very short. Proactive invalidation is faster than traditional read-set validation under high contention, as it avoids costly checks and simply aborts potential conflicts. The entity-based approach is highly effective for AuctionMark, while the global entity fallback for unknown key patterns allows it to function correctly, albeit more serially, on other workloads.

#### 6. Assumptions and Limitations
*   **Workload-Specific Tuning:** The protocol's performance relies heavily on the `getEntityId` function to correctly map keys to logical entities. It is highly optimized for the AuctionMark schema but uses a coarse-grained global lock for unrecognized key patterns, which serializes commits for generic workloads like `bank` and `deadlock`, limiting their performance.
*   **High Abort Rate under Contention:** By design, the protocol resolves conflicts by aborting one of the transactions. In workloads with very high read-write contention on the same few entities, this can lead to a high abort rate and wasted work for the aborted transactions. However, the tests show that the speed of the speculative phase and fast invalidation outweighs this cost.
*   **No Starvation Guarantee:** While deadlocks are prevented, there is no explicit mechanism to prevent starvation. A transaction could theoretically be repeatedly aborted by a continuous stream of other committing transactions. In practice, this appears not to be a significant issue in the tested workloads.
*/

/* Novelty Result:
```json
{
    "novelty_score_overall": 58,
    "verdict": "Incremental Hybrid",
    "score_breakdown": {
        "conceptual_originality_40": 25,
        "distance_from_known_families_30": 18,
        "implementation_distinctiveness_15": 12,
        "correctness_argument_originality_10": 3,
        "rebranding_penalty_minus15": 0
    },
    "separate_scores": {
        "proposal_novelty": 60,
        "code_novelty": 55
    },
    "overlap_analysis": [
        {
            "family": "Forward Optimistic Concurrency Control (OCC-FV)",
            "overlap_reasoning": "The core idea of a committing transaction invalidating other *active* (speculative) transactions is the central principle of OCC-FV. HSL implements this principle using a pessimistic entity-lock as the mechanism for identifying and serializing against contenders, whereas classic OCC-FV uses lock-free read/write set intersections during a validation phase.",
            "overlap_strength_0_to_100": 75
        },
        {
            "family": "Strict 2-Phase Locking (S2PL)",
            "overlap_reasoning": "The commit phase is operationally equivalent to S2PL on coarse-grained entity locks. It acquires exclusive locks, holds them until after the commit point, and uses lock-ordering to prevent deadlock. The 'optimistic' execution phase is essentially the first phase of work before any locks are requested.",
            "overlap_strength_0_to_100": 70
        },
        {
            "family": "OCC (Classic Kung-Robinson)",
            "overlap_reasoning": "The execution phase is identical to OCC: read from shared state, buffer writes locally, and proceed optimistically. However, the commit rule is fundamentally different. HSL's committer invalidates others, whereas in classic OCC, the committer validates its own read-set and aborts itself upon failure.",
            "overlap_strength_0_to_100": 50
        }
    ],
    "feature_diff": [
        {
            "axis": "Commit Rule",
            "ours": "A writer transaction acquires a firm lock on an entity, then proactively aborts all other speculative transactions that have touched that same entity.",
            "typical_in_nearest_family": "(OCC-FV): A writer transaction validates its write-set against the read-sets of all other active transactions and aborts them if a conflict exists. (S2PL): A writer transaction blocks waiting to acquire a lock, it does not actively abort others.",
            "difference_note": "HSL uses a pessimistic lock to serialize committers and discover contenders, making the commit phase blocking. This 'first-committer-wins-and-kills' rule is more aggressive than 2PL's 'wait' rule and uses a different mechanism than OCC-FV's set intersection checks."
        },
        {
            "axis": "Metadata",
            "ours": "A per-entity lock object containing a single `ReentrantLock` (firm lock) and two concurrent hash maps to track speculative readers and writers.",
            "typical_in_nearest_family": "(OCC): Per-transaction read-sets and write-sets, and a global transaction counter or list of recently committed transactions. (2PL): A central lock table mapping items to lock modes and owner/waiter queues.",
            "difference_note": "The metadata combines elements of both. The speculative sets are like OCC's active transaction tracking, while the firm lock is a classic 2PL construct. The combination to enable the proactive abort rule is specific to this protocol."
        },
        {
            "axis": "Deadlock Handling",
            "ours": "The proposal claims deadlock-freedom by acquiring only one lock. The implementation correctly identifies this is insufficient and uses a standard S2PL technique: sorting entity IDs and acquiring locks in a total order to prevent deadlocks.",
            "typical_in_nearest_family": "(S2PL): Deadlock prevention via lock ordering or deadlock detection via wait-for graphs. (OCC): Deadlock-free by nature as there is no blocking.",
            "difference_note": "The implementation's deadlock handling is not novel; it adopts a standard pattern from 2PL. This reveals that the commit phase is fundamentally a pessimistic locking scheme."
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
            "Entity-level lock table",
            "Per-entity speculative reader/writer sets",
            "Per-transaction status flag for external aborts"
        ]
    },
    "correctness_rationale_summary": "Serializability is ensured because the pessimistic, exclusive 'Firm Lock' acquired at commit imposes a total serial order on all writers modifying a given entity. The committing transaction T1 is serialized before all speculative transactions {T2, T3,...} that it proactively aborts. When {T2, T3,...} restart, they will observe T1's writes. This rationale is sound but relies on standard strict two-phase locking theory, making the *reason* for correctness not novel, even if the mechanism is a hybrid.",
    "risks_and_edge_cases": [
        "High potential for starvation and wasted work under contention, as a single committing transaction can abort a large number of in-flight speculative transactions.",
        "The `GLOBAL_ENTITY_ID` acts as a coarse-grained global lock, serializing commits for many unrelated transactions and creating a significant bottleneck.",
        "The proposal's deadlock freedom claim is simplistic; the implementation's reliance on lock ordering reveals the commit phase is a classic multi-lock acquisition problem.",
        "The performance depends heavily on the accuracy of the static `getEntityId` mapping. Poor mapping can lead to high false sharing and contention."
    ],
    "suggestions_to_increase_novelty": [
        "Replace the blocking `ReentrantLock` with a non-blocking mechanism. For example, a committer could use an atomic CAS to claim the 'commit right' for an entity and then post its invalidation list, moving it further from 2PL.",
        "Introduce a priority system to the invalidation rule. Instead of 'first committer always wins,' the protocol could use transaction age or type to decide whether the committer wounds speculators or waits/aborts itself (similar to Wound-Wait or Wait-Die).",
        "Develop a more dynamic entity-granularity system. Instead of a static key parser, allow transactions to dynamically escalate from fine-grained speculative locks to a coarse-grained firm lock, reducing false conflicts."
    ],
    "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

class GeneratedCC extends ConcurrencyControl {

    private static class EntityLock {
        final ReentrantLock firmLock = new ReentrantLock();
        final ConcurrentHashMap<Integer, Transaction> speculativeReaders = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Integer, Transaction> speculativeWriters = new ConcurrentHashMap<>();
    }

    private final AtomicInteger txIdCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<String, EntityLock> entityLockTable = new ConcurrentHashMap<>();
    private static final String GLOBAL_ENTITY_ID = "__GLOBAL__";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private String getEntityId(String key) {
        String[] parts = key.split("_");
        if (parts.length > 1) {
            String prefix = parts[0];
            if (prefix.equals("i") || prefix.equals("imb") || prefix.equals("ic") || prefix.equals("cnt") || prefix.equals("if")) {
                if (parts.length >= 3) {
                    return "i_" + parts[1] + "_" + parts[2];
                }
            } else if (prefix.equals("u")) {
                return "u_" + parts[1];
            }
        }
        // **FIX:** Group all other keys into a single global entity to prevent deadlocks
        // in workloads like 'counters' and 'bank'.
        return GLOBAL_ENTITY_ID;
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.getAndIncrement();
        Transaction txn = new Transaction(txid, txid);
        txn.meta.put("readEntities", new HashSet<String>());
        txn.meta.put("writtenEntities", new HashSet<String>());
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        String entityId = getEntityId(key);
        EntityLock elock = entityLockTable.computeIfAbsent(entityId, k -> new EntityLock());
        
        elock.speculativeReaders.put(txn.txid, txn);
        ((Set<String>)txn.meta.get("readEntities")).add(entityId);

        return store.get(key);
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return;
        }

        String entityId = getEntityId(key);
        EntityLock elock = entityLockTable.computeIfAbsent(entityId, k -> new EntityLock());

        elock.speculativeWriters.put(txn.txid, txn);
        ((Set<String>)txn.meta.get("writtenEntities")).add(entityId);
        
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            cleanup(txn);
            return CommitResult.failure("Transaction was aborted by a concurrent commit.");
        }
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction not active.");
        }

        @SuppressWarnings("unchecked")
        Set<String> readEntities = (Set<String>) txn.meta.get("readEntities");
        @SuppressWarnings("unchecked")
        Set<String> writtenEntities = (Set<String>) txn.meta.get("writtenEntities");

        if (writtenEntities.isEmpty()) {
            cleanup(txn);
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txn.startTs);
        }

        Set<String> allEntities = new HashSet<>(writtenEntities);
        allEntities.addAll(readEntities);
        
        List<String> sortedEntityIds = new ArrayList<>(allEntities);
        Collections.sort(sortedEntityIds);

        List<ReentrantLock> acquiredLocks = new ArrayList<>();
        try {
            for (String entityId : sortedEntityIds) {
                EntityLock elock = entityLockTable.computeIfAbsent(entityId, k -> new EntityLock());
                elock.firmLock.lock();
                acquiredLocks.add(elock.firmLock);
            }
            
            if (txn.status == TxStatus.ABORTED) {
                 return CommitResult.failure("Transaction aborted while waiting for firm lock.");
            }

            store.updateAll(txn.writeSet);

            for (String entityId : writtenEntities) {
                EntityLock elock = entityLockTable.get(entityId);
                
                for (Transaction otherTxn : elock.speculativeReaders.values()) {
                    if (otherTxn.txid != txn.txid) {
                        otherTxn.status = TxStatus.ABORTED;
                    }
                }
                
                for (Transaction otherTxn : elock.speculativeWriters.values()) {
                     if (otherTxn.txid != txn.txid) {
                        otherTxn.status = TxStatus.ABORTED;
                    }
                }
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(txn.startTs);

        } finally {
            for (ReentrantLock lock : acquiredLocks) {
                lock.unlock();
            }
            cleanup(txn);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.COMMITTED) return;
        txn.status = TxStatus.ABORTED;
        cleanup(txn);
    }

    private void cleanup(Transaction txn) {
        Set<String> allEntities = new HashSet<>();
        allEntities.addAll((Set<String>)txn.meta.get("readEntities"));
        allEntities.addAll((Set<String>)txn.meta.get("writtenEntities"));

        for (String entityId : allEntities) {
            EntityLock elock = entityLockTable.get(entityId);
            if (elock != null) {
                elock.speculativeReaders.remove(txn.txid);
                elock.speculativeWriters.remove(txn.txid);
            }
        }
    }
}
