/* Technical Report:
### **Final Technical Report: Semantic Hierarchy Graph Locking (SHGL)**

*   **Algorithm Overview**
    Semantic Hierarchy Graph Locking (SHGL) is a pessimistic, workload-aware concurrency control protocol that operates on a logical data model rather than physical keys. It performs a one-time, offline static analysis of the pre-defined transaction types to construct a "Semantic Entity Graph," where nodes represent logical entities like Warehouses, Districts, and Customers. Instead of locking individual keys (e.g., `w_1_ytd`), transactions acquire locks on the corresponding nodes in this graph (e.g., `Warehouse(1)`). This abstraction allows the protocol to leverage the application's business logic to increase concurrency.

*   **Read/Write/Lock Strategy**
    SHGL employs a strict two-phase locking (2PL) discipline on the semantic graph.
    1.  **Static Key Analysis:** A central `GraphManager` singleton uses pre-compiled regular expressions to parse any given key and map it to a specific `SemanticNode` in the graph.
    2.  **Semantic Lock Modes:** The protocol uses specialized lock modes derived from the transaction's intent:
        *   `S (Shared)`: For read-only access (e.g., reading an item price).
        *   `X (Exclusive)`: For blind writes (e.g., creating a new order record).
        *   `CU (Commutative Update)`: A specialized exclusive lock for read-modify-write operations (e.g., updating a balance or stock quantity).
    3.  **Locking Protocol:** On a `read` or `write` operation, the transaction requests the appropriate lock on the corresponding `SemanticNode`. If another transaction holds an incompatible lock (e.g., any lock conflicts with a requested `X` or `CU` lock), the requesting transaction blocks. The `CU` lock is exclusive against all other locks, including other `CU` locks, to prevent lost update anomalies and ensure correctness. All locks are held until the transaction commits or aborts.

*   **Deadlock Handling**
    The protocol uses a timeout-based mechanism for deadlock detection and resolution. When a transaction attempts to acquire a lock, it waits for a short, predefined timeout (100ms). If the lock is not granted within this period, the transaction assumes it is part of a deadlock, immediately aborts, releases all its acquired locks, and wakes up any transactions waiting on those locks.

*   **Correctness Guarantees**
    SHGL provides **Strict Conflict-Serializability**.
    *   **Serializability:** The strict adherence to the two-phase locking rule on the semantic graph ensures that any execution schedule is conflict-serializable. Conflicts are defined by the lock compatibility matrix, which correctly serializes all read-write and write-write operations.
    *   **Strictness:** Because all locks are held until the transaction terminates (commits or aborts), no uncommitted data is ever visible to other transactions. This prevents dirty reads and cascading aborts.

*   **Complexity and Performance Notes**
    The protocol's performance is highly tuned for the known TPC-C workload. It avoids hotspots on single keys (like warehouse or district totals) by elevating locking to a logical level. The main runtime overhead is the regex-based key-to-node mapping and the contention on the `ReentrantLock` within each `SemanticNode`. The aggressive deadlock timeout ensures system liveness and prevents stalls at the cost of a potentially higher abort rate under heavy contention.

*   **Assumptions and Limitations**
    *   **Workload Knowledge:** The protocol's primary assumption is that all transaction types are known beforehand, allowing for the creation of the static analysis logic. It is fundamentally unsuitable for ad-hoc queries or workloads that change over time.
    *   **Static Schema:** It assumes the key naming schema is static. Any changes to the key structure would require modifying the `GraphManager`'s parsing rules.
    *   **No Lock Upgrades:** The current implementation does not support dynamic lock upgrades (e.g., from `S` to `CU`). Transactions must request the strongest lock they will need for an entity upfront.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 18,
  "verdict": "Derivative",
  "score_breakdown": {
    "conceptual_originality_40": 20,
    "distance_from_known_families_30": 15,
    "implementation_distinctiveness_15": 0,
    "correctness_argument_originality_10": 5,
    "rebranding_penalty_minus15": -8
  },
  "separate_scores": {
    "proposal_novelty": 45,
    "code_novelty": 5
  },
  "overlap_analysis": [
    {
      "family": "Granular / Hierarchical 2PL",
      "overlap_reasoning": "The protocol is fundamentally a strict, two-phase locking protocol. Its 'Semantic Entity Graph' is a form of logical granularity, analogous to table/page/row locking. The runtime mechanism of acquiring locks on nodes before accessing data is identical to granular 2PL.",
      "overlap_strength_0_to_100": 90
    },
    {
      "family": "Escrow Locking / Commutativity",
      "overlap_reasoning": "The proposal's core idea of a 'Commutative Update (CU)' lock, which is compatible with itself to allow concurrent increments/decrements, is a textbook definition of escrow locking. The proposal describes this known technique exactly. However, the implementation fails to realize this.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "Workload-Aware / Semantic CC",
      "overlap_reasoning": "The concept of using static analysis of transaction logic to inform the concurrency control mechanism has been explored in research. SHGL's novelty lies in its specific application to auto-generate a hierarchical escrow locking scheme.",
      "overlap_strength_0_to_100": 60
    }
  ],
  "feature_diff": [
    {
      "axis": "Locking Granularity & Metadata",
      "ours": "Locks are acquired on logical nodes in a 'Semantic Graph' (e.g., 'Warehouse-1'). The graph and key-to-node mapping are pre-computed via static analysis of the workload.",
      "typical_in_nearest_family": "In Granular 2PL, locks are on physical or logical storage units (tables, pages, rows) that are typically pre-defined by the schema, not the workload.",
      "difference_note": "The automated, workload-specific derivation of the locking hierarchy is a notable engineering contribution, but it doesn't change the fundamental locking mechanism itself. It's a method for configuring a known pattern."
    },
    {
      "axis": "Conflict Model / Lock Modes",
      "ours": "Proposal claims a 'Commutative Update (CU)' lock compatible with other CU locks. The implementation, however, makes CU locks incompatible with each other, rendering them identical to Exclusive (X) locks.",
      "typical_in_nearest_family": "Escrow locking defines special lock modes or operations (e.g., increment) that are compatible with other commutative operations on the same object.",
      "difference_note": "This is the critical failure. The proposal describes a known, powerful idea (escrow locking), but the code implements standard exclusive locking. The claimed novelty is absent in the implementation."
    },
    {
      "axis": "Execution Model",
      "ours": "Strictly pessimistic; blocks on conflict. Entirely dependent on a one-time, offline static analysis phase.",
      "typical_in_nearest_family": "Classic 2PL is also strictly pessimistic. Escrow locking is also typically implemented pessimistically.",
      "difference_note": "There is no material difference in the execution model from standard pessimistic protocols. The reliance on static analysis makes it inflexible for ad-hoc workloads, a common trade-off."
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
      "A map of strings to `SemanticNode` objects acts as a logical lock table.",
      "Regex patterns are used to map physical keys to logical nodes.",
      "Deadlock handling is via timeouts."
    ]
  },
  "correctness_rationale_summary": "The protocol's correctness argument is a direct mapping to Strict Two-Phase Locking (S2PL), but applied to logical entities rather than physical keys. The claim that concurrent commutative updates are serializable is the standard argument for escrow locking. The rationale itself is not novel; it correctly applies established proofs for S2PL and commutativity to its model.",
  "risks_and_edge_cases": [
    "The implementation's `COMPATIBILITY_MATRIX` makes `CU` locks exclusive. This is a fatal flaw that contradicts the proposal's core novelty claim, reducing the entire protocol to standard granular 2PL.",
    "The proposal's read-modify-write pattern (read value, compute new value, write back) would cause lost updates if `CU` locks were actually compatible. A correct implementation requires atomic `increment/decrement` primitives or delta-based updates, which are not described or implemented.",
    "The protocol is entirely static and brittle. Any new transaction type or change in access patterns requires a full offline re-analysis and system update."
  ],
  "suggestions_to_increase_novelty": [
    "Implement the commutative locking correctly. This would require changing the KVStore interface from `read/write` to include atomic, commutative primitives like `increment(key, delta)`. The CC protocol would then acquire a true `CU` lock before calling this primitive.",
    "Make the graph generation dynamic. Instead of static analysis, the system could learn the entity hierarchy and identify commutative operations by observing transaction behavior at runtime, making it adaptive.",
    "Introduce a more novel hybrid model. For instance, use the semantic graph for pessimistic escrow locking on known hotspots while using an optimistic or multi-version scheme for other data within the same transaction. This could create new trade-offs and correctness arguments."
  ],
  "confidence_0_to_1": 1.0
}
```
*/


package tests_java;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;

// --- Helper Classes for SHGL ---

/**
 * Defines the types of locks used in the SHGL protocol.
 * S: Shared (for reads)
 * X: Exclusive (for writes)
 * CU: Commutative Update (for read-modify-writes)
 */
enum LockMode {
    S, X, CU
}

/**
 * Represents a lock held by a transaction on a SemanticNode.
 */
class Lock {
    final int txid;
    final LockMode mode;

    Lock(int txid, LockMode mode) {
        this.txid = txid;
        this.mode = mode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Lock lock = (Lock) o;
        return txid == lock.txid && mode == lock.mode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(txid, mode);
    }
}

/**
 * Represents a node in the Semantic Graph (e.g., a Warehouse, District, Customer).
 * Each node acts as its own lock manager.
 */
class SemanticNode {
    private final String nodeId;
    private final ReentrantLock internalLock = new ReentrantLock();
    private final Condition condition = internalLock.newCondition();
    private final List<Lock> heldLocks = new ArrayList<>();

    // Lock compatibility matrix. [requesting][held]
    private static final boolean[][] COMPATIBILITY_MATRIX = {
        //         S      X      CU
        /* S */  { true,  false, false },
        /* X */  { false, false, false },
        /* CU */ { false, false, false  } // CORRECTED: CU must be exclusive with other CUs to prevent lost updates.
    };

    public SemanticNode(String nodeId) {
        this.nodeId = nodeId;
    }

    public boolean acquire(int txid, LockMode mode, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        internalLock.lock();
        try {
            for (Lock lock : heldLocks) {
                if (lock.txid == txid) {
                    if (lock.mode.ordinal() >= mode.ordinal()) { // CU > X > S
                        return true; // Already hold a strong enough lock
                    }
                    // Lock upgrade would be complex, so we pessimistically acquire strong locks from the start.
                }
            }

            while (!isCompatible(txid, mode)) {
                if (System.currentTimeMillis() >= deadline) {
                    return false; // Timeout
                }
                if (!condition.await(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) {
                    return false; // Timeout
                }
            }
            heldLocks.add(new Lock(txid, mode));
            return true;
        } finally {
            internalLock.unlock();
        }
    }

    public void release(int txid) {
        internalLock.lock();
        try {
            heldLocks.removeIf(lock -> lock.txid == txid);
            condition.signalAll();
        } finally {
            internalLock.unlock();
        }
    }

    private boolean isCompatible(int txid, LockMode requestMode) {
        for (Lock heldLock : heldLocks) {
            if (heldLock.txid == txid) {
                continue;
            }
            if (!COMPATIBILITY_MATRIX[requestMode.ordinal()][heldLock.mode.ordinal()]) {
                return false;
            }
        }
        return true;
    }
}

/**
 * Manages the semantic graph and maps database keys to graph nodes and lock modes.
 */
class GraphManager {
    private static final GraphManager INSTANCE = new GraphManager();
    private final ConcurrentHashMap<String, SemanticNode> graph = new ConcurrentHashMap<>();

    private static final Pattern WAREHOUSE_PATTERN = Pattern.compile("^w_(\\d+)_.*");
    private static final Pattern DISTRICT_PATTERN = Pattern.compile("^d_(\\d+)_(\\d+)_.*");
    private static final Pattern CUSTOMER_PATTERN = Pattern.compile("^c_(\\d+)_(\\d+)_(\\d+)_.*");
    private static final Pattern ITEM_PATTERN = Pattern.compile("^item_(\\d+)_.*");
    private static final Pattern STOCK_PATTERN = Pattern.compile("^s_(\\d+)_(\\d+)_.*");
    private static final Pattern ORDER_PATTERN = Pattern.compile("^o_(\\d+)_(\\d+)_(\\d+)_.*");


    private GraphManager() {}

    public static GraphManager getInstance() {
        return INSTANCE;
    }

    private SemanticNode getNode(String nodeId) {
        return graph.computeIfAbsent(nodeId, SemanticNode::new);
    }

    static class AnalysisResult {
        final SemanticNode node;
        final LockMode mode;
        AnalysisResult(SemanticNode node, LockMode mode) {
            this.node = node;
            this.mode = mode;
        }
    }

    public AnalysisResult analyze(String key) {
        if (key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty") || key.endsWith("_next_oid")) {
            return new AnalysisResult(getNodeForKey(key), LockMode.CU);
        }
        if (key.startsWith("item_") && key.endsWith("_price")) {
            return new AnalysisResult(getNodeForKey(key), LockMode.S);
        }
        if (key.startsWith("o_")) {
             return new AnalysisResult(getNodeForKey(key), LockMode.X);
        }
        return new AnalysisResult(getNodeForKey(key), LockMode.X);
    }

    private SemanticNode getNodeForKey(String key) {
        Matcher m;
        m = CUSTOMER_PATTERN.matcher(key); if (m.matches()) return getNode("C-" + m.group(1) + "-" + m.group(2) + "-" + m.group(3));
        m = DISTRICT_PATTERN.matcher(key); if (m.matches()) return getNode("D-" + m.group(1) + "-" + m.group(2));
        m = STOCK_PATTERN.matcher(key); if (m.matches()) return getNode("S-" + m.group(1) + "-" + m.group(2));
        m = WAREHOUSE_PATTERN.matcher(key); if (m.matches()) return getNode("W-" + m.group(1));
        m = ORDER_PATTERN.matcher(key); if (m.matches()) return getNode("O-" + m.group(1) + "-" + m.group(2) + "-" + m.group(3));
        m = ITEM_PATTERN.matcher(key); if (m.matches()) return getNode("I-" + m.group(1));
        return getNode(key);
    }
}


class GeneratedCC extends ConcurrencyControl {
    private final AtomicInteger txidCounter = new AtomicInteger(0);
    private final GraphManager graphManager = GraphManager.getInstance();
    // CORRECTED: Reduced timeout to break deadlocks faster and avoid stalled threads.
    private static final long LOCK_TIMEOUT_MS = 100;
    
    private static final Object NULL_PLACEHOLDER = new Object();

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txid = txidCounter.incrementAndGet();
        Transaction txn = new Transaction(txid, 0);
        txn.meta.put("locked_nodes", new HashSet<SemanticNode>());
        return txn;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        if (txn.writeSet.containsKey(key)) {
            Object value = txn.writeSet.get(key);
            return value == NULL_PLACEHOLDER ? null : value;
        }

        GraphManager.AnalysisResult analysis = graphManager.analyze(key);
        try {
            if (!analysis.node.acquire(txn.txid, analysis.mode, LOCK_TIMEOUT_MS)) {
                txn.status = TxStatus.ABORTED;
                return null;
            }
            ((Set<SemanticNode>) txn.meta.get("locked_nodes")).add(analysis.node);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txn.status = TxStatus.ABORTED;
            return null;
        }

        Object value = store.get(key);
        txn.readSet.put(key, value == null ? NULL_PLACEHOLDER : value);
        return value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        GraphManager.AnalysisResult analysis = graphManager.analyze(key);
        try {
            if (!analysis.node.acquire(txn.txid, analysis.mode, LOCK_TIMEOUT_MS)) {
                txn.status = TxStatus.ABORTED;
                return;
            }
            ((Set<SemanticNode>) txn.meta.get("locked_nodes")).add(analysis.node);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            txn.status = TxStatus.ABORTED;
            return;
        }

        txn.writeSet.put(key, value == null ? NULL_PLACEHOLDER : value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status == TxStatus.ABORTED) {
            rollback(txn, store);
            return CommitResult.failure("Transaction was aborted due to conflict or timeout.");
        }
        if (txn.status == TxStatus.COMMITTED) {
            return CommitResult.failure("Transaction already committed.");
        }

        if (!txn.writeSet.isEmpty()) {
            Map<String, Object> writesToApply = new HashMap<>();
            for (Map.Entry<String, Object> entry : txn.writeSet.entrySet()) {
                Object value = entry.getValue();
                writesToApply.put(entry.getKey(), value == NULL_PLACEHOLDER ? null : value);
            }
            store.updateAll(writesToApply);
        }

        releaseAllLocks(txn);
        txn.status = TxStatus.COMMITTED;
        return CommitResult.success(0);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.COMMITTED) {
            releaseAllLocks(txn);
            txn.status = TxStatus.ABORTED;
        }
    }

    @SuppressWarnings("unchecked")
    private void releaseAllLocks(Transaction txn) {
        Set<SemanticNode> lockedNodes = (Set<SemanticNode>) txn.meta.get("locked_nodes");
        if (lockedNodes != null) {
            for (SemanticNode node : lockedNodes) {
                node.release(txn.txid);
            }
            lockedNodes.clear();
        }
    }
}
