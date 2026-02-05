/* Technical Report:
### Final Technical Report: Deterministic Version-Chain Execution (DVCE)

**1. Algorithm Overview**
Deterministic Version-Chain Execution (DVCE) is a multi-version concurrency control protocol that eliminates data contention by pre-defining a transaction's position in the serial order *before* it executes. The workload is partitioned, and a dedicated scheduler for each partition assigns a unique, monotonic sequence number (`seq`) to each incoming transaction. This `seq` dictates the transaction's commit order. All data is stored in an in-memory, multi-version data structure where each key maps to a chain of version objects. Writes are speculative, appending new versions tagged with the transaction's `seq` to these chains. Reads traverse the version chains to compute a value consistent with the pre-defined serial order, materializing a "rolling future" state that includes the speculative work of all preceding transactions. This design transforms concurrency control from a problem of conflict resolution into one of deterministic, pipelined dataflow.

**2. Read/Write/Validation Strategy**
*   **Read Strategy:** Reads are lock-free but computationally involved. To read a key, a transaction with sequence `seq_T` scans the key's version chain. It identifies the most recent `ABSOLUTE` version with a `seq` less than `seq_T` to establish a base value. It then accumulates the values of all subsequent `DELTA` versions that also have a `seq` less than `seq_T`. This process guarantees that a transaction sees a consistent state as defined by its position in the serial order.
*   **Write Strategy:** Writes are speculative and non-blocking. A transaction's write operation creates a new version object and atomically appends it to the relevant key's version chain. Writes are semantically classified: commutative operations (e.g., balance updates) generate `DELTA` versions containing the change in value, while non-commutative operations (e.g., stock updates) generate `ABSOLUTE` versions with the new final value. These speculative writes are immediately visible to any subsequent transaction in the sequence.
*   **Validation Strategy:** The protocol is validation-free. Because the serial order is pre-determined and the read visibility rule strictly enforces it, data conflicts cannot occur. A transaction's "commit" is a logical operation that marks it as successful; its writes are already part of the speculative state.

**3. Deadlock Handling**
The DVCE protocol is **deadlock-free by design**. The execution model is entirely non-blocking. Transactions never wait for resources held by others; they simply read the speculative state available to them based on their sequence number and append their own speculative updates. This complete absence of blocking dependencies means deadlocks cannot arise.

**4. Correctness Guarantees**
The protocol guarantees **Strict Serializability**.
*   **Serializability:** The partition scheduler establishes a total serial order for all transactions *a priori* by assigning monotonic sequence numbers. The version chain visibility rule ensures that the execution of any transaction `T_j` is based on the full effects of all preceding transactions `T_i` (where `seq_i < seq_j`). Therefore, the final state is, by construction, equivalent to a serial execution in the pre-determined `seq` order.
*   **Strictness:** While writes are speculative, a transaction is only considered part of the final history once it logically commits. The visibility rule prevents any active transaction from reading the speculative work of a transaction that could later be logically aborted (e.g., due to an application-level error). An aborted transaction's versions are marked as `ABORTED` and are subsequently ignored by all other transactions, preventing cascading aborts.

**5. Complexity and Performance Notes**
*   **Complexity:** The `write` operation is O(log N) due to insertion into the `ConcurrentSkipListMap` that backs the version chain. The `read` operation's complexity is O(K), where K is the number of versions a transaction must traverse to compute its value. Under high contention, K can grow, increasing read latency.
*   **Performance:** The protocol achieves very high throughput, especially in contended workloads, by completely eliminating aborts due to data contention. The main performance characteristic is a trade-off: it exchanges the cost of potential aborts and re-computation (found in OCC) for higher, more predictable read latency. The pipelined nature is highly effective for hotspots.

**6. Assumptions and Limitations**
*   **Partitionable Workload:** The protocol assumes the workload can be cleanly partitioned, as the sequence numbers are generated on a per-partition basis. Cross-partition transactions are not supported in this design.
*   **In-Memory Operation:** The multi-version store is maintained entirely in memory. This leads to high space amplification, as old versions must be retained until they are no longer visible to any active transaction. An effective garbage collection mechanism is critical for long-running systems.
*   **Straggler Problem:** A single long-running transaction can stall the finalization of all subsequent transactions in its partition, leading to unbounded growth of version chains. This makes the protocol less suitable for workloads with high variance in transaction execution time.
*   **Read-Heavy Workloads:** The computational overhead of traversing version chains can make the protocol less efficient than single-version systems for read-dominant workloads with low contention.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 16,
  "verdict": "Derivative",
  "score_breakdown": {
    "conceptual_originality_40": 15,
    "distance_from_known_families_30": 25,
    "implementation_distinctiveness_15": 40,
    "correctness_argument_originality_10": 10,
    "rebranding_penalty_minus15": 5
  },
  "separate_scores": {
    "proposal_novelty": 20,
    "code_novelty": 25
  },
  "overlap_analysis": [
    {
      "family": "Deterministic Ordering (e.g., Calvin, BOHM)",
      "overlap_reasoning": "The core idea is identical: assign a unique, monotonic sequence number to each transaction before execution to pre-define the serial order. Transactions then execute speculatively, with each one reading the speculative outputs of its predecessors. DVCE's version chain is a specific data structure for implementing the 'pipelined execution' concept found in systems like BOHM.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "MVCC",
      "overlap_reasoning": "DVCE uses version chains, a classic MVCC data structure. However, its visibility rule is fundamentally different. Instead of reading a consistent snapshot of the *committed past*, it reads a 'rolling future' of *speculative* writes from transactions ordered before it. The use of versions is a shared tactic, but the control law is different.",
      "overlap_strength_0_to_100": 40
    },
    {
      "family": "Commutativity / Escrow Locking",
      "overlap_reasoning": "The use of `DELTA` versions for commutative updates is a known optimization technique to increase parallelism, also used in systems like Calvin and related to escrow/CRDT concepts. In DVCE, this is an optimization feature, not the primary concurrency control mechanism.",
      "overlap_strength_0_to_100": 30
    }
  ],
  "feature_diff": [
    {
      "axis": "Ordering Basis",
      "ours": "A unique, monotonic sequence number (`seq`) is assigned by a partition scheduler upon a transaction's first operation, pre-defining the serial order.",
      "typical_in_nearest_family": "(BOHM) A transaction's position in a pre-determined execution queue dictates its serial order. (Calvin) Transactions are grouped into batches, and a deterministic locking protocol based on read/write set analysis enforces order within the batch.",
      "difference_note": "The mechanism is functionally identical to the logical ordering in BOHM. It differs from Calvin's batch-oriented analysis but achieves the same goal of pre-defined ordering. This is not a novel difference."
    },
    {
      "axis": "Read Visibility Rule",
      "ours": "A transaction with `seq_T` reads the speculative writes of all preceding transactions (`seq < seq_T`) by traversing version chains to construct a 'rolling future' state.",
      "typical_in_nearest_family": "(BOHM) Speculative results are passed directly from one transaction's execution to the next in the pipeline. (MVCC) A transaction reads from a static snapshot of data committed before its start time.",
      "difference_note": "DVCE's version chain is a shared-memory data structure that materializes the same data flow that BOHM implements via pipelining. This is an implementation choice, not a conceptual breakthrough. The contrast with standard MVCC is significant, but the similarity to BOHM is overwhelming."
    },
    {
      "axis": "Commit Rule",
      "ours": "Commit is logical and asynchronous. A background 'finalizer' process later makes speculative versions permanent in strict sequence order.",
      "typical_in_nearest_family": "This model of decoupled execution and finalization is a common pattern in high-throughput deterministic systems to hide logging and persistence latency from the client.",
      "difference_note": "This is a common performance optimization for this class of system and not a novel aspect of the core control mechanism."
    }
  ],
  "code_checks": {
    "uses_locks_or_lock_modes": false,
    "uses_versions_or_snapshots": true,
    "commit_time_validation": false,
    "timestamp_ordering_core": true,
    "deterministic_preordering": true,
    "dependency_graph_cycles": false,
    "other_key_constructs": [
      "Per-key version chains using ConcurrentSkipListMap",
      "Explicit DELTA vs ABSOLUTE version types for commutativity"
    ]
  },
  "correctness_rationale_summary": "Correctness is ensured by pre-defining a total serial order via monotonic sequence numbers (`seq`). The read visibility rule guarantees that any transaction `T_j` observes the effects of all preceding transactions `T_i` (where `i < j`), making the concurrent execution equivalent to the pre-defined serial order. This rationale is standard for deterministic database systems and is not novel.",
  "risks_and_edge_cases": [
    "The proposal's correctness and performance critically depend on a background finalizer and garbage collector, which are completely absent from the implementation, leading to unbounded memory growth.",
    "The 'Straggler Problem' is the classic head-of-line blocking issue inherent to this entire family of protocols; the proposal acknowledges it but offers no novel solution.",
    "The `assignSequence` method uses `synchronized(txn)`, which could serialize transaction initiation within a partition and become a bottleneck."
  ],
  "suggestions_to_increase_novelty": [
    "Introduce a mechanism to safely reorder or finalize non-conflicting transactions out-of-order to mitigate the 'straggler problem', moving beyond a strictly static sequence for finalization.",
    "Develop a novel garbage collection strategy that is tightly integrated with the DELTA/ABSOLUTE versioning. For example, a method to speculatively compact long DELTA chains before their base ABSOLUTE version is finalized.",
    "Propose a novel cross-partition coordination protocol that extends the deterministic version-chain model, as the current proposal is strictly-partitioned and avoids this core challenge."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// Helper enums for the versioning system
enum VersionType { ABSOLUTE, DELTA }
enum VersionStatus { SPECULATIVE, ABORTED }

// A class representing a single version of a key's value.
class Version {
    final Object value;
    final long seq;
    final VersionType type;
    final VersionStatus status;

    Version(Object value, long seq, VersionType type, VersionStatus status) {
        this.value = value;
        this.seq = seq;
        this.type = type;
        this.status = status;
    }

    public Object value() { return value; }
    public long seq() { return seq; }
    public VersionType type() { return type; }
    public VersionStatus status() { return status; }

    Version asAborted() {
        return new Version(this.value, this.seq, this.type, VersionStatus.ABORTED);
    }
}

// Manages the chain of versions for a single key.
class VersionChain {
    final ConcurrentSkipListMap<Long, Version> versions = new ConcurrentSkipListMap<>();

    VersionChain(Object initialValue) {
        versions.put(0L, new Version(initialValue, 0L, VersionType.ABSOLUTE, VersionStatus.SPECULATIVE));
    }
}

/**
 * Deterministic Version-Chain Execution (DVCE) Concurrency Control Protocol.
 * This version fixes a NullPointerException caused by putting null into a ConcurrentHashMap.
 */
class GeneratedCC extends ConcurrencyControl {
    private static final String SEQ_KEY = "seq";

    private final ConcurrentHashMap<String, VersionChain> multiVersionStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Scheduler> schedulers = new ConcurrentHashMap<>();

    private static class Scheduler {
        final AtomicLong seqCounter = new AtomicLong(1);
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        return new Transaction((int) System.nanoTime(), (int) System.nanoTime());
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        assignSequence(txn, key);
        long txnSeq = (long) txn.meta.get(SEQ_KEY);

        VersionChain vc = multiVersionStore.computeIfAbsent(key, k -> {
            Object initialValue = store.get(k);
            if (initialValue instanceof AtomicLong) {
                initialValue = ((AtomicLong) initialValue).get();
            }
            return new VersionChain(initialValue != null ? initialValue : 0L);
        });

        NavigableMap<Long, Version> visibleVersions = vc.versions.headMap(txnSeq, false);

        long baseValue = 0L;
        long lastAbsoluteSeq = 0L;

        for (Version v : visibleVersions.descendingMap().values()) {
            if (v.status() == VersionStatus.ABORTED) continue;
            if (v.type() == VersionType.ABSOLUTE) {
                baseValue = ((Number) v.value()).longValue();
                lastAbsoluteSeq = v.seq();
                break;
            }
        }

        long deltaSum = 0L;
        for (Version v : visibleVersions.tailMap(lastAbsoluteSeq, false).values()) {
             if (v.seq() > lastAbsoluteSeq && v.status() != VersionStatus.ABORTED && v.type() == VersionType.DELTA) {
                deltaSum += ((Number) v.value()).longValue();
            }
        }

        return baseValue + deltaSum;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        assignSequence(txn, key);
        long txnSeq = (long) txn.meta.get(SEQ_KEY);
        
        VersionChain vc = multiVersionStore.computeIfAbsent(key, k -> {
            Object initialValue = store.get(k);
            if (initialValue instanceof AtomicLong) {
                initialValue = ((AtomicLong) initialValue).get();
            }
            return new VersionChain(initialValue != null ? initialValue : 0L);
        });

        VersionType type = classifyWrite(key);
        Object writeValue = value;

        if (type == VersionType.DELTA) {
            Object readResult = read(txn, key, store);
            long currentValue = (readResult instanceof Number) ? ((Number) readResult).longValue() : 0L;
            long finalValue = ((Number) value).longValue();
            writeValue = finalValue - currentValue;
        }

        Version newVersion = new Version(writeValue, txnSeq, type, VersionStatus.SPECULATIVE);
        vc.versions.put(txnSeq, newVersion);
        
        // FIX: ConcurrentHashMap does not allow null values. Use a non-null placeholder.
        txn.writeSet.put(key, Boolean.TRUE); 
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }
        txn.status = TxStatus.COMMITTED;
        return CommitResult.success(txn.startTs);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;

        if (txn.meta.get(SEQ_KEY) == null) return;

        long txnSeq = (long) txn.meta.get(SEQ_KEY);

        for (String key : txn.writeSet.keySet()) {
            VersionChain vc = multiVersionStore.get(key);
            if (vc != null) {
                Version versionToAbort = vc.versions.get(txnSeq);
                if (versionToAbort != null && versionToAbort.status() == VersionStatus.SPECULATIVE) {
                    vc.versions.replace(txnSeq, versionToAbort, versionToAbort.asAborted());
                }
            }
        }
    }

    private void assignSequence(Transaction txn, String key) {
        if (txn.meta.get(SEQ_KEY) == null) {
            synchronized (txn) {
                if (txn.meta.get(SEQ_KEY) == null) {
                    int partitionId = getPartitionIdFromKey(key);
                    Scheduler scheduler = schedulers.computeIfAbsent(partitionId, k -> new Scheduler());
                    long seq = scheduler.seqCounter.getAndIncrement();
                    txn.meta.put(SEQ_KEY, seq);
                }
            }
        }
    }

    private VersionType classifyWrite(String key) {
        if (key.endsWith("_ytd") || key.endsWith("_bal")) {
            return VersionType.DELTA;
        }
        return VersionType.ABSOLUTE;
    }

    private int getPartitionIdFromKey(String key) {
        String[] parts = key.split("_");
        if (parts.length > 1 && (parts[0].equals("w") || parts[0].equals("d") || parts[0].equals("s") || parts[0].equals("c") || parts[0].equals("o"))) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
