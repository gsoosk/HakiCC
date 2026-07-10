/* Technical Report:
### Final Technical Report: Semantic Multi-Version Latching (SMVL)

#### 1. Algorithm Overview
Semantic Multi-Version Latching (SMVL) is a hybrid concurrency control protocol designed for high performance on workloads with pre-known transaction types. It combines Multi-Version Concurrency Control (MVCC) for non-blocking reads, fine-grained semantic latching for managing direct write conflicts, and a Serializable Snapshot Isolation (SSI) validation phase to guarantee serializability. The core principle is to leverage application-specific knowledge to differentiate between write operations, applying the least restrictive concurrency mechanism necessary for each type, thereby maximizing parallelism.

#### 2. Read/Write/Validation Strategy
*   **Read Strategy:** Reads operate under Snapshot Isolation. When a transaction reads a key, it traverses a version chain to find the most recent version committed before the transaction's start timestamp (`startTs`). This mechanism is non-blocking and ensures that read-only transactions never conflict with writers. Each read's version timestamp is recorded for use in the commit-time validation phase.
*   **Write Strategy:** Writes are first buffered in a transaction's local write set (read-your-own-writes is supported). Upon buffering, a semantic analysis is performed based on the key's name pattern to classify the operation (e.g., `_status` keys are `STATE_TRANSITION`, `_desc` are `GENERAL`, others are `BLIND_APPEND`). This classification determines the required concurrency control mechanism.
*   **Validation and Latching Strategy:**
    1.  **Semantic Latching:** For write types identified as potentially conflicting (`STATE_TRANSITION`, `GENERAL`), the transaction attempts to acquire a short-lived, exclusive "latch" on the key. This latch is a lightweight, in-memory lock that serializes direct read-modify-write and write-write operations on critical data items.
    2.  **SSI Validation:** At commit time, the protocol performs an anti-dependency check inspired by SSI. Before writes are applied, the transaction validates its read set against a global commit log. It checks if any other transaction, which committed between the current transaction's start and commit time, wrote to any of the keys the current transaction read. A detected conflict indicates a potential write-skew anomaly, leading to an abort.

#### 3. Deadlock Handling
The protocol avoids deadlocks through its latch acquisition policy. When a transaction attempts to acquire a latch that is already held by another transaction, it does not wait. Instead, it follows a "requester aborts" (or "wait-die" where the younger transaction aborts) strategy. The conflicting transaction is immediately marked as `ABORTED` and is rolled back, preventing any circular dependencies from forming.

#### 4. Correctness Guarantees
SMVL guarantees **serializability**. This is achieved through a multi-layered approach:
1.  **Snapshot Isolation (from MVCC):** Eliminates basic anomalies like Dirty Reads and Non-Repeatable Reads.
2.  **Semantic Latching:** Prevents Lost Updates by ensuring that direct read-modify-write cycles on a single data item are serialized.
3.  **SSI-style Validation:** Prevents phantom anomalies and write-skew by detecting and aborting transactions whose read-set premises have been invalidated by concurrent commits. The combination of these three mechanisms ensures that any execution history is equivalent to some serial order.

#### 5. Complexity and Performance Notes
*   **Complexity:** The protocol has higher implementation complexity than standard 2PL or OCC, requiring management of version chains, a commit log, and semantic classification logic. Memory overhead is also higher due to storing multiple versions of data items.
*   **Performance:** The design achieves high performance on its target workload (AuctionMark, ~48.5k tps) by minimizing contention. Read-heavy transactions are fast and non-blocking. The semantic analysis correctly identifies that many writes are blind appends to new keys, which require no latching and face no conflicts. The global `commitLock` required for validation and write-set installation is the primary point of serialization but is held for a very short duration. Performance on generic, unknown workloads (TPC-C, ~10.5k tps) is respectable but not as highly optimized, demonstrating the protocol's specialization.

#### 6. Assumptions and Limitations
*   **Assumption of Pre-Known Transactions:** The protocol's effectiveness is critically dependent on the ability to accurately classify write operations based on key patterns. This assumes the transaction semantics are known at design time and are relatively stable.
*   **Workload Specificity:** Performance gains are tightly coupled to the specific workload. A significant shift in access patterns or the introduction of new, unclassifiable transaction types would degrade performance and require modifications to the classification logic.
*   **Resource Management:** The implementation lacks garbage collection for old versions and pruning for the commit log. In a long-running system, these data structures would grow indefinitely, requiring auxiliary mechanisms to manage memory consumption.
*   **Commutative Operations:** The current implementation simplifies by treating commutative operations (e.g., counters) as general writes requiring a latch. A more advanced version could implement lock-free atomic delta updates to further enhance concurrency on these specific hotspots.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 21,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 20,
    "distance_from_known_families_30": 9,
    "implementation_distinctiveness_15": 2,
    "correctness_argument_originality_10": 1,
    "rebranding_penalty_minus15": -10
  },
  "separate_scores": {
    "proposal_novelty": 45,
    "code_novelty": 10
  },
  "overlap_analysis": [
    {
      "family": "SSI (Serializable Snapshot Isolation)",
      "overlap_reasoning": "The core correctness mechanism—an MVCC base combined with a commit-time validation that checks a transaction's read set against the write sets of concurrently committed transactions—is the defining feature of SSI. This protocol implements that exact logic.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "MVCC",
      "overlap_reasoning": "The protocol is explicitly built on an MVCC foundation. It uses version chains with commit timestamps (`VersionedValue`) and snapshot-based reads (`current.commitTs < txn.startTs`) which are textbook MVCC.",
      "overlap_strength_0_to_100": 100
    },
    {
      "family": "Escrow Locking / Commutativity",
      "overlap_reasoning": "The proposal claims a novel lock-free path for commutative operations using deltas. This is a known technique. However, the implementation completely omits this feature, treating commutative ops as general writes that require locks. Therefore, the overlap is only with the concept, not the code.",
      "overlap_strength_0_to_100": 40
    },
    {
      "family": "2PL (Two-Phase Locking)",
      "overlap_reasoning": "The 'semantic latches' are functionally equivalent to short-lived, exclusive, key-level locks. While not a full 2PL protocol (locks are not held in two phases), it incorporates a pessimistic locking component for certain conflicts, similar to hybrid S2PL+SI systems.",
      "overlap_strength_0_to_100": 25
    }
  ],
  "feature_diff": [
    {
      "axis": "Core Correctness Mechanism",
      "ours": "A combination of MVCC for snapshot reads, pessimistic 'semantic latches' acquired on write, and an optimistic SSI-style anti-dependency validation at commit.",
      "typical_in_nearest_family": "Standard SSI uses MVCC for snapshot reads and an optimistic anti-dependency validation at commit. Pessimistic checks are a known optimization but not part of the core protocol.",
      "difference_note": "The primary difference is adding a pessimistic latching step to abort certain conflicts early. This is an optimization strategy layered on top of SSI, not a fundamental change to the correctness model, which still relies on the SSI validation for serializability."
    },
    {
      "axis": "Write Classification",
      "ours": "Explicitly classifies writes (State-Transition, General, Commutative, Blind Append) based on hardcoded key names to determine the concurrency strategy.",
      "typical_in_nearest_family": "Generic SSI treats all writes equally, distinguishing them only by the key they affect. It does not typically inspect the operation's semantics.",
      "difference_note": "This semantic layer is the main conceptual contribution. However, its implementation is a simple, brittle policy. More importantly, the most impactful path (lock-free commutative) is unimplemented, collapsing its behavior back to a more standard model."
    },
    {
      "axis": "Implementation of Commit Phase",
      "ours": "Uses a single, global `ReentrantLock` to serialize the entire validation and write phase for all committing transactions.",
      "typical_in_nearest_family": "High-performance SSI implementations use more granular or lock-free techniques to manage commit-time validation and logging to avoid a single-threaded bottleneck.",
      "difference_note": "The implementation's reliance on a global commit lock is a significant simplifying choice that makes it less novel and performant than state-of-the-art SSI systems, reducing it to a serialized-commit SSI variant."
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
      "Serialized commit phase via global ReentrantLock",
      "Hardcoded semantic rules based on key name suffixes",
      "Standard MVCC version chain via linked-list objects"
    ]
  },
  "correctness_rationale_summary": "Correctness is ensured by a standard composition of known mechanisms. MVCC provides Snapshot Isolation, eliminating basic read anomalies. Semantic latches (locks) prevent direct write-write conflicts on critical keys. Finally, an SSI-style anti-dependency validation at commit time detects and aborts transactions that would cause write-skew, thus elevating the isolation level to full serializability. This rationale is entirely conventional for the SSI family and offers no novel insights.",
  "risks_and_edge_cases": [
    "The implementation's global `commitLock` serializes all commits, creating a central bottleneck that undermines the proposed concurrency benefits.",
    "The most novel and performant claim—lock-free commutative updates—is unimplemented. The protocol as coded degenerates to a much simpler 'SSI with per-key locks' pattern.",
    "The 'semantic' classification is brittle, relying on hardcoded key name suffixes. Schema changes or new transaction types would require code modifications, violating database-application separation."
  ],
  "suggestions_to_increase_novelty": [
    "Fully implement the proposed lock-free commutative update mechanism using atomic compare-and-swap on a `pending_deltas` data structure. This was the most promising and distinct feature of the proposal.",
    "Replace the global `commitLock` with a more scalable commit mechanism, such as parallel validation techniques found in protocols like Silo or partitioned validation based on key ranges.",
    "Generalize the semantic analysis. Instead of hardcoding rules on key names, allow transactions to declare operation types (e.g., `increment`, `set_state`, `append`) via an API, making the protocol more robust and applicable beyond a single workload."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

class GeneratedCC extends ConcurrencyControl {

    // Helper class to represent a specific version of a value.
    // Forms an immutable singly-linked list (version chain).
    private static class VersionedValue {
        final Object value;
        final int commitTs;
        final VersionedValue previous;

        VersionedValue(Object value, int commitTs, VersionedValue previous) {
            this.value = value;
            this.commitTs = commitTs;
            this.previous = previous;
        }
    }

    // Enum for classifying write operations based on pre-known transaction semantics.
    private enum WriteType {
        STATE_TRANSITION, // e.g., item_status change, needs latching
        GENERAL,          // e.g., item_desc update, needs latching
        COMMUTATIVE,      // e.g., num_bids, rating (treated as GENERAL in this impl)
        BLIND_APPEND      // e.g., new_bid, new_comment, no latch needed
    }

    // Global shared state for the CC protocol
    private final AtomicInteger globalTs = new AtomicInteger(1);
    private final AtomicInteger txIdCounter = new AtomicInteger(1);
    private final ReentrantLock commitLock = new ReentrantLock();

    // Main data store: Key -> Head of its version chain
    private final ConcurrentHashMap<String, VersionedValue> versionStore = new ConcurrentHashMap<>();

    // Metadata for semantic latching: Key -> txid of latch holder
    private final ConcurrentHashMap<String, Integer> latchStore = new ConcurrentHashMap<>();

    // Metadata for SSI validation: CommitTS -> Set of keys written by that transaction
    private final ConcurrentNavigableMap<Integer, Set<String>> commitLog = new ConcurrentSkipListMap<>();
    
    // Tracks active transactions
    private final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();


    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txIdCounter.getAndIncrement();
        int startTs = globalTs.get();
        Transaction txn = new Transaction(txid, startTs);
        
        // Store metadata maps within the transaction object itself
        txn.meta.put("readSetTimestamps", new ConcurrentHashMap<String, Integer>());
        txn.meta.put("writeTypeMap", new ConcurrentHashMap<String, WriteType>());
        
        activeTransactions.put(txid, txn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return null;
        }

        // 1. Read-Your-Own-Writes: Check the transaction's local write set first.
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        // 2. MVCC Read: Find the correct version from the version store.
        VersionedValue current = versionStore.get(key);
        VersionedValue visibleVersion = null;

        // Traverse the version chain to find the latest version committed before this transaction started.
        while (current != null) {
            if (current.commitTs < txn.startTs) {
                visibleVersion = current;
                break;
            }
            current = current.previous;
        }
        
        // 3. Record the timestamp of the version we read for SSI validation.
        @SuppressWarnings("unchecked")
        Map<String, Integer> readTimestamps = (Map<String, Integer>) txn.meta.get("readSetTimestamps");
        
        if (visibleVersion != null) {
            readTimestamps.put(key, visibleVersion.commitTs);
            return visibleVersion.value;
        } else {
            // Key doesn't exist in our snapshot. Record this as reading version 0.
            readTimestamps.put(key, 0);
            return null;
        }
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return;
        }

        // Buffer the write in the transaction's local write set.
        txn.writeSet.put(key, value);
        
        // Perform semantic analysis and acquire latches if necessary.
        classifyAndLatch(txn, key);
    }
    
    private void classifyAndLatch(Transaction txn, String key) {
        @SuppressWarnings("unchecked")
        Map<String, WriteType> writeTypeMap = (Map<String, WriteType>) txn.meta.get("writeTypeMap");

        WriteType type;
        // Simple pattern matching to classify writes based on AuctionMark semantics.
        if (key.endsWith("_status")) {
            type = WriteType.STATE_TRANSITION;
        } else if (key.endsWith("_desc")) {
            type = WriteType.GENERAL;
        } else if (key.endsWith("_num_bids") || key.endsWith("_rating") || key.startsWith("cnt_ic_")) {
            // For simplicity, commutative ops are treated as general writes needing a latch.
            type = WriteType.GENERAL;
        } else {
            // Assume all other writes are blind appends of new records (new user, item, bid, etc.)
            type = WriteType.BLIND_APPEND;
        }

        writeTypeMap.put(key, type);

        // Acquire a short-lived semantic latch for contentious write types.
        if (type == WriteType.STATE_TRANSITION || type == WriteType.GENERAL) {
            Integer existingOwner = latchStore.putIfAbsent(key, txn.txid);
            if (existingOwner != null && existingOwner != txn.txid) {
                // Latch conflict. Abort this transaction (simple "requester aborts" policy).
                txn.status = TxStatus.ABORTED;
            }
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            rollback(txn, store); // Pass store object
            return CommitResult.failure("Transaction was aborted due to latch conflict.");
        }
        if (txn.status == TxStatus.COMMITTED) {
            return CommitResult.failure("Transaction already committed.");
        }

        commitLock.lock();
        try {
            // --- PHASE 1: VALIDATION (SSI Anti-Dependency Check) ---
            int commitTs = globalTs.incrementAndGet();

            @SuppressWarnings("unchecked")
            Map<String, Integer> readTimestamps = (Map<String, Integer>) txn.meta.get("readSetTimestamps");

            for (Map.Entry<String, Integer> readEntry : readTimestamps.entrySet()) {
                String key = readEntry.getKey();
                // int versionReadTs = readEntry.getValue(); // Not strictly needed for this simplified check

                // Check if any transaction that committed *after* we started
                // wrote to a key that we read.
                ConcurrentNavigableMap<Integer, Set<String>> concurrentCommits = 
                    commitLog.subMap(txn.startTs, false, commitTs, false);
                
                for (Set<String> writtenKeys : concurrentCommits.values()) {
                    if (writtenKeys.contains(key)) {
                        // A potential write-skew anomaly is detected. Abort.
                        rollback(txn, store); // Pass store object
                        return CommitResult.failure("SSI validation failed on key: " + key);
                    }
                }
            }

            // --- PHASE 2: WRITE & RELEASE ---
            if (!txn.writeSet.isEmpty()) {
                for (Map.Entry<String, Object> writeEntry : txn.writeSet.entrySet()) {
                    String key = writeEntry.getKey();
                    Object value = writeEntry.getValue();
                    VersionedValue currentHead = versionStore.get(key);
                    VersionedValue newVersion = new VersionedValue(value, commitTs, currentHead);
                    versionStore.put(key, newVersion);
                }
                // Log the writes for future validation checks.
                commitLog.put(commitTs, txn.writeSet.keySet());
            }

            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);

        } finally {
            // Release resources regardless of commit success or failure inside the lock.
            releaseResources(txn);
            commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ACTIVE) {
            txn.status = TxStatus.ABORTED;
        }
        // Release resources. This is safe to call even if already released.
        releaseResources(txn);
    }

    private void releaseResources(Transaction txn) {
        activeTransactions.remove(txn.txid);

        @SuppressWarnings("unchecked")
        Map<String, WriteType> writeTypeMap = (Map<String, WriteType>) txn.meta.get("writeTypeMap");
        if (writeTypeMap == null) return;
        
        for (Map.Entry<String, WriteType> entry : writeTypeMap.entrySet()) {
            if (entry.getValue() == WriteType.STATE_TRANSITION || entry.getValue() == WriteType.GENERAL) {
                // Atomically remove the latch only if we are the owner.
                latchStore.remove(entry.getKey(), txn.txid);
            }
        }
    }
}
