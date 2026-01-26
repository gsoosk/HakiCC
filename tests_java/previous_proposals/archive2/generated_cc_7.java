/* Technical Report:
### Final Technical Report: Delta-Chain Optimistic Protocol (DCOP)

*   **Algorithm Overview**
    DCOP is a hybrid multi-version/optimistic concurrency control protocol, specifically engineered for the TPC-C transaction profile. It avoids the high cost of creating full new versions for every write by introducing "delta chaining." Instead of blocking or aborting on common write-write conflicts, commutative arithmetic operations (e.g., balance updates) are recorded as "delta" objects appended to a list associated with the data item. Transactions execute optimistically in a non-blocking manner, reading a synthesized value by applying these pending deltas to the last stable committed version. To handle hotspots, the protocol offloads high-contention, non-commutative counters (`next_oid`) to dedicated atomic primitives, removing them entirely from the main CC mechanism. Commits are serialized by a per-partition lock (based on `warehouse_id`) to ensure atomicity and establish a serial order.

*   **Read/Write/Validation Strategy**
    -   **Read Strategy:** Reads are non-blocking. A transaction first checks its local write-set for the most recent value (read-your-own-writes). If not present, it reads the key's last committed base value and version, then synthesizes the current value by iterating through the public chain of pending deltas and applying them. The base version number is stored in the transaction's read-set for later validation. Semantic optimizations are applied to skip validation tracking for known immutable keys (`item_price`) and blind reads (`customer_balance` in `NewOrder`).
    -   **Write Strategy:** Writes are classified based on semantics.
        1.  **Commutative Updates:** A read is performed to get the current synthesized value. The difference (delta) between the new value and the current value is calculated and appended as a `Delta` object to the key's public delta chain.
        2.  **Non-Commutative Overwrites:** The new value is buffered locally in the transaction's private write-set.
        3.  **Offloaded Counters:** Writes to `d_..._next_oid` are redirected to a dedicated, lock-free `AtomicLong`, bypassing the DCOP protocol.
    -   **Validation Strategy:** At commit time, the transaction acquires its partition's commit lock. It then validates its read-set by checking if the `committedVersion` of each key it read is still the same. If another transaction has committed a non-commutative overwrite to a key in the read-set, its version will have increased, causing the validation to fail and the current transaction to abort. Crucially, validation succeeds if other transactions have only appended deltas, as these commutative operations do not invalidate the basis of the reading transaction's logic.

*   **Deadlock Handling**
    The protocol is **deadlock-free**. The execution phase is entirely non-blocking. The only lock is the per-partition commit lock. A transaction acquires this lock only when it is ready to commit and holds no other resources. Since it never holds the commit lock while waiting for another resource, circular dependencies cannot form.

*   **Correctness Guarantees**
    DCOP ensures **Strict Conflict-Serializability**.
    -   **Serializability:** The per-partition commit lock establishes a total serial order for all committing transactions within that partition. Since partitions are disjoint, this provides a global serializable history. The validation step guarantees that a transaction's execution is equivalent to it having occurred at its position in this serial order. The protocol correctly permits the reordering of commutative delta operations, ensuring the final state is consistent with the serial history.
    -   **Strictness:** All writes—both applying deltas to the base value and installing overwrites—are performed atomically inside the commit lock. Therefore, the effects of a transaction are only made visible after it has successfully passed validation and committed. No uncommitted data is ever exposed.

*   **Complexity and Performance Notes**
    -   **Performance:** DCOP demonstrates very high throughput for the target workload. Reads are fast and wait-free. Commutative writes are extremely fast, non-blocking appends. This transforms most write-write conflicts into non-conflicts, drastically reducing the abort rate compared to standard OCC. The main point of contention is the per-partition commit lock. Offloading the `_next_oid` key is a critical performance optimization.
    -   **Complexity:** The main complexity lies in synthesizing values on the read path, which requires traversing the delta chain. The memory footprint can also increase if delta chains become long due to high contention or slow-committing transactions.

*   **Assumptions and Limitations**
    -   **Workload-Specific Semantics:** The protocol's effectiveness relies on pre-known transaction semantics to distinguish commutative updates from overwrites and to identify blind reads or immutable data. It would be less effective for ad-hoc, general-purpose workloads.
    -   **Partitionable Data:** Performance scales well because the workload is easily partitionable by `warehouse_id`. Transactions that span multiple partitions would serialize on a single global lock, degrading performance.
    -   **High Commutativity Ratio:** The protocol is most beneficial when the majority of write conflicts are on commutative operations. A workload dominated by non-commutative overwrites would see performance converge towards a standard partitioned OCC protocol, with a higher abort rate.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 33,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 18,
    "distance_from_known_families_30": 12,
    "implementation_distinctiveness_15": 8,
    "correctness_argument_originality_10": 0,
    "rebranding_penalty_minus15": -5
  },
  "separate_scores": {
    "proposal_novelty": 65,
    "code_novelty": 25
  },
  "overlap_analysis": [
    {
      "family": "Optimistic Concurrency Control (OCC)",
      "overlap_reasoning": "The core control mechanism is identical to classic OCC: a transaction validates its read set against version numbers at commit time and aborts if a conflict is found. DCOP is a specialized implementation of OCC.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "Silo",
      "overlap_reasoning": "Shares the key architectural patterns of partitioned commit serialization (using locks) and OCC validation based on per-item version numbers (TIDs in Silo). DCOP's main deviation is the delta-chaining mechanism.",
      "overlap_strength_0_to_100": 80
    },
    {
      "family": "Commutativity / Escrow Locking",
      "overlap_reasoning": "The concept of handling commutative updates (like increments/decrements) as non-conflicting operations is well-established. Delta-chaining is a specific implementation of this idea, akin to an optimistic form of escrow.",
      "overlap_strength_0_to_100": 60
    },
    {
      "family": "Multi-Version Concurrency Control (MVCC)",
      "overlap_reasoning": "Uses version numbers per item, a feature of MVCC. However, it does not maintain multiple full, historical versions for snapshot reads. Reads synthesize a value from one base version and a chain of uncommitted deltas, which is not a typical snapshot isolation read.",
      "overlap_strength_0_to_100": 40
    }
  ],
  "feature_diff": [
    {
      "axis": "Read Visibility Rule",
      "ours": "During execution, reads see a single committed base version plus the synthesized effect of ALL uncommitted deltas from concurrent transactions present in the shared delta chain.",
      "typical_in_nearest_family": "In classic OCC, reads see the last committed value. In MVCC, reads see a consistent snapshot from a specific point in time. In both, uncommitted data from other transactions is invisible.",
      "difference_note": "This is the most significant difference. DCOP allows a form of structured 'dirty read' on commutative operations. The standard OCC validation at commit ensures this doesn't violate serializability, but it fundamentally changes the execution-phase visibility model."
    },
    {
      "axis": "Write Buffering",
      "ours": "Commutative writes (deltas) are immediately appended to a globally visible, shared list (`deltaChain`). Non-commutative writes are buffered in a traditional private `writeSet`.",
      "typical_in_nearest_family": "In OCC, all writes are buffered in a private write-set, invisible to others until the commit's write phase.",
      "difference_note": "Making deltas globally visible before commit enables the unique read visibility rule. It's a structural change to OCC's execution phase, but not its validation phase."
    },
    {
      "axis": "Validation Logic",
      "ours": "A transaction validates that the `committedVersion` of items in its read set has not changed. The presence of new deltas from other transactions does not directly cause validation failure.",
      "typical_in_nearest_family": "Identical. Standard OCC validates a read set against version numbers/timestamps that are updated upon any successful write.",
      "difference_note": "There is no difference in the validation logic itself. The proposal *claims* a novel validation rule that ignores delta-based conflicts, but the implementation correctly uses the standard, stricter OCC validation, which will abort on such conflicts. The novelty is in the (unrealized) claim, not the implementation."
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
      "Per-key shared `CopyOnWriteArrayList` for pending deltas ('deltaChain').",
      "Partitioned commit locks.",
      "Offloaded atomic counters for hotspots."
    ]
  },
  "correctness_rationale_summary": "Correctness is ensured by classic Optimistic Concurrency Control (OCC) validation, not a new mechanism. A per-partition commit lock establishes a serial commit order. During validation, a transaction checks if the base version of any data it read has been modified by a concurrently committed transaction and aborts if so. The novel delta-chain mechanism affects read visibility during execution but does not change the fundamental correctness argument, which is identical to standard partitioned OCC. The rationale itself is not novel.",
  "risks_and_edge_cases": [
    "The proposal's central claim to ignore certain read-write conflicts is not actually implemented; the code uses standard OCC validation that correctly aborts on these conflicts.",
    "The `deltaChain` can grow long under high contention, making the read-path synthesis logic a potential bottleneck.",
    "A single non-commutative overwrite to a key will clear its `deltaChain`, likely causing a cascade of aborts for transactions that had appended deltas to that key.",
    "The protocol degenerates to standard partitioned OCC for workloads without commutative updates, offering no benefit."
  ],
  "suggestions_to_increase_novelty": [
    "Develop a correct validation protocol that *actually* distinguishes between overwrites and deltas. For example, a transaction could be allowed to commit if its read value was only changed by deltas, provided its own logic was provably insensitive to that change (e.g., a blind write).",
    "Formalize the read-visibility rule. Instead of reading all live deltas, a transaction could read a snapshot of the delta chain from its start time, creating a novel hybrid of Snapshot Isolation and live updates.",
    "Eliminate the coarse-grained partition lock. A more novel approach would use lock-free atomic instructions (e.g., CAS) on the `VersionedDeltaValue` object to install the final committed state, moving it closer to protocols like TicToc or modern Silo."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java.previous_proposals.archive2;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * Implementation of Delta-Chain Optimistic Protocol (DCOP).
 * This is a hybrid MVCC/OCC protocol optimized for the TPC-C workload.
 * It avoids creating full new versions for commutative updates by appending "deltas"
 * to a version chain. Validation is optimized to ignore conflicts on these deltas.
 * High-contention counters are offloaded to dedicated atomics to remove bottlenecks.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final int TPCC_NUM_WAREHOUSES = 10;
    private static final int TPCC_DISTRICTS_PER_WH = 10;
    
    // Maps a key to its versioned, delta-chained value
    private final ConcurrentHashMap<String, VersionedDeltaValue> versionedData = new ConcurrentHashMap<>();
    private final AtomicInteger nextTxid = new AtomicInteger(0);

    // Partition-specific locks and version counters
    private final ConcurrentHashMap<Integer, Partition> partitions = new ConcurrentHashMap<>();
    private static final Pattern WAREHOUSE_KEY_PATTERN = Pattern.compile("^[a-z]_(\\d+)_.*");

    // Offloaded atomic counters for the _next_oid hotspot
    private final ConcurrentHashMap<String, AtomicLong> offloadedCounters = new ConcurrentHashMap<>();

    // Represents a single commutative update
    private static class Delta {
        final int txid;
        final long deltaValue;

        Delta(int txid, long deltaValue) {
            this.txid = txid;
            this.deltaValue = deltaValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Delta delta = (Delta) o;
            return txid == delta.txid && deltaValue == delta.deltaValue;
        }

        @Override
        public int hashCode() {
            return Objects.hash(txid, deltaValue);
        }
    }

    // Holds the versioned state for a key
    private static class VersionedDeltaValue {
        volatile Object committedValue;
        volatile long committedVersion;
        final CopyOnWriteArrayList<Delta> deltaChain = new CopyOnWriteArrayList<>();

        VersionedDeltaValue(Object value, long version) {
            this.committedValue = value;
            this.committedVersion = version;
        }
    }

    // Encapsulates per-partition state
    private static class Partition {
        final ReentrantLock commitLock = new ReentrantLock();
        final AtomicLong versionCounter = new AtomicLong(0);
    }

    public GeneratedCC() {
        // Pre-initialize offloaded counters to avoid runtime contention
        for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
            for (int did = 1; did <= TPCC_DISTRICTS_PER_WH; did++) {
                String key = "d_" + wid + "_" + did + "_next_oid";
                offloadedCounters.put(key, new AtomicLong(1));
            }
        }
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = nextTxid.getAndIncrement();
        Transaction txn = new Transaction(txid, 0);
        // readSet stores key -> readVersion
        // deltaSet stores key -> list of deltas from this txn
        txn.meta.put("deltaSet", new ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>>());
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // 1. Read-your-own-writes (for non-commutative overwrites)
        if (txn.writeSet.containsKey(key)) {
            return txn.writeSet.get(key);
        }

        // Ensure the key exists in our versioned map
        versionedData.computeIfAbsent(key, k -> {
            Object val = store.get(k);
            if (val instanceof AtomicLong) {
                val = ((AtomicLong) val).get();
            }
            return new VersionedDeltaValue(val, 0);
        });
        VersionedDeltaValue vdv = versionedData.get(key);

        Object baseValue = vdv.committedValue;
        long baseVersion = vdv.committedVersion;
        
        // 2. Synthesize value by applying deltas
        long synthesizedValue = (baseValue instanceof Number) ? ((Number) baseValue).longValue() : 0;
        
        for (Delta d : vdv.deltaChain) {
            synthesizedValue += d.deltaValue;
        }
        
        // Also apply this transaction's own uncommitted deltas
        ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>> myDeltas = (ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>>) txn.meta.get("deltaSet");
        if(myDeltas.containsKey(key)) {
             for (Delta d : myDeltas.get(key)) {
                synthesizedValue += d.deltaValue;
            }
        }

        // 3. Record read version for validation, with semantic optimization
        // Skip validation for immutable items and blind reads
        boolean isBlindRead = key.matches("c_.*_bal") && !txn.writeSet.containsKey(key);
        if (!key.startsWith("item_") && !isBlindRead) {
            txn.readSet.put(key, baseVersion);
        }

        return synthesizedValue;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Optimization 1: Offload _next_oid hotspot
        if (key.endsWith("_next_oid")) {
            long oid = offloadedCounters.get(key).getAndIncrement();
            txn.writeSet.put(key, (int)oid + 1); // The transaction logic uses the value before increment
            txn.readSet.put(key, (int)oid);      // so we put the "read" value for its own use.
            return;
        }

        // Optimization 2: Use deltas for commutative operations
        if (key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty")) {
            Object oldValueObj = read(txn, key, store); // Read current synthesized value
            long oldValue = (oldValueObj instanceof Number) ? ((Number) oldValueObj).longValue() : 0;
            long newValue = ((Number) value).longValue();
            long deltaValue = newValue - oldValue;

            if (deltaValue != 0) {
                Delta newDelta = new Delta(txn.txid, deltaValue);
                versionedData.computeIfAbsent(key, k -> new VersionedDeltaValue(store.get(k), 0)).deltaChain.add(newDelta);
                
                ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>> myDeltas = (ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>>) txn.meta.get("deltaSet");
                myDeltas.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(newDelta);
            }
        } else {
            // Fallback to standard optimistic overwrite
            txn.writeSet.put(key, value);
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return CommitResult.failure("Transaction not active");

        // Determine partition and acquire lock
        int partitionId = findPartitionId(txn);
        Partition partition = partitions.computeIfAbsent(partitionId, k -> new Partition());
        partition.commitLock.lock();

        try {
            // *** FIX STARTS HERE ***
            // Ensure VersionedDeltaValue exists for all written keys before validation/commit,
            // to prevent NullPointerException on blind writes.
            Set<String> writeKeys = ConcurrentHashMap.newKeySet();
            writeKeys.addAll(txn.writeSet.keySet());
            ConcurrentHashMap<String, ?> myDeltasMap = (ConcurrentHashMap<String, ?>) txn.meta.get("deltaSet");
            writeKeys.addAll(myDeltasMap.keySet());

            for (String key : writeKeys) {
                versionedData.computeIfAbsent(key, k -> {
                    Object initialValue = store.get(k);
                    if (initialValue instanceof AtomicLong) {
                        initialValue = ((AtomicLong) initialValue).get();
                    }
                    return new VersionedDeltaValue(initialValue, 0);
                });
            }
            // *** FIX ENDS HERE ***

            // 1. Validation Phase
            for (Map.Entry<String, Object> entry : txn.readSet.entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("_next_oid")) continue; 

                long readVersion = (long) entry.getValue();
                VersionedDeltaValue vdv = versionedData.get(key);
                if (vdv != null && vdv.committedVersion > readVersion) {
                    rollback(txn, store);
                    return CommitResult.failure("Validation failed on key: " + key);
                }
            }

            // 2. Commit Phase
            long commitVersion = partition.versionCounter.incrementAndGet();

            // Apply non-commutative writes
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                String key = entry.getKey();
                if (key.endsWith("_next_oid")) continue;
                
                VersionedDeltaValue vdv = versionedData.get(key);
                vdv.committedValue = entry.getValue();
                vdv.committedVersion = commitVersion;
                vdv.deltaChain.clear(); 
            }

            // Apply commutative delta writes
            ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>> myDeltas = (ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>>) txn.meta.get("deltaSet");
            for (Map.Entry<String, CopyOnWriteArrayList<Delta>> entry : myDeltas.entrySet()) {
                String key = entry.getKey();
                VersionedDeltaValue vdv = versionedData.get(key);
                
                long totalDelta = 0;
                for(Delta d : entry.getValue()){
                    totalDelta += d.deltaValue;
                }

                long currentCommitted = (vdv.committedValue instanceof Number) ? ((Number)vdv.committedValue).longValue() : 0;
                vdv.committedValue = currentCommitted + totalDelta;
                vdv.committedVersion = commitVersion;
                
                vdv.deltaChain.removeAll(entry.getValue());
            }

            txn.status = TxStatus.COMMITTED;
            
            Map<String, Object> finalWrites = new ConcurrentHashMap<>();
            myDeltas.keySet().forEach(key -> finalWrites.put(key, versionedData.get(key).committedValue));
            txn.writeSet.keySet().forEach(key -> {
                 if (!key.endsWith("_next_oid")) {
                    finalWrites.put(key, versionedData.get(key).committedValue);
                 }
            });
            store.updateAll(finalWrites);

            return CommitResult.success((int) commitVersion);

        } finally {
            partition.commitLock.unlock();
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) return;
        txn.status = TxStatus.ABORTED;

        // Remove this transaction's deltas from the public chains
        ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>> myDeltas = (ConcurrentHashMap<String, CopyOnWriteArrayList<Delta>>) txn.meta.get("deltaSet");
        for (Map.Entry<String, CopyOnWriteArrayList<Delta>> entry : myDeltas.entrySet()) {
            VersionedDeltaValue vdv = versionedData.get(entry.getKey());
            if (vdv != null) {
                vdv.deltaChain.removeAll(entry.getValue());
            }
        }
    }

    private int getWarehouseId(String key) {
        Matcher matcher = WAREHOUSE_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0; // Global partition for non-warehouse keys like "item"
    }
    
    private int findPartitionId(Transaction txn) {
        Set<String> keys = ConcurrentHashMap.newKeySet();
        keys.addAll(txn.readSet.keySet());
        keys.addAll(txn.writeSet.keySet());
        keys.addAll(((ConcurrentHashMap<String, ?>)txn.meta.get("deltaSet")).keySet());

        for (String key : keys) {
            int id = getWarehouseId(key);
            if (id != 0) return id;
        }
        return 0; // Default to global partition if no warehouse key found
    }
}
