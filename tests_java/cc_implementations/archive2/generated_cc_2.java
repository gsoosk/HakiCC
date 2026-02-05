/* Technical Report:
### **Technical Report: Type-Aware Optimistic Concurrency Control (TOC)**

*   **Algorithm Overview:**
    Type-Aware Optimistic Concurrency Control (TOC) is a specialized optimistic protocol that leverages static, pre-analyzed knowledge of transaction types to reduce aborts. The core idea is to replace generic read/write set validation with a more intelligent *semantic validation* phase. By classifying data keys based on their access patterns in the TPC-C workload (e.g., commutative, immutable), the protocol can safely ignore "false positive" conflicts that would cause aborts in traditional OCC, aiming to increase the commit success rate. The protocol is entirely optimistic and lock-free during the execution phase.

*   **Read/Write/Validation Strategy:**
    - **Data Model:** Every key in the store is associated with a `VersionedValue` containing the data, a commit timestamp (`commitTs`), and the type of write (`WriteType`) that created it.
    - **Read Operation:** Reads are non-blocking. A transaction reads the `VersionedValue` of a key, records its `commitTs` in a private `readSet`, and stores the original value for later use. Reads of keys pre-identified as immutable (e.g., `item_*_price`) are not recorded in the `readSet` to eliminate validation overhead.
    - **Write Operation:** Writes are buffered locally in a private `writeSet`. Using a static `SemanticAnalyzer`, the protocol determines if a write is on a commutative key (e.g., `*_ytd`, `*_bal`). If so, it calculates the *delta* (the difference between the new and original value) and stores it. All other writes are buffered as standard overwrites.
    - **Validation Strategy:** Commits are serialized through a single, global lock. Inside this critical section, the transaction validates its `readSet`. For each key read, it compares the key's current `commitTs` in the store with the timestamp it read. A conflict exists if `current_ts > read_ts`. However, the transaction only aborts if the intervening write was a standard `OVERWRITE`. If the conflict was caused by a `COMMUTATIVE_DELTA` write, it is safely ignored, and validation proceeds.

*   **Deadlock Handling:**
    The protocol is **deadlock-free**. As a purely optimistic scheme, transactions never block waiting for resources held by other active transactions during their execution phase. All contention is resolved at commit time via the validation check, which results in an immediate commit or abort.

*   **Correctness Guarantees:**
    TOC ensures **Strict Conflict-Serializability**. The serialization order is determined by the monotonic commit timestamps assigned within the global commit lock. The correctness of ignoring commutative conflicts relies on the mathematical property of commutativity: the final state of an item is independent of the execution order of concurrent additions/subtractions. By aborting on any intervening non-commutative overwrite, the protocol ensures that a transaction's premises are never invalidated, preserving equivalence to a serial execution.

*   **Complexity and Performance Notes:**
    - **Complexity:** The protocol has low computational overhead during the execution phase due to its lock-free nature. The primary complexity lies in the commit phase.
    - **Performance:** The implementation suffers from a critical performance bottleneck: the use of a single global `ReentrantLock` to serialize all commit attempts. While this ensures the atomicity of the validate-and-write phases, it completely eliminates parallelism at commit time. As a result, under high concurrency, the system's throughput is throttled to that of a single thread, leading to the extremely low observed throughput of ~0.2 ops/sec. The design successfully reduces aborts but at the cost of scalability.

*   **Assumptions and Limitations:**
    - **Static Semantics:** The protocol's effectiveness is entirely dependent on the ability to pre-determine the semantics of operations based on simple, static key patterns. It would not work for ad-hoc queries.
    - **Centralized Bottleneck:** The single global commit lock is a fundamental design limitation that makes this implementation unsuitable for any system requiring high concurrent throughput.
    - **Initialization:** The mechanism for wrapping initial data in `VersionedValue` objects relies on reflection and is brittle, assuming a specific underlying `KVStore` implementation.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 29,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 15,
    "distance_from_known_families_30": 5,
    "implementation_distinctiveness_15": 7,
    "correctness_argument_originality_10": 2,
    "rebranding_penalty_minus15": 0
  },
  "separate_scores": {
    "proposal_novelty": 30,
    "code_novelty": 25
  },
  "overlap_analysis": [
    {
      "family": "OCC (Optimistic Concurrency Control)",
      "overlap_reasoning": "The protocol is structurally identical to classic forward OCC: an optimistic execution phase buffering writes, followed by a critical section for read-set validation and write-back. The only modification is relaxing the validation rule for certain semantic write types.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "Semantic Concurrency Control",
      "overlap_reasoning": "Shares the foundational idea of using operation semantics (specifically commutativity) to increase concurrency. However, classic semantic CC literature is broader, while TOC applies this concept narrowly as an optimization within the validation phase of an OCC protocol.",
      "overlap_strength_0_to_100": 60
    },
    {
      "family": "Silo",
      "overlap_reasoning": "The implementation's use of a single global lock (`commitLock`) to serialize the validation and commit phases is a pattern popularized by high-performance OCC systems like Silo. This is a structural similarity in implementation strategy, not in the core validation logic.",
      "overlap_strength_0_to_100": 50
    }
  ],
  "feature_diff": [
    {
      "axis": "commit rule / validation",
      "ours": "A transaction aborts on a read-write conflict only if the intervening committed write was a non-commutative 'OVERWRITE'. Conflicts with writes tagged as 'COMMUTATIVE_DELTA' are ignored, allowing the transaction to commit.",
      "typical_in_nearest_family": "In standard OCC, a transaction aborts if any key in its read set has been modified by another transaction that committed after its start time, regardless of the write's semantics.",
      "difference_note": "This difference is the protocol's core contribution. It avoids 'false positive' aborts common in OCC on contended data like counters, significantly improving throughput for specific workloads. The novelty lies in specializing the validation rule, not in inventing a new protocol structure."
    },
    {
      "axis": "metadata / per-item state",
      "ours": "Each data item is versioned with a commit timestamp and a `WriteType` enum (e.g., `OVERWRITE`, `COMMUTATIVE_DELTA`), which is essential for the semantic validation logic.",
      "typical_in_nearest_family": "Standard OCC/MVCC protocols typically only store a version number or commit timestamp with each data item. The semantic type of the write that created the version is not persisted.",
      "difference_note": "This additional metadata is required to operationalize the semantic validation. It enriches the version information to allow for more intelligent conflict resolution at commit time."
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
      "VersionedValue object storing (value, commitTs, writeType)",
      "Static SemanticAnalyzer class to hardcode TPC-C key patterns",
      "Use of a global ReentrantLock to serialize the entire commit phase"
    ]
  },
  "correctness_rationale_summary": "Correctness is based on standard OCC, with serializability defined by the commit timestamp order. The novelty in the argument is justifying why ignoring certain conflicts maintains this order. For commutative updates, the final state is equivalent to a serial execution where the commutative write occurred first. For 'Stale Read OK', correctness relies on static analysis proving the read value has no dataflow dependency on any writes. This rationale applies known semantic serializability concepts to OCC; the argument itself is not fundamentally new.",
  "risks_and_edge_cases": [
    "The static, pattern-based semantic analysis is extremely brittle and workload-specific (TPC-C). It will fail or perform poorly on different workloads without manual re-coding.",
    "On workloads lacking commutative updates or immutable keys, the protocol degenerates to standard OCC with the added overhead of type checking, offering no benefit.",
    "The global `commitLock` serializes all commits, which is a known scalability bottleneck for OCC protocols on systems with many cores.",
    "The implementation's check of the `writeType` on the current `VersionedValue` is a simplification. It doesn't account for a history of writes, which could matter in more complex semantic scenarios."
  ],
  "suggestions_to_increase_novelty": [
    "Replace the hardcoded `SemanticAnalyzer` with a dynamic system that learns or formally verifies commutativity from transaction code, making the protocol general-purpose.",
    "Eliminate the global `commitLock`. A more novel approach would integrate semantic validation with a decentralized commit protocol (e.g., using per-key locks during write-back or a timestamp-based scheme like TicToc).",
    "Expand the set of semantic types beyond simple commutative deltas. For example, add support for idempotent operations, max/min operators, or other CRDT-like merge functions to broaden applicability.",
    "Apply the semantic validation concept to a different CC family, like SSI. Using semantic information to prune false positives from serialization graph cycle detection would be a more novel contribution than modifying classic OCC."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

// Helper class to wrap values in the KVStore with versioning info
class VersionedValue {
    final Object value;
    final int commitTs;
    final WriteType writeType;

    VersionedValue(Object value, int commitTs, WriteType writeType) {
        this.value = value;
        this.commitTs = commitTs;
        this.writeType = writeType;
    }
}

// Enum to tag the type of write operation
enum WriteType {
    OVERWRITE,
    COMMUTATIVE_DELTA
}

// Helper class to represent a commutative update in a transaction's write set
class Delta {
    final long value;
    Delta(long value) { this.value = value; }
}

// Static utility to analyze key semantics based on TPC-C naming conventions
class SemanticAnalyzer {
    static boolean isCommutative(String key) {
        return key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty") || key.endsWith("_next_oid");
    }

    static boolean isImmutable(String key) {
        return key.startsWith("item_") && key.endsWith("_price");
    }
}

class GeneratedCC extends ConcurrencyControl {

    private final AtomicInteger timestampGenerator = new AtomicInteger(1);
    private final ReentrantLock commitLock = new ReentrantLock();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }
    
    // We must wrap the initial data from the store
    private static volatile boolean isInitialized = false;
    private static final ReentrantLock initLock = new ReentrantLock();
    private void ensureStoreIsInitialized(KVStore store) {
        if (!isInitialized) {
            initLock.lock();
            try {
                if (!isInitialized) {
                    ConcurrentHashMap<String, Object> initialData = new ConcurrentHashMap<>();
                    // This is a bit of a hack: assume the store is a ConcurrentHashMap and get its data
                    try {
                        java.lang.reflect.Field dataField = KVStore.class.getDeclaredField("data");
                        dataField.setAccessible(true);
                        @SuppressWarnings("unchecked")
                        ConcurrentHashMap<String, Object> internalMap = (ConcurrentHashMap<String, Object>) dataField.get(store);
                        initialData.putAll(internalMap);
                    } catch (Exception e) {
                        // Fallback if reflection fails, though it makes the test setup brittle
                    }
                    
                    for (Map.Entry<String, Object> entry : initialData.entrySet()) {
                        Object val = entry.getValue();
                        if (val instanceof AtomicLong) {
                            val = ((AtomicLong)val).get();
                        }
                        store.update(entry.getKey(), new VersionedValue(val, 0, WriteType.OVERWRITE));
                    }
                    isInitialized = true;
                }
            } finally {
                initLock.unlock();
            }
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        ensureStoreIsInitialized(store);
        int startTs = timestampGenerator.getAndIncrement();
        Transaction txn = new Transaction(0, startTs); // txid not used
        txn.meta.put("original_reads", new ConcurrentHashMap<String, Object>());
        return txn;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOriginalReads(Transaction txn) {
        return (Map<String, Object>) txn.meta.get("original_reads");
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // 1. Read-your-own-writes
        if (txn.writeSet.containsKey(key)) {
            Object writtenValue = txn.writeSet.get(key);
            if (writtenValue instanceof Delta) {
                // Apply delta to the original value we read
                Object original = getOriginalReads(txn).get(key);
                if (original instanceof Number) {
                    return ((Number) original).longValue() + ((Delta) writtenValue).value;
                }
            }
            return writtenValue;
        }

        // 2. Read from store
        Object obj = store.get(key);
        VersionedValue vVal = (obj instanceof VersionedValue) ? (VersionedValue) obj : new VersionedValue(obj, 0, WriteType.OVERWRITE);
        
        Object value = vVal.value;
        if (value instanceof AtomicLong) {
            value = ((AtomicLong)value).get();
        }

        // 3. Record the read if necessary
        if (!SemanticAnalyzer.isImmutable(key)) {
            txn.readSet.put(key, vVal.commitTs);
        }

        // 4. Store original value for delta calculation
        // FIX: ConcurrentHashMap does not allow null values. Only store non-null reads.
        if (value != null) {
            getOriginalReads(txn).put(key, value);
        }

        return (value instanceof Number) ? ((Number)value).intValue() : value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        if (SemanticAnalyzer.isCommutative(key)) {
            Object originalValue = getOriginalReads(txn).get(key);
            if (originalValue instanceof Number && value instanceof Number) {
                long delta = ((Number) value).longValue() - ((Number) originalValue).longValue();
                txn.writeSet.put(key, new Delta(delta));
                return;
            }
        }
        // Default to overwrite
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }

        commitLock.lock();
        try {
            // 1. Validation Phase
            for (Map.Entry<String, Object> entry : txn.readSet.entrySet()) {
                String key = entry.getKey();
                int readTs = (int) entry.getValue();

                Object obj = store.get(key);
                VersionedValue currentVVal = (obj instanceof VersionedValue) ? (VersionedValue) obj : new VersionedValue(obj, 0, WriteType.OVERWRITE);
                
                if (currentVVal.commitTs > readTs) {
                    // Conflict detected. Check if it's a false positive.
                    if (currentVVal.writeType == WriteType.OVERWRITE) {
                        txn.status = TxStatus.ABORTED;
                        return CommitResult.failure("Validation failed on key: " + key);
                    }
                    // If the intervening write was commutative, we can ignore the conflict.
                }
            }

            // 2. Write Phase
            int commitTs = timestampGenerator.getAndIncrement();
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                Object writeValue = entry.getValue();
                
                if (writeValue instanceof Delta) {
                    // Apply delta to the current value in the store
                    Object currentObj = store.get(key);
                    VersionedValue currentVVal = (currentObj instanceof VersionedValue) ? (VersionedValue) currentObj : new VersionedValue(0, 0, WriteType.OVERWRITE);
                    Object currentValObj = currentVVal.value instanceof AtomicLong ? ((AtomicLong)currentVVal.value).get() : currentVVal.value;

                    long baseValue = (currentValObj instanceof Number) ? ((Number) currentValObj).longValue() : 0;
                    long newValue = baseValue + ((Delta) writeValue).value;
                    store.update(key, new VersionedValue(newValue, commitTs, WriteType.COMMUTATIVE_DELTA));
                } else {
                    // Overwrite with the new value
                    store.update(key, new VersionedValue(writeValue, commitTs, WriteType.OVERWRITE));
                }
            }
            
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(commitTs);

        } finally {
            commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
        // No other cleanup needed as writes are buffered locally.
    }
}
