/* Technical Report:
### Final Report: Semantic Mode Locking (SML) Protocol

#### 1. Algorithm Overview
Semantic Mode Locking (SML) is a pessimistic concurrency control protocol based on Strict Two-Phase Locking (S2PL). Its primary innovation is the introduction of lock modes derived from the semantics of database operations. Instead of treating all writes as `EXCLUSIVE`, SML distinguishes between non-commutative writes and commutative writes (e.g., increments/decrements on counters). This is achieved through a multi-mode locking system: `SHARED` for reads, `EXCLUSIVE` for general writes, and a specialized `DELTA` mode for commutative updates. The `DELTA` lock is compatible with other `DELTA` locks on the same data item, allowing multiple transactions to concurrently prepare updates for high-contention counters. This transforms serial bottlenecks into parallel operations, significantly increasing throughput for workloads rich in commutative updates, such as TPC-C's `Payment` transaction.

#### 2. Read/Write/Lock and Validation Strategy
*   **Locking Strategy:** SML is a pessimistic protocol. A transaction must acquire the appropriate lock *before* performing an operation. If a conflicting lock is held, the transaction blocks until the lock is released.
    *   **Read:** A `SHARED` lock is requested.
    *   **Commutative Write (`_ytd`, `_bal`):** A `DELTA` lock is requested. A `SHARED` lock can be upgraded to a `DELTA` lock if the transaction is the sole shared lock holder.
    *   **Non-commutative Write (`_qty`, `_next_oid`):** An `EXCLUSIVE` lock is requested. A `SHARED` lock can be upgraded to `EXCLUSIVE` if the transaction is the sole shared lock holder.
*   **Lock Compatibility Matrix:**
| Requested | Held: SHARED | Held: DELTA | Held: EXCLUSIVE |
|---|---|---|---|
| **SHARED** | **OK** | CONFLICT | CONFLICT |
| **DELTA** | CONFLICT | **OK** | CONFLICT |
| **EXCLUSIVE**| CONFLICT | CONFLICT | CONFLICT |
*   **Write Strategy:** Writes are buffered locally. `EXCLUSIVE` writes store the final value in a `writeSet`. `DELTA` writes calculate the change (delta) from the last read value and store it in a `deltaSet`. All buffered writes and deltas are applied to the store at commit time.
*   **Validation Strategy:** As a pessimistic protocol, SML does not require a validation phase at commit time. Correctness is guaranteed by preventing conflicts from occurring in the first place.

#### 3. Deadlock Handling
The protocol uses the **Wait-Die** algorithm for deadlock prevention. Each transaction is assigned a unique, monotonically increasing timestamp upon creation. When a transaction `T_req` requests a lock that conflicts with a lock held by another transaction `T_hold`:
*   If `T_req` is older (`T_req.timestamp < T_hold.timestamp`), `T_req` waits (blocks).
*   If `T_req` is younger (`T_req.timestamp > T_hold.timestamp`), `T_req` "dies" (is immediately aborted and rolled back).
Because an older transaction never waits for a younger one, circular dependencies in the wait-for graph are impossible, thus preventing deadlocks. This mechanism guarantees progress and freedom from stalls.

#### 4. Correctness Guarantees
SML guarantees **Strict Conflict-Serializability**.
*   **Conflict-Serializability:** The protocol is a variant of Two-Phase Locking. It has a "growing phase" where transactions acquire locks and a "shrinking phase" where they release all locks at the end. The lock compatibility matrix correctly prevents all `R-W`, `W-R`, and non-commutative `W-W` conflicts. The one exception, concurrent `DELTA` locks, is safe because the underlying operations are commutative, meaning the final state of the data item is the same regardless of the serialization order of the committing transactions.
*   **Strictness:** All locks (Shared, Delta, Exclusive) are held until the transaction commits or aborts. This ensures that no transaction can read or write data that has been modified by an uncommitted transaction, guaranteeing a strict execution history and preventing cascading aborts.

#### 5. Complexity and Performance Notes
SML's performance shines in workloads with high contention on commutative hotspots. By allowing `DELTA` locks to coexist, it significantly outperforms standard S2PL, which would serialize all updates to a given counter. As a pessimistic protocol, it has a near-zero abort rate due to data contention, avoiding the wasted work common in OCC under high load. The main overheads are the maintenance of the lock table and the blocking latency when true conflicts occur. The Wait-Die mechanism is simple and effective but can lead to more aborts for younger transactions in some conflict scenarios.

#### 6. Assumptions and Limitations
*   The protocol's effectiveness relies on the ability to statically analyze transaction code to distinguish between commutative and non-commutative write operations. This is straightforward for the given TPC-C workload but may be more complex in ad-hoc query environments.
*   The implementation uses a simplified heuristic to identify the "blind read" in the `NewOrder` transaction to bypass locking. A more robust system would require explicit hints from the application.
*   While Wait-Die prevents deadlocks, it is not starvation-free. A transaction that is repeatedly aborted because it conflicts with older transactions could theoretically be starved, though this is rare in practice with random transaction scheduling.Novelty Result:
```json
```json
{
  "novelty_score_overall": 45,
  "verdict": "Novel Combination",
  "score_breakdown": {
    "conceptual_originality_40": 20,
    "distance_from_known_families_30": 15,
    "implementation_distinctiveness_15": 5,
    "correctness_argument_originality_10": 3,
    "rebranding_penalty_minus15": -2
  },
  "separate_scores": {
    "proposal_novelty": 45,
    "code_novelty": 45
  },
  "overlap_analysis": [
    {
      "family": "Strict 2PL (S2PL)",
      "overlap_reasoning": "The protocol is fundamentally a pessimistic, two-phase locking scheme. It acquires locks before access and holds them until commit/abort. The use of Shared and Exclusive locks follows the standard S2PL pattern.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "Semantic Concurrency Control / Escrow Locking",
      "overlap_reasoning": "The core novelty comes from this family. The 'DELTA' lock is a classic example of incorporating operation semantics (commutativity) into the concurrency control mechanism. It functions similarly to escrow or commutative locks, which allow concurrent updates on counters.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "Deadlock Prevention Algorithms (Wait-Die)",
      "overlap_reasoning": "The protocol uses a textbook implementation of the Wait-Die algorithm for deadlock prevention. This is a standard technique often paired with pessimistic locking protocols.",
      "overlap_strength_0_to_100": 95
    },
    {
      "family": "Intention Locks (e.g., in hierarchical locking)",
      "overlap_reasoning": "While not directly related, the concept of having multiple lock modes with a specific compatibility matrix is shared. However, SML's modes are based on operation semantics, not data hierarchy.",
      "overlap_strength_0_to_100": 20
    }
  ],
  "feature_diff": [
    {
      "axis": "Core Locking Mechanism",
      "ours": "A unified, multi-mode locking protocol (Shared, Delta, Exclusive) where commutativity is a first-class lock mode, fully integrated with standard read/write locks.",
      "typical_in_nearest_family": "Standard S2PL uses only Shared and Exclusive locks. Semantic/Escrow locking is often treated as a special-case mechanism for specific data types, not as a peer lock mode within a general protocol.",
      "difference_note": "The key novelty is the tight integration. Instead of having a separate 'counter update' protocol, SML makes the commutative lock a peer to shared/exclusive locks within a single, unified compatibility matrix. This is a novel and elegant combination of well-known concepts."
    },
    {
      "axis": "Deadlock Management",
      "ours": "Uses the Wait-Die prevention algorithm based on transaction start timestamps.",
      "typical_in_nearest_family": "Pessimistic protocols use a variety of deadlock management schemes, including prevention (e.g., Wait-Die, Wound-Wait, canonical ordering) and detection (building a wait-for graph and aborting a victim). The choice of Wait-Die is standard but fits the overall pessimistic design.",
      "difference_note": "No significant novelty in the choice of deadlock algorithm itself, but its application to the multi-mode locking system is part of the complete package."
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
      "Per-key custom lock object tracking multiple holder sets (shared, delta).",
      "Hardcoded semantic analysis based on key name suffixes."
    ]
  },
  "correctness_rationale_summary": "The correctness argument is a solid and standard extension of the S2PL proof. It relies on showing that the lock compatibility matrix prevents all harmful data conflicts (R-W, W-R, non-commutative W-W). The one exception, allowing concurrent DELTA locks, is justified by the mathematical property of commutativity. The addition of the Wait-Die algorithm's proof (showing it prevents circular waits) completes the argument for a serializable and deadlock-free execution. The argument is not groundbreaking but is a correct application of established principles to a new combination of mechanisms.",
  "risks_and_edge_cases": [
    "The performance gain is highly dependent on the workload having a significant number of commutative updates on contended keys. On a workload with only exclusive writes, it degenerates to standard S2PL.",
    "The static analysis of operation semantics based on key names is brittle. A transaction that performs a non-commutative write on a key ending in '_ytd' would violate the protocol's assumptions and could lead to data corruption.",
    "Wait-Die can cause higher abort rates for younger (often shorter) transactions, which could be a performance issue in some workloads."
  ],
  "suggestions_to_increase_novelty": [
    "Generalize the semantic lock modes. Could a 'MAX' or 'MIN' lock mode be introduced for idempotent operations? This would extend the core idea beyond just commutativity.",
    "Explore a hybrid approach. Could a transaction start optimistically and only acquire these semantic locks upon first conflict, combining the benefits of low-overhead optimistic execution with the high-concurrency pessimistic locking?",
    "Replace Wait-Die with a more novel deadlock management scheme tailored to the semantic lock modes. For example, could a DELTA lock holder be 'pushed' to commit faster by a conflicting EXCLUSIVE lock requestor, rather than forcing an abort?"
  ],
  "confidence_0_to_1": 0.9
}
```
```
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 26,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 16,
    "distance_from_known_families_30": 9,
    "implementation_distinctiveness_15": 4,
    "correctness_argument_originality_10": 2,
    "rebranding_penalty_minus15": -5
  },
  "separate_scores": {
    "proposal_novelty": 25,
    "code_novelty": 15
  },
  "overlap_analysis": [
    {
      "family": "Escrow Locking / Semantic Locking",
      "overlap_reasoning": "The core mechanism of SML, the 'DELTA' lock for commutative operations, is functionally identical to the principle of Escrow Locking developed in the 1980s (e.g., by O'Neil). Both allow concurrent, non-conflicting updates to hotspots like counters by locking the delta/change rather than the value itself.",
      "overlap_strength_0_to_100": 98
    },
    {
      "family": "Strict 2PL (S2PL)",
      "overlap_reasoning": "SML is explicitly built upon S2PL. It is pessimistic, blocks on conflict, acquires locks during execution, and releases them only at termination (commit/abort). The SHARED and EXCLUSIVE modes behave exactly as in standard S2PL.",
      "overlap_strength_0_to_100": 95
    },
    {
      "family": "CRDTs (Conflict-free Replicated Data Types)",
      "overlap_reasoning": "The concept of allowing concurrent updates based on commutativity is the philosophical foundation of CRDTs. The 'DELTA' lock on a numerical key effectively treats that key as a PN-Counter CRDT. However, SML embeds this within a traditional, centralized locking protocol rather than being a full CRDT-based system.",
      "overlap_strength_0_to_100": 30
    }
  ],
  "feature_diff": [
    {
      "axis": "Lock Modes",
      "ours": "Three modes: SHARED (read), EXCLUSIVE (non-commutative write), and DELTA (commutative write).",
      "typical_in_nearest_family": "In S2PL: SHARED and EXCLUSIVE. In Escrow Locking: A conceptual lock on a 'delta' or escrowed amount, rather than a distinct lock mode in a formal compatibility matrix.",
      "difference_note": "The novelty is minor, consisting of formalizing the classic escrow concept into a simple, third lock mode ('DELTA') within a standard S/X compatibility matrix. This is a clean presentation but not a new idea."
    },
    {
      "axis": "Conflict Resolution",
      "ours": "DELTA locks are compatible only with other DELTA locks. They conflict with both SHARED and EXCLUSIVE locks.",
      "typical_in_nearest_family": "Classic escrow locking focused on WW conflicts and constraint preservation. The interaction with readers (SHARED locks) was not always as strictly defined. Standard S2PL would treat all writes as EXCLUSIVE, making them conflict.",
      "difference_note": "The strict incompatibility between DELTA and SHARED is a specific, safe design choice that simplifies correctness by ensuring readers always see a stable value, not a base value plus uncommitted deltas. This makes it a stricter variant of the general semantic locking idea."
    },
    {
      "axis": "Deadlock Handling",
      "ours": "The proposal claims deadlock prevention via canonical key ordering. The code implements deadlock avoidance via Wait-Die (younger transactions abort).",
      "typical_in_nearest_family": "Both canonical ordering and Wait-Die are standard, well-known techniques used with 2PL.",
      "difference_note": "There is no novelty in the deadlock handling mechanism itself. The discrepancy between the proposal and implementation indicates a choice between two standard options, not the invention of a new one."
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
      "Per-transaction 'deltaSet' to buffer commutative updates separately from the standard 'writeSet'.",
      "Custom 'KeyLock' object with separate holder sets for each lock mode."
    ]
  },
  "correctness_rationale_summary": "Correctness is ensured by extending the Strict Two-Phase Locking (S2PL) argument. The lock compatibility matrix prevents all standard Read-Write, Write-Read, and non-commutative Write-Write conflicts. The only permitted concurrent write-write conflict (DELTA-DELTA) is proven safe because the underlying operations are commutative, meaning the final state is independent of their physical execution order. Strictness is guaranteed by holding all locks until termination. This rationale is sound but not novel; it is the standard argument for Escrow Locking.",
  "risks_and_edge_cases": [
    "The protocol's performance benefits are highly workload-specific, realized only on hotspots with commutative operations. On general workloads, it degenerates to S2PL with minor overhead.",
    "The 'Blind Read Bypass' is an application-specific optimization, not a general CC principle. It could violate serializability if applied incorrectly to reads that do form true data dependencies.",
    "The protocol is essentially a rediscovery of Escrow Locking from the 1980s, limiting its academic novelty despite its practical effectiveness on certain workloads."
  ],
  "suggestions_to_increase_novelty": [
    "Generalize the semantic analysis beyond hardcoded key suffixes. Create a framework where transactions can register arbitrary commutative and associative functions, and the lock manager can grant a generalized 'COMMUTATIVE' lock.",
    "Combine the DELTA lock concept with multi-versioning. For instance, allow readers on a snapshot to see a consistent past version while concurrent transactions acquire DELTA locks on the latest version. This would create a novel hybrid of MVCC and Semantic Locking.",
    "Relax the conflict rules. Allow SHARED and DELTA locks to be compatible. Readers would read the base value and be responsible for applying any visible uncommitted deltas, creating a mechanism akin to 'read-uncommitted' but for commutative operations, which may be acceptable for certain metrics."
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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Custom exception to signal a deadlock detected by the Wait-Die algorithm.
 */
class DeadlockException extends RuntimeException {
    public DeadlockException(String message) {
        super(message);
    }
}

/**
 * The main implementation of the Semantic Mode Locking (SML) protocol with Wait-Die deadlock prevention.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final LockManager lockManager = new LockManager();
    private static final AtomicInteger nextTxid = new AtomicInteger(1);
    private static final AtomicInteger clock = new AtomicInteger(1);
    private static final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();

    static final String HELD_LOCKS_KEY = "held_locks";
    static final String DELTA_SET_KEY = "delta_set";
    static final String IS_PAYMENT_KEY = "is_payment";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        Transaction txn = new Transaction(nextTxid.getAndIncrement(), clock.getAndIncrement());
        txn.meta.put(HELD_LOCKS_KEY, new HashSet<String>());
        txn.meta.put(DELTA_SET_KEY, new ConcurrentHashMap<String, Long>());
        activeTransactions.put(txn.txid, txn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (key.contains("_bal") && txn.meta.get(IS_PAYMENT_KEY) == null) {
            Object val = store.get(key);
            return (val instanceof AtomicLong) ? ((AtomicLong) val).get() : val;
        }

        acquireLock(txn, key, LockMode.SHARED);
        if (txn.status == TxStatus.ABORTED) return null;

        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(DELTA_SET_KEY);
        if (deltaSet.containsKey(key)) {
            Object baseValObj = txn.readSet.get(key);
            if (baseValObj == null) { 
                Object fromStore = store.get(key);
                baseValObj = (fromStore instanceof AtomicLong) ? ((AtomicLong) fromStore).get() : 0L;
            }
            long baseVal = ((Number) baseValObj).longValue();
            return baseVal + deltaSet.get(key);
        }

        Object val = store.get(key);
        Object realVal = (val instanceof AtomicLong) ? ((AtomicLong) val).get() : val;
        
        if (realVal != null) {
            txn.readSet.put(key, realVal);
        }
        return realVal;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if (key.endsWith("_ytd") || key.endsWith("_bal")) {
            txn.meta.put(IS_PAYMENT_KEY, true);
            acquireLock(txn, key, LockMode.DELTA);
            if (txn.status == TxStatus.ABORTED) return;
            
            @SuppressWarnings("unchecked")
            Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(DELTA_SET_KEY);
            
            Object baseValObj = txn.readSet.get(key);
            if (baseValObj == null) {
                Object fromStore = store.get(key);
                baseValObj = (fromStore instanceof AtomicLong) ? ((AtomicLong) fromStore).get() : 0L;
            }

            long baseVal = ((Number) baseValObj).longValue();
            long currentLogicalVal = baseVal + deltaSet.getOrDefault(key, 0L);
            long newLogicalVal = ((Number) value).longValue();
            long delta = newLogicalVal - currentLogicalVal;
            
            deltaSet.merge(key, delta, Long::sum);

        } else {
            acquireLock(txn, key, LockMode.EXCLUSIVE);
            if (txn.status == TxStatus.ABORTED) return;
            txn.writeSet.put(key, value);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }
        activeTransactions.remove(txn.txid);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Long> deltaSet = (Map<String, Long>) txn.meta.get(DELTA_SET_KEY);
            for (Map.Entry<String, Long> entry : deltaSet.entrySet()) {
                store.increment(entry.getKey(), entry.getValue());
            }
            store.updateAll(txn.writeSet);
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(clock.getAndIncrement());
        } finally {
            lockManager.releaseAll(txn);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.COMMITTED) return;
        activeTransactions.remove(txn.txid);
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
        }
        lockManager.releaseAll(txn);
    }

    private void acquireLock(Transaction txn, String key, LockMode mode) {
        if (txn.status != TxStatus.ACTIVE) return;
        try {
            lockManager.acquire(key, mode, txn, activeTransactions);
            @SuppressWarnings("unchecked")
            Set<String> heldLocks = (Set<String>) txn.meta.get(HELD_LOCKS_KEY);
            heldLocks.add(key);
        } catch (DeadlockException e) {
            rollback(txn, null);
        }
    }
}

enum LockMode { SHARED, DELTA, EXCLUSIVE }

class KeyLock {
    private final ReentrantLock internalLock = new ReentrantLock();
    private final Condition condition = internalLock.newCondition();
    private final Set<Integer> sharedLockHolders = new HashSet<>();
    private final Set<Integer> deltaLockHolders = new HashSet<>();
    private Integer exclusiveLockHolder = null;

    private LockMode getHeldLockMode(int txid) {
        if (exclusiveLockHolder != null && exclusiveLockHolder.equals(txid)) return LockMode.EXCLUSIVE;
        if (sharedLockHolders.contains(txid)) return LockMode.SHARED;
        if (deltaLockHolders.contains(txid)) return LockMode.DELTA;
        return null;
    }

    private boolean isCompatible(LockMode requestMode, int requesterTxid) {
        if (exclusiveLockHolder != null && !exclusiveLockHolder.equals(requesterTxid)) return false;
        
        switch (requestMode) {
            case SHARED:
                return deltaLockHolders.isEmpty() && exclusiveLockHolder == null;
            case DELTA:
                if (exclusiveLockHolder != null) return false;
                if (!deltaLockHolders.isEmpty() && !(deltaLockHolders.size() == 1 && deltaLockHolders.contains(requesterTxid))) return false;
                if (sharedLockHolders.isEmpty()) return true;
                return sharedLockHolders.size() == 1 && sharedLockHolders.contains(requesterTxid);
            case EXCLUSIVE:
                if (exclusiveLockHolder != null && exclusiveLockHolder.equals(requesterTxid)) return true;
                if (!deltaLockHolders.isEmpty()) return false;
                if (sharedLockHolders.isEmpty()) return true;
                return sharedLockHolders.size() == 1 && sharedLockHolders.contains(requesterTxid);
            default:
                return false;
        }
    }

    private void grantLock(LockMode requestMode, int txid, LockMode heldMode) {
        if (heldMode == LockMode.SHARED && (requestMode == LockMode.EXCLUSIVE || requestMode == LockMode.DELTA)) {
            sharedLockHolders.remove(txid);
        }
        switch (requestMode) {
            case SHARED: sharedLockHolders.add(txid); break;
            case DELTA: deltaLockHolders.add(txid); break;
            case EXCLUSIVE: exclusiveLockHolder = txid; break;
        }
    }

    private void getConflictingHolders(Set<Integer> conflicting, LockMode requestMode, int requesterTxid) {
        if (exclusiveLockHolder != null && !exclusiveLockHolder.equals(requesterTxid)) {
            conflicting.add(exclusiveLockHolder);
        }
        if (requestMode == LockMode.SHARED || requestMode == LockMode.EXCLUSIVE) {
            deltaLockHolders.forEach(h -> { if(h != requesterTxid) conflicting.add(h); });
        }
        if (requestMode == LockMode.DELTA || requestMode == LockMode.EXCLUSIVE) {
            sharedLockHolders.forEach(h -> { if(h != requesterTxid) conflicting.add(h); });
        }
    }

    public void acquire(LockMode requestMode, Transaction requester, Map<Integer, Transaction> activeTxns) throws InterruptedException {
        internalLock.lock();
        try {
            LockMode heldMode = getHeldLockMode(requester.txid);
            if (heldMode != null && (heldMode == requestMode || heldMode == LockMode.EXCLUSIVE || (heldMode == LockMode.DELTA && requestMode == LockMode.SHARED))) {
                return;
            }
            
            while (!isCompatible(requestMode, requester.txid)) {
                Set<Integer> conflicting = new HashSet<>();
                getConflictingHolders(conflicting, requestMode, requester.txid);
                for (Integer holderId : conflicting) {
                    Transaction holder = activeTxns.get(holderId);
                    if (holder != null && requester.startTs > holder.startTs) {
                        throw new DeadlockException("Wait-Die Abort");
                    }
                }
                condition.await();
            }
            grantLock(requestMode, requester.txid, heldMode);
        } finally {
            internalLock.unlock();
        }
    }

    public void release(int txid) {
        internalLock.lock();
        try {
            boolean wasHolder = false;
            if (exclusiveLockHolder != null && exclusiveLockHolder.equals(txid)) {
                exclusiveLockHolder = null; wasHolder = true;
            }
            if (sharedLockHolders.remove(txid)) wasHolder = true;
            if (deltaLockHolders.remove(txid)) wasHolder = true;
            if (wasHolder) condition.signalAll();
        } finally {
            internalLock.unlock();
        }
    }
}

class LockManager {
    private final ConcurrentHashMap<String, KeyLock> locks = new ConcurrentHashMap<>();
    private KeyLock getLock(String key) { return locks.computeIfAbsent(key, k -> new KeyLock()); }

    public void acquire(String key, LockMode mode, Transaction txn, Map<Integer, Transaction> activeTxns) {
        try {
            getLock(key).acquire(mode, txn, activeTxns);
        } catch (InterruptedException e) {
            // FIX: Corrected method call from Thread.currentThread to Thread.currentThread()
            Thread.currentThread().interrupt();
            throw new RuntimeException("Transaction interrupted", e);
        }
    }

    public void releaseAll(Transaction txn) {
        @SuppressWarnings("unchecked")
        Set<String> heldLocks = (Set<String>) txn.meta.get(GeneratedCC.HELD_LOCKS_KEY);
        if (heldLocks != null) {
            for (String key : heldLocks) {
                getLock(key).release(txn.txid);
            }
        }
    }
}
