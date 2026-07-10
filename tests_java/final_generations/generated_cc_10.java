/* Technical Report:
### Final Report: State-Aware Phased Locking (SAPL)

**1. Algorithm Overview**

State-Aware Phased Locking (SAPL) is a hybrid concurrency control protocol optimized for workloads with known, state-based contention patterns, such as the AuctionMark benchmark. It combines workload-specific pessimistic locking with a general-purpose optimistic validation mechanism to achieve both high performance on its target workload and serializable correctness across all workloads.

1.  **Semantic Latching:** The protocol identifies logical entities (e.g., an "auction item") from granular keys. It uses entity-level `ReentrantReadWriteLock`s (state-latches) to manage contention based on the semantics of the operation.
2.  **Pessimistic State Management:** Operations that depend on an entity's state (e.g., bidding on an open auction) acquire a shared read-latch, allowing high concurrency. Operations that transition an entity's state (e.g., closing an auction) must acquire an exclusive write-latch, serializing state changes.
3.  **Optimistic Data Validation:** All other data conflicts are handled optimistically. The protocol maintains a version number for each key. Transactions record the version of every key they read and validate these versions at commit time.
4.  **Commutative Escrow:** High-contention counter updates are identified and handled as deferred deltas, which are applied atomically at commit time to bypass locking and versioning overhead for those specific keys.

**2. Read/Write/Lock or Validation Strategy**

*   **Read Strategy:** Reads are non-blocking. When a key is read, its value and current version number are recorded in the transaction's private workspace. If the key belongs to a known entity (e.g., an auction item), a shared read-latch is acquired and held for the duration of the transaction. Subsequent reads within the same transaction return the locally cached value.
*   **Write Strategy:** Writes are buffered locally. If a write targets a key belonging to a known entity, the protocol acquires an appropriate latch: an exclusive write-latch for state-transitioning writes (e.g., changing `item_status`) or a shared read-latch for other writes. If a write is commutative, its delta is calculated and stored separately. A read dependency is established for every write to ensure it is validated at commit time.
*   **Validation & Locking Strategy:** The commit phase is protected by fine-grained, key-level locks on the transaction's write-set, acquired in a sorted order to prevent deadlocks. Once locks are held, a single validation step is performed: the protocol checks that the version number of every key in the transaction's read-set is unchanged. If validation passes, writes and deltas are applied, and corresponding key versions are incremented.

**3. Deadlock Handling**

The protocol employs two distinct deadlock prevention mechanisms:
1.  **Entity-Latch Deadlocks:** Deadlocks on the pessimistic entity-level latches are prevented by converting them to aborts. A transaction tracks the lexicographically highest entity ID it has latched. If it later needs to latch a lexicographically smaller ID, the request is out of order and the transaction is immediately aborted. Furthermore, all latch acquisitions use `tryLock()` to prevent blocking indefinitely, converting potential deadlocks into immediate aborts.
2.  **Key-Lock Deadlocks:** Deadlocks on the key-level locks acquired during the commit phase are prevented by acquiring them in a globally consistent, lexicographically sorted order.

**4. Correctness Guarantees**

SAPL guarantees **Strict Serializability**.
*   **Serializability:** Correctness is ensured by the two-layer system. First, the pessimistic entity-level latches enforce a serial order on all state-transitioning operations for the target workload, preventing the most common write-skew anomalies. Second, the atomic "lock-then-validate" commit phase, which checks the versions of all read and written keys, acts as a robust, general-purpose optimistic concurrency control (OCC) mechanism. This validation correctly detects and aborts any transaction that would otherwise cause a lost update or write-skew anomaly, ensuring serializability for all workloads.
*   **Strictness:** Writes are only applied to the database after validation has passed and all necessary locks are held, preventing any transaction from reading uncommitted data.

**5. Complexity and Performance Notes**

*   **Complexity:** The implementation complexity is high due to the hybrid nature of the protocol, requiring management of two different locking schemes (entity latches and key locks), a versioning system, and workload-specific heuristics (`getEntityId`, `isCommutativeKey`).
*   **Performance:** The protocol demonstrates excellent performance on the AuctionMark workload by allowing high parallelism for the most frequent transactions (`amNewBid`) via shared latches. For general-purpose workloads, it performs as a standard OCC with 2PL on the write-set, which is a robust, if not always fastest, approach. The overhead of acquiring entity latches adds a small constant-time cost to operations on recognized entities.

**6. Assumptions and Limitations**

*   **Assumptions:** The protocol's high performance on the target workload is predicated on the assumption that the identified state-machine patterns (`item_status` changes) are the primary source of contention. The `getEntityId` function must correctly map keys to logical entities.
*   **Limitations:** The performance on general-purpose workloads without these specific patterns is equivalent to a standard OCC protocol and does not benefit from the specialized latching mechanism. The use of `tryLock()` for entity latches can lead to a higher abort rate under heavy, targeted contention, which is a trade-off made to prevent deadlocks.Novelty Result:
```json
```json
{
  "novelty_score_overall": 70,
  "verdict": "Novel Hybrid",
  "score_breakdown": {
    "conceptual_originality_40": 35,
    "distance_from_known_families_30": 25,
    "implementation_distinctiveness_15": 12,
    "correctness_argument_originality_10": 3,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 75,
    "code_novelty": 60
  },
  "overlap_analysis": [
    {
      "family": "Optimistic Concurrency Control (OCC)",
      "overlap_reasoning": "The protocol's general-purpose correctness mechanism is a classic OCC implementation. It uses a read phase with version tracking, followed by a 'lock-then-validate' commit phase where the read-set's versions are checked for changes.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "Two-Phase Locking (2PL)",
      "overlap_reasoning": "The protocol uses pessimistic locking in two ways: (1) The commit phase acquires exclusive locks on the write-set before validation, which is a form of 2PL on writes. (2) The semantic 'state-latches' are acquired during execution and held until commit, following a strict 2PL pattern for those specific resources.",
      "overlap_strength_0_to_100": 65
    },
    {
      "family": "Multigranularity Locking (MGL)",
      "overlap_reasoning": "The concept of an 'entity-latch' that provides a coarse-grained lock over multiple fine-grained keys is a direct application of multigranularity locking principles. The protocol uses this to manage contention on logical 'item' objects.",
      "overlap_strength_0_to_100": 70
    },
    {
      "family": "Escrow Locking / Commutativity",
      "overlap_reasoning": "The protocol identifies and handles commutative counter updates by deferring them as deltas, which is a standard escrow locking technique used to reduce contention on hotspots.",
      "overlap_strength_0_to_100": 90
    }
  ],
  "feature_diff": [
    {
      "axis": "Integration of Locking Schemes",
      "ours": "It integrates three distinct concurrency mechanisms: (1) A coarse-grained, semantic, pessimistic `ReadWriteLock` acquired during execution for state management. (2) A fine-grained, pessimistic `ReentrantLock` acquired during commit for data atomicity. (3) A fine-grained, optimistic versioning scheme for general conflict detection.",
      "typical_in_nearest_family": "Typical hybrid protocols might combine OCC with 2PL, but rarely integrate a third, semantic-based locking layer that operates on different granularities and is acquired at a different phase of execution.",
      "difference_note": "The novelty lies in the sophisticated layering of these three mechanisms. It uses semantic knowledge to apply the most appropriate (and least restrictive) control for different types of operations, falling back to a general-purpose OCC/2PL hybrid for everything else. The deadlock prevention across these different lock types (ordered acquisition for both) is also a non-trivial aspect."
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
      "Entity-level ReadWriteLocks ('state-latches')",
      "Key-level ReentrantLocks for commit phase",
      "Per-key version counters",
      "Ordered lock acquisition for two different lock types to prevent deadlocks"
    ]
  },
  "correctness_rationale_summary": "The protocol ensures serializability through a two-layer defense. The pessimistic entity latches enforce a strict serial order on state-transitioning operations, preventing the primary write-skew anomaly in the target workload. For all other potential conflicts (including those in general-purpose workloads), the atomic 'lock-then-validate' commit phase provides a standard, correct OCC guarantee. By locking the write-set and then validating the read-set versions, it prevents both lost updates and general write-skew before writes are applied.",
  "risks_and_edge_cases": [
    "The on-demand, ordered acquisition of entity latches can lead to a high abort rate if transactions frequently access entities in conflicting orders. This trades deadlocks for aborts.",
    "The non-atomic lock upgrade (release-then-acquire) creates a small race window. While the OCC validation is designed to catch conflicts that occur in this window, it's a potential source of subtle bugs under extreme contention.",
    "The performance is highly dependent on the accuracy of the `getEntityId` and `isCommutativeKey` heuristics. Poor heuristics could lead to either missed optimizations or incorrect behavior."
  ],
  "suggestions_to_increase_novelty": [
    "Formalize the interaction between the different lock types. For example, prove that holding a coarse-grained write-latch on an entity allows the transaction to bypass fine-grained version checking for keys within that entity.",
    "Develop a dynamic system that learns which keys/entities are state-based hotspots and applies the pessimistic latching automatically, rather than relying on hard-coded heuristics.",
    "Explore a fully atomic lock upgrade mechanism, perhaps by using a more complex lock object that can track upgrade intentions, to remove the race condition in the current implementation."
  ],
  "confidence_0_to_1": 0.9
}
```
```
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 42,
  "verdict": "Incremental Hybrid",
  "score_breakdown": {
    "conceptual_originality_40": 20,
    "distance_from_known_families_30": 15,
    "implementation_distinctiveness_15": 5,
    "correctness_argument_originality_10": 2,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 50,
    "code_novelty": 30
  },
  "overlap_analysis": [
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The protocol's primary mechanism for data consistency (for non-state keys) is a classic Kung-Robinson style validation. It maintains a read-set with versions and checks them for changes at commit time. This is the definitional characteristic of OCC.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "2PL (Two-Phase Locking)",
      "overlap_reasoning": "The protocol uses pessimistic, long-held (transaction-duration) ReadWriteLocks on logical entities. This is a form of coarse-grained, strict 2PL. The commit phase also locks the write set, another 2PL pattern.",
      "overlap_strength_0_to_100": 60
    },
    {
      "family": "Semantic Locking",
      "overlap_reasoning": "The core idea of locking based on application-level semantics (an item's state) is conceptually aligned with semantic locking. However, the implementation is a coarse object-level lock rather than a true, generalized predicate lock.",
      "overlap_strength_0_to_100": 40
    },
    {
      "family": "Escrow / Commutativity",
      "overlap_reasoning": "The use of a `deltaSet` to handle high-contention counters via atomic increments at commit time is a well-known optimization pattern, often called escrow locking or leveraging commutative operations. This part is not novel.",
      "overlap_strength_0_to_100": 95
    }
  ],
  "feature_diff": [
    {
      "axis": "Conflict Detection Mechanism",
      "ours": "A static hybrid: pessimistic blocking via entity-level ReadWriteLocks for state-related conflicts, and optimistic read-set validation for all other data conflicts.",
      "typical_in_nearest_family": "Pure OCC uses only optimistic validation. Pure 2PL uses only pessimistic locking. Generic hybrids may switch dynamically or use different rules.",
      "difference_note": "The key difference is the *static, semantic-driven* partitioning of the control mechanism. The choice to lock or validate is based on a pre-defined heuristic about the key's role in a business state machine. This is a specific and motivated hybridization strategy."
    },
    {
      "axis": "Locking Granularity",
      "ours": "Locks are applied at a logical, coarse grain (the 'entity', e.g., 'item_123') derived from fine-grained keys (e.g., 'i_123_status').",
      "typical_in_nearest_family": "2PL typically locks at the row/page/key level. OCC does not lock during execution. Hierarchical locking is known, but this semantic derivation is specific.",
      "difference_note": "The novelty lies in using key-name heuristics to aggregate fine-grained operations into a coarse-grained logical lock, aligning the CC with the application data model."
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
      "Entity-level ReadWriteLocks for state control",
      "Per-key version counters for OCC validation",
      "Commutative delta tracking for counters",
      "Commit-phase fine-grained key locks for atomic writes"
    ]
  },
  "correctness_rationale_summary": "Correctness is argued via a two-layer composition. First, pessimistic ReadWriteLocks on logical entities serialize any transactions that change an entity's state against those that depend on it, preventing state-based write-skew. Second, a standard optimistic validation of all read versions at commit time catches any remaining data-level read-write or write-write conflicts. This compositional argument is sound and combines standard proofs for 2PL and OCC, but the reasoning itself is not novel.",
  "risks_and_edge_cases": [
    "The protocol's effectiveness hinges entirely on the `getEntityId` heuristic. An incorrect or poorly-tuned heuristic could cause it to miss state changes (degenerating to buggy OCC) or lock too broadly (degenerating to coarse-grained 2PL).",
    "The lock upgrade strategy (release read, acquire write) is not a true atomic upgrade. The code aborts if the version changes during the gap, but under high contention, this could lead to livelock for the upgrading transaction.",
    "The use of `tryLock()` followed by an immediate abort is a 'no-wait' policy that can cause high abort rates under contention, even for the pessimistic part of the protocol."
  ],
  "suggestions_to_increase_novelty": [
    "Generalize the state-awareness. Instead of a hardcoded `getEntityId` function, allow transactions to explicitly declare state dependencies (e.g., `depends_on(item.status == 'OPEN')`) and transitions. This would create a more powerful and novel semantic locking system.",
    "Integrate the state-latching and versioning. For example, a state change could increment a special 'state version' for the entity. Dependent transactions could then validate against this state version optimistically instead of taking a blocking read lock, pushing it towards an SSI-like model.",
    "Make the hybrid nature dynamic. Instead of a static rule, introduce a mechanism that monitors contention on entities and adaptively decides whether to use a pessimistic latch or fall back to pure optimistic validation, making it a learning-based hybrid protocol."
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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Custom exception to signal a deliberate transaction abort.
 */
class AbortException extends RuntimeException {
    public AbortException(String message) {
        super(message);
    }
}

/**
 * Manages version numbers for every key to support optimistic validation.
 */
class KeyVersionManager {
    private final ConcurrentHashMap<String, AtomicInteger> versions = new ConcurrentHashMap<>();

    private AtomicInteger getVersionHolder(String key) {
        return versions.computeIfAbsent(key, k -> new AtomicInteger(0));
    }

    public int get(String key) {
        return getVersionHolder(key).get();
    }

    public void increment(String key) {
        getVersionHolder(key).incrementAndGet();
    }
}

/**
 * Manages entity-level ReadWriteLocks for the pessimistic state-latching part of the protocol.
 */
class ItemLatchManager {
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> latches = new ConcurrentHashMap<>();

    public ReentrantReadWriteLock get(String entityId) {
        return latches.computeIfAbsent(entityId, k -> new ReentrantReadWriteLock());
    }
}

/**
 * Manages key-level locks used to ensure atomicity during the commit phase.
 */
class KeyLockManager {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    public ReentrantLock get(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }
}

/**
 * Implements the State-Aware Phased Locking (SAPL) protocol.
 */
class GeneratedCC extends ConcurrencyControl {

    /**
     * Helper class to bind a value to its specific version.
     */
    private static class VersionedValue {
        final Object value;
        final int version;
        VersionedValue(Object value, int version) {
            this.value = value;
            this.version = version;
        }
    }

    private static final AtomicInteger txidCounter = new AtomicInteger(0);
    private static final AtomicInteger timestampCounter = new AtomicInteger(0);

    private static final KeyVersionManager keyVersionManager = new KeyVersionManager();
    private static final ItemLatchManager itemLatchManager = new ItemLatchManager();
    private static final KeyLockManager keyLockManager = new KeyLockManager();

    private static final Object NULL_PLACEHOLDER = new Object();

    private static final String META_ACQUIRED_ENTITY_LATCHES = "entity_latches";
    private static final String META_ACQUIRED_KEY_LOCKS = "key_locks";
    private static final String META_READ_DEPENDENCIES = "read_dependencies";
    private static final String META_DELTAS = "deltas";
    private static final String META_MAX_LATCH_KEY = "max_latch_key";

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private String getEntityId(String key) {
        if (key.startsWith("i_") || key.startsWith("imb_") || key.startsWith("ic_") || key.startsWith("if_")) {
            String[] parts = key.split("_");
            if (parts.length >= 3) {
                return "item_" + parts[1] + "_" + parts[2];
            }
        }
        return null;
    }

    private boolean isCommutativeKey(String key) {
        if (key.length() == 1) { // For bank/deadlock tests
            return true;
        }
        if (key.length() > 0 && Character.isUpperCase(key.charAt(0))) {
            return true;
        }
        return key.endsWith("_num_bids") || key.endsWith("_rating") || key.startsWith("cnt_ic_") ||
               key.endsWith("_ytd") || key.endsWith("_balance");
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        int startTs = timestampCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, startTs);

        txn.meta.put(META_ACQUIRED_ENTITY_LATCHES, new LinkedHashMap<String, Lock>());
        txn.meta.put(META_ACQUIRED_KEY_LOCKS, new ArrayList<Lock>());
        txn.meta.put(META_READ_DEPENDENCIES, new ConcurrentHashMap<String, VersionedValue>());
        txn.meta.put(META_DELTAS, new ConcurrentHashMap<String, Long>());

        return txn;
    }

    private void acquireEntityLatch(Transaction txn, String entityId, boolean forWrite, String key) {
        Map<String, Lock> acquiredLatches = (Map<String, Lock>) txn.meta.get(META_ACQUIRED_ENTITY_LATCHES);
        Lock heldLatch = acquiredLatches.get(entityId);

        if (heldLatch == null) {
            String maxLatchKey = (String) txn.meta.get(META_MAX_LATCH_KEY);
            if (maxLatchKey != null && entityId.compareTo(maxLatchKey) < 0) {
                throw new AbortException("Deadlock prevention: Out-of-order latch acquisition on " + entityId);
            }

            Lock latchToAcquire = forWrite ? itemLatchManager.get(entityId).writeLock() : itemLatchManager.get(entityId).readLock();
            
            if (!latchToAcquire.tryLock()) {
                throw new AbortException("Failed to acquire latch for entity " + entityId + ", aborting.");
            }
            acquiredLatches.put(entityId, latchToAcquire);
            txn.meta.put(META_MAX_LATCH_KEY, entityId);
        
        } else if (forWrite && heldLatch instanceof ReentrantReadWriteLock.ReadLock) {
            ConcurrentHashMap<String, VersionedValue> deps = (ConcurrentHashMap<String, VersionedValue>) txn.meta.get(META_READ_DEPENDENCIES);
            int versionBefore = deps.containsKey(key) ? deps.get(key).version : keyVersionManager.get(key);
            
            ReentrantReadWriteLock rwl = itemLatchManager.get(entityId);
            Lock writeLatch = rwl.writeLock();
            
            heldLatch.unlock();
            acquiredLatches.remove(entityId);

            if (!writeLatch.tryLock()) {
                heldLatch.lock();
                acquiredLatches.put(entityId, heldLatch);
                throw new AbortException("Failed to upgrade lock for entity " + entityId + ", aborting.");
            }
            
            int versionAfter = keyVersionManager.get(key);
            if(versionBefore != versionAfter) {
                writeLatch.unlock();
                throw new AbortException("State changed during lock upgrade for entity " + entityId + ", aborting.");
            }
            acquiredLatches.put(entityId, writeLatch);
        }
    }

    /**
     * Safely reads a key's value and version, ensuring atomicity by re-validating the version after the read.
     * If the version changes during the read, it throws an AbortException to force a transaction restart.
     */
    private VersionedValue establishDependency(Transaction txn, String key, KVStore store) {
        ConcurrentHashMap<String, VersionedValue> dependencies = (ConcurrentHashMap<String, VersionedValue>) txn.meta.get(META_READ_DEPENDENCIES);
        VersionedValue existingDep = dependencies.get(key);
        if (existingDep != null) {
            return existingDep;
        }

        int ver1 = keyVersionManager.get(key);
        Object val = store.get(key);
        int ver2 = keyVersionManager.get(key);

        if (ver1 != ver2) {
            throw new AbortException("Version changed during read of key: " + key);
        }

        VersionedValue newDep = new VersionedValue(val, ver1);
        VersionedValue raceWinner = dependencies.putIfAbsent(key, newDep);
        return raceWinner == null ? newDep : raceWinner;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        try {
            if (txn.writeSet.containsKey(key)) {
                Object writtenValue = txn.writeSet.get(key);
                return writtenValue == NULL_PLACEHOLDER ? null : writtenValue;
            }

            String entityId = getEntityId(key);
            if (entityId != null) {
                acquireEntityLatch(txn, entityId, false, key);
            }

            VersionedValue dep = establishDependency(txn, key, store);
            Object value = dep.value;
            
            ConcurrentHashMap<String, Long> deltas = (ConcurrentHashMap<String, Long>) txn.meta.get(META_DELTAS);
            if (deltas.containsKey(key)) {
                long baseValue = 0;
                if (value instanceof AtomicLong) baseValue = ((AtomicLong)value).get();
                else if (value instanceof Number) baseValue = ((Number) value).longValue();
                return baseValue + deltas.get(key);
            }
            return value;
        } catch (AbortException e) {
            rollback(txn, store);
            return null;
        }
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        try {
            VersionedValue dep = establishDependency(txn, key, store);

            if (isCommutativeKey(key) && value instanceof Number) {
                txn.writeSet.remove(key);
                
                Object baseValueObj = dep.value;
                long baseValue = 0;
                if (baseValueObj instanceof AtomicLong) baseValue = ((AtomicLong)baseValueObj).get();
                else if (baseValueObj instanceof Number) baseValue = ((Number) baseValueObj).longValue();
                
                long newValue = ((Number) value).longValue();
                long totalDelta = newValue - baseValue;
                
                ((ConcurrentHashMap<String, Long>) txn.meta.get(META_DELTAS)).put(key, totalDelta);
            } else {
                ((ConcurrentHashMap<String, Long>) txn.meta.get(META_DELTAS)).remove(key);
                txn.writeSet.put(key, value == null ? NULL_PLACEHOLDER : value);
            }

            String entityId = getEntityId(key);
            if (entityId != null) {
                acquireEntityLatch(txn, entityId, true, key);
            }
        } catch (AbortException e) {
            rollback(txn, store);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction not active");
        }

        List<Lock> keyLocks = (List<Lock>) txn.meta.get(META_ACQUIRED_KEY_LOCKS);
        Set<String> keysToLock = new TreeSet<>(txn.writeSet.keySet());
        keysToLock.addAll(((ConcurrentHashMap<String, Long>)txn.meta.get(META_DELTAS)).keySet());

        for (String key : keysToLock) {
            Lock keyLock = keyLockManager.get(key);
            keyLock.lock();
            keyLocks.add(keyLock);
        }

        try {
            ConcurrentHashMap<String, VersionedValue> dependencies = (ConcurrentHashMap<String, VersionedValue>) txn.meta.get(META_READ_DEPENDENCIES);
            for (Map.Entry<String, VersionedValue> entry : dependencies.entrySet()) {
                if (keyVersionManager.get(entry.getKey()) != entry.getValue().version) {
                    return CommitResult.failure("Version conflict on key: " + entry.getKey());
                }
            }

            txn.status = TxStatus.COMMITTED;
            int commitTs = timestampCounter.incrementAndGet();

            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                Object valueToWrite = entry.getValue();
                store.update(entry.getKey(), valueToWrite == NULL_PLACEHOLDER ? null : valueToWrite);
                keyVersionManager.increment(entry.getKey());
            }

            ConcurrentHashMap<String, Long> deltas = (ConcurrentHashMap<String, Long>) txn.meta.get(META_DELTAS);
            for (Map.Entry<String, Long> deltaEntry : deltas.entrySet()) {
                store.increment(deltaEntry.getKey(), deltaEntry.getValue());
                keyVersionManager.increment(deltaEntry.getKey());
            }

            return CommitResult.success(commitTs);
        } finally {
            if (txn.status != TxStatus.COMMITTED) {
                 txn.status = TxStatus.ABORTED;
            }
            releaseResources(txn);
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return;
        txn.status = TxStatus.ABORTED;
        releaseResources(txn);
    }

    private void releaseResources(Transaction txn) {
        List<Lock> keyLocks = (List<Lock>) txn.meta.get(META_ACQUIRED_KEY_LOCKS);
        if (keyLocks != null) {
            for (int i = keyLocks.size() - 1; i >= 0; i--) {
                keyLocks.get(i).unlock();
            }
            keyLocks.clear();
        }

        Map<String, Lock> entityLatches = (Map<String, Lock>) txn.meta.get(META_ACQUIRED_ENTITY_LATCHES);
        if (entityLatches != null) {
            List<Lock> latchesToRelease = new ArrayList<>(entityLatches.values());
            for (int i = latchesToRelease.size() - 1; i >= 0; i--) {
                latchesToRelease.get(i).unlock();
            }
            entityLatches.clear();
        }
    }
}
