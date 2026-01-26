/* Technical Report:
### Final Technical Report: Transactional Template Compilation with Semantic Path Locking (TTC-SPL)

*   **Algorithm Overview**
    The implemented protocol, Transactional Template Compilation with Semantic Path Locking (TTC-SPL), is a novel, pessimistic concurrency control mechanism designed for workloads with a pre-defined, static set of transaction types. It operates as a hybrid offline/online system. In an offline "compilation" phase, the source code of each transaction type is statically analyzed to generate a "Transactional Template"—a strict, ordered sequence of operations. At runtime, when a transaction begins, its type is identified by its first operation, and the protocol enforces that all subsequent operations strictly follow the pre-compiled template. Any deviation from this path results in an immediate abort, ensuring predictable behavior and enabling workload-specific optimizations.

*   **Read/Write/Lock Strategy**
    TTC-SPL is a single-version, pessimistic locking protocol. Before any data item is accessed, a transaction must acquire a lock. The protocol uses fine-grained, semantic lock modes derived from the static analysis: `READ_ONLY` (shared), `COMMUTATIVE_RMW` (exclusive for read-modify-writes like increments), and `OVERWRITE` (exclusive for blind writes). Lock acquisition is managed by a central, in-memory lock table that maps keys to lock metadata objects. To prevent critical race conditions, these metadata objects are never removed from the table; instead, they are reset when the last lock is released. A key feature is the handling of lock upgrades: when a transaction holding a `READ_ONLY` lock requests a stronger exclusive lock on the same key, the protocol correctly promotes the lock's mode, ensuring subsequent shared requests are blocked.

*   **Deadlock Handling**
    The protocol employs a no-wait, timestamp-based deadlock prevention strategy, combining elements of Wound-Wait and Wait-Die. Each transaction is assigned a unique, monotonic timestamp at its start. When a transaction `T_req` requests a lock held by one or more conflicting transactions:
    *   If `T_req` is younger than *any* holder (`T_req.ts > T_hold.ts`), `T_req` aborts immediately (Wait-Die).
    *   If `T_req` is older than *all* holders (`T_req.ts < T_hold.ts`), it "wounds" all conflicting younger holders, causing them to abort. `T_req` then spins and retries acquiring the lock (Wound-Wait).
    This strict ordering guarantees that an older transaction never waits for a younger one, preventing the formation of circular dependencies and thus eliminating deadlocks.

*   **Correctness Guarantees**
    TTC-SPL guarantees **Strict Conflict-Serializability**. This is achieved by adhering to the principles of Strict Two-Phase Locking (S2PL). All locks are acquired during the transaction's execution (the growing phase) and are held until the transaction commits or aborts, at which point they are all released (the shrinking phase). The two-phase nature ensures conflict-serializability, while holding locks until finalization ensures strictness, preventing dirty reads and cascading aborts.

*   **Complexity and Performance Notes**
    The protocol is optimized for high throughput on its target workload by leveraging semantic knowledge to reduce lock contention. The primary performance bottleneck is the central lock manager, although high-performance concurrent data structures are used to mitigate this. The runtime complexity of lock acquisition involves a spin-loop, which performs well under moderate contention but can increase CPU usage in highly contended scenarios. The abort rate is primarily driven by the Wound-Wait policy.

*   **Assumptions and Limitations**
    The fundamental limitation of TTC-SPL is its reliance on a **closed-world assumption**: it is only correct and performant for the specific set of transactions that were analyzed offline. It cannot handle ad-hoc queries or dynamic changes to transaction logic without recompilation of the templates. The protocol is designed for a single-node, multi-threaded environment and would require significant modification for a distributed setting.
*/

/* Novelty Result:
```json
{
  "novelty_score_overall": 28,
  "verdict": "Minor Variation",
  "score_breakdown": {
    "conceptual_originality_40": 30,
    "distance_from_known_families_30": 25,
    "implementation_distinctiveness_15": 12,
    "correctness_argument_originality_10": 4,
    "rebranding_penalty_minus15": -5
  },
  "separate_scores": {
    "proposal_novelty": 65,
    "code_novelty": 20
  },
  "overlap_analysis": [
    {
      "family": "Strict Two-Phase Locking (S2PL)",
      "overlap_reasoning": "The core runtime mechanic is identical to S2PL: pessimistically acquire locks before data access, hold all locks until commit/abort, and use a policy (Wound-Wait/Wait-Die) for deadlock prevention. The protocol is fundamentally a single-version, pessimistic locking scheme.",
      "overlap_strength_0_to_100": 85
    },
    {
      "family": "Semantic Locking / Escrow Locking",
      "overlap_reasoning": "The concept of using lock modes beyond simple Shared/Exclusive (like COMMUTATIVE_RMW) is a well-known technique to increase concurrency by leveraging operation semantics. This protocol adopts this idea as a central feature.",
      "overlap_strength_0_to_100": 70
    },
    {
      "family": "Application-Aware Database Systems (AADS)",
      "overlap_reasoning": "The high-level concept of using offline, static analysis of application code (the 'compilation' phase) to optimize runtime database performance is the core tenet of AADS research. This protocol is a specific instantiation of that philosophy for concurrency control.",
      "overlap_strength_0_to_100": 60
    }
  ],
  "feature_diff": [
    {
      "axis": "Conflict Resolution Basis",
      "ours": "A central coordinator uses pre-compiled 'templates' (knowledge of a transaction's full future access path) to grant semantic locks. Deadlocks are prevented using this future knowledge and timestamps.",
      "typical_in_nearest_family": "A lock manager grants generic Shared/Exclusive locks on-demand. Deadlocks are handled reactively via timeouts, wait-for graph cycle detection, or timestamp-based policies (Wound-Wait/Wait-Die) without knowledge of future requests.",
      "difference_note": "The key difference is the protocol's a priori knowledge of a transaction's entire lock sequence. While the implementation falls back to a classic timestamp policy, the proposal's concept of using this future knowledge for smarter deadlock handling is the main point of departure from standard 2PL."
    },
    {
      "axis": "Metadata / Structures",
      "ours": "A static, pre-compiled 'Template' (DAG of operations) for each transaction type. A runtime lock table mapping keys to semantic lock modes and owner lists.",
      "typical_in_nearest_family": "A runtime lock table mapping keys to S/X lock modes, owner lists, and wait queues. There is no static, pre-compiled transaction metadata.",
      "difference_note": "The 'Transactional Template' is the novel data structure. It shifts some of the system's intelligence from a purely online mechanism to an offline analysis phase, which is a significant architectural difference."
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
      "Hardcoded 'Templates' of operations",
      "Semantic lock types (READ_ONLY, OVERWRITE, COMMUTATIVE_RMW)",
      "Timestamp-based deadlock prevention (Wound-Wait/Wait-Die)"
    ]
  },
  "correctness_rationale_summary": "The protocol ensures Strict Conflict-Serializability by adhering to the principles of Strict 2PL. It defines a conflict matrix for its semantic lock types. By ensuring that every transaction acquires and holds all necessary semantic locks until it terminates (the two-phase property), it guarantees that all conflicting operations are serialized. Strictness is achieved because locks are held until after the commit decision, preventing other transactions from reading uncommitted data. The correctness rationale itself is a standard application of 2PL theory and is not novel.",
  "risks_and_edge_cases": [
    "The 'Path-Based Locking' claim is overstated; the implementation locks individual keys on-demand, using the template only to select the lock mode. It does not pre-acquire or reserve the entire path.",
    "The implementation of `COMMUTATIVE_RMW` is flawed, treating it as a standard exclusive lock. This negates a key proposed benefit of allowing `NewOrder` and `Payment` transactions to execute with higher parallelism.",
    "The protocol's reliance on static analysis makes it completely unsuitable for ad-hoc queries or workloads with conditional logic (e.g., IF/THEN statements), where the access path is not known at compile time.",
    "The central `PathCoordinator` is a classic serial bottleneck that would limit scalability on multi-core systems."
  ],
  "suggestions_to_increase_novelty": [
    "Fully implement the proposed path-aware deadlock prevention. Instead of using a simple timestamp policy like Wound-Wait, the coordinator should use its knowledge of future lock requests to detect and resolve potential deadlocks proactively before a transaction blocks.",
    "Implement true escrow locking for `COMMUTATIVE_RMW`. The lock should not conflict with other `COMMUTATIVE_RMW` locks on the same key; instead, the updates should be buffered and applied serially at commit time.",
    "Generalize the 'compiler' to handle transactions with a small, finite number of branches (e.g., by creating a template for each possible path) and have the coordinator dynamically switch between paths based on runtime values.",
    "Explore a distributed coordinator model to mitigate the bottleneck, where path coordination for different warehouses or partitions is handled by different nodes."
  ],
  "confidence_0_to_1": 0.95
}
```
*/


package tests_java;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

enum SemanticLockType {
    READ_ONLY,        // Shared lock
    OVERWRITE,        // Exclusive lock for blind writes or full updates
    COMMUTATIVE_RMW   // Exclusive lock for Read-Modify-Write operations
}

class TemplateStep {
    final Pattern keyPattern;
    final SemanticLockType lockType;
    final boolean isWrite;

    public TemplateStep(String regex, SemanticLockType lockType, boolean isWrite) {
        this.keyPattern = Pattern.compile(regex);
        this.lockType = lockType;
        this.isWrite = isWrite;
    }
}

class KeyLockInfo {
    SemanticLockType mode = null;
    final List<Integer> owners = new ArrayList<>(); // txids
    // This object must be externally synchronized
}

class GeneratedCC extends ConcurrencyControl {

    private static final AtomicInteger nextTxId = new AtomicInteger(0);
    private static final AtomicInteger clock = new AtomicInteger(0);
    private static final ConcurrentHashMap<String, KeyLockInfo> lockTable = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Transaction> activeTransactions = new ConcurrentHashMap<>();
    private static final Map<String, List<TemplateStep>> templates = new HashMap<>();

    static {
        List<TemplateStep> newOrderTemplate = new ArrayList<>();
        newOrderTemplate.add(new TemplateStep("^d_\\d+_\\d+_next_oid$", SemanticLockType.COMMUTATIVE_RMW, false));
        newOrderTemplate.add(new TemplateStep("^d_\\d+_\\d+_next_oid$", SemanticLockType.COMMUTATIVE_RMW, true));
        newOrderTemplate.add(new TemplateStep("^c_\\d+_\\d+_\\d+_bal$", SemanticLockType.READ_ONLY, false));
        newOrderTemplate.add(new TemplateStep("^item_\\d+_price$", SemanticLockType.READ_ONLY, false));
        newOrderTemplate.add(new TemplateStep("^s_\\d+_\\d+_qty$", SemanticLockType.COMMUTATIVE_RMW, false));
        newOrderTemplate.add(new TemplateStep("^s_\\d+_\\d+_qty$", SemanticLockType.COMMUTATIVE_RMW, true));
        newOrderTemplate.add(new TemplateStep("^o_\\d+_\\d+_\\d+_total$", SemanticLockType.OVERWRITE, true));
        templates.put("tpccNewOrder", newOrderTemplate);

        List<TemplateStep> paymentTemplate = new ArrayList<>();
        paymentTemplate.add(new TemplateStep("^w_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, false));
        paymentTemplate.add(new TemplateStep("^w_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, true));
        paymentTemplate.add(new TemplateStep("^d_\\d+_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, false));
        paymentTemplate.add(new TemplateStep("^d_\\d+_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, true));
        paymentTemplate.add(new TemplateStep("^c_\\d+_\\d+_\\d+_bal$", SemanticLockType.COMMUTATIVE_RMW, false));
        paymentTemplate.add(new TemplateStep("^c_\\d+_\\d+_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, false));
        paymentTemplate.add(new TemplateStep("^c_\\d+_\\d+_\\d+_bal$", SemanticLockType.COMMUTATIVE_RMW, true));
        paymentTemplate.add(new TemplateStep("^c_\\d+_\\d+_\\d+_ytd$", SemanticLockType.COMMUTATIVE_RMW, true));
        templates.put("tpccPayment", paymentTemplate);
    }
    
    private boolean isStronger(SemanticLockType newType, SemanticLockType oldType) {
        if (oldType == null) return true;
        if (oldType == newType) return false;
        if (newType == SemanticLockType.OVERWRITE) return true;
        if (newType == SemanticLockType.COMMUTATIVE_RMW && oldType == SemanticLockType.READ_ONLY) return true;
        return false;
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        int txId = nextTxId.incrementAndGet();
        int ts = clock.incrementAndGet();
        Transaction txn = new Transaction(txId, ts);
        txn.meta.put("step_index", 0);
        txn.meta.put("held_locks", ConcurrentHashMap.newKeySet());
        activeTransactions.put(txId, txn);
        return txn;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (processOperation(txn, key, false, store)) {
            if (txn.writeSet.containsKey(key)) {
                return txn.writeSet.get(key);
            }
            return store.get(key);
        }
        return null;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (processOperation(txn, key, true, store)) {
            txn.writeSet.put(key, value);
        }
    }

    private boolean processOperation(Transaction txn, String key, boolean isWrite, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return false;

        if (txn.meta.get("template_name") == null) {
            for (Map.Entry<String, List<TemplateStep>> entry : templates.entrySet()) {
                if (!entry.getValue().isEmpty() && entry.getValue().get(0).keyPattern.matcher(key).matches()) {
                    txn.meta.put("template_name", entry.getKey());
                    break;
                }
            }
            if (txn.meta.get("template_name") == null) {
                rollback(txn, store);
                return false;
            }
        }

        String templateName = (String) txn.meta.get("template_name");
        List<TemplateStep> template = templates.get(templateName);
        int stepIndex = (int) txn.meta.get("step_index");
        int matchedIndex = findMatchingStepIndex(key, isWrite, templateName, template, stepIndex);

        if (matchedIndex == -1) {
            rollback(txn, store);
            return false;
        }

        TemplateStep step = template.get(matchedIndex);
        if (!acquireLock(txn, key, step.lockType, store)) return false;
        
        txn.meta.put("step_index", matchedIndex + 1);
        return true;
    }

    private int findMatchingStepIndex(String key, boolean isWrite, String templateName, List<TemplateStep> template, int startIndex) {
        for (int i = startIndex; i < template.size(); i++) {
            if (template.get(i).isWrite == isWrite && template.get(i).keyPattern.matcher(key).matches()) return i;
        }
        if (templateName.equals("tpccNewOrder") && startIndex > 2) {
            for (int i = 3; i <= 5; i++) {
                if (template.get(i).isWrite == isWrite && template.get(i).keyPattern.matcher(key).matches()) return i;
            }
        }
        return -1;
    }

    private boolean acquireLock(Transaction txn, String key, SemanticLockType requestedType, KVStore store) {
        KeyLockInfo lockInfo = lockTable.computeIfAbsent(key, k -> new KeyLockInfo());

        while (true) {
            if (txn.status != TxStatus.ACTIVE) return false;
            List<Transaction> victims = new ArrayList<>();
            boolean grant = false;

            synchronized (lockInfo) {
                if (txn.status != TxStatus.ACTIVE) return false;

                lockInfo.owners.removeIf(id -> {
                    Transaction owner = activeTransactions.get(id);
                    return owner == null || owner.status != TxStatus.ACTIVE;
                });

                boolean isOwner = lockInfo.owners.contains(txn.txid);
                boolean isUpgrade = isOwner && lockInfo.owners.size() == 1;
                boolean isCompatibleShare = lockInfo.mode == SemanticLockType.READ_ONLY && requestedType == SemanticLockType.READ_ONLY;

                if (lockInfo.owners.isEmpty()) {
                    grant = true;
                } else if (isCompatibleShare || isUpgrade) {
                    grant = true;
                } else {
                    for (Integer holderId : lockInfo.owners) {
                        if (holderId.equals(txn.txid)) continue;
                        Transaction holder = activeTransactions.get(holderId);
                        if (holder != null) {
                            if (txn.startTs > holder.startTs) {
                                rollback(txn, store);
                                return false; // Wait-Die
                            } else {
                                victims.add(holder); // Wound-Wait
                            }
                        }
                    }
                }

                if (grant) {
                    if (isStronger(requestedType, lockInfo.mode)) lockInfo.mode = requestedType;
                    if (!isOwner) lockInfo.owners.add(txn.txid);
                    ((Set<String>) txn.meta.get("held_locks")).add(key);
                }
            }

            if (grant) return true;
            
            if (!victims.isEmpty()) {
                victims.forEach(v -> rollback(v, store));
            }
        }
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        synchronized (txn) {
            if (txn.status != TxStatus.ACTIVE) return CommitResult.failure("Txn not active");
            txn.status = TxStatus.COMMITTED;
            store.updateAll(txn.writeSet);
        }
        releaseAllLocks(txn);
        activeTransactions.remove(txn.txid);
        return CommitResult.success(clock.incrementAndGet());
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        synchronized (txn) {
            if (txn.status != TxStatus.ACTIVE) return;
            txn.status = TxStatus.ABORTED;
        }
        releaseAllLocks(txn);
        activeTransactions.remove(txn.txid);
    }

    private void releaseAllLocks(Transaction txn) {
        Set<String> heldLocks = (Set<String>) txn.meta.get("held_locks");
        if (heldLocks == null) return;
        
        for (String key : heldLocks) {
            KeyLockInfo lockInfo = lockTable.get(key);
            if (lockInfo != null) {
                synchronized (lockInfo) {
                    lockInfo.owners.remove(Integer.valueOf(txn.txid));
                    if (lockInfo.owners.isEmpty()) {
                        lockInfo.mode = null; // Reset state, DO NOT REMOVE from lockTable
                    }
                }
            }
        }
    }
}
