
import os
def get_propose_prompt(inspiration_text: str | None = None) -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    inspiration_block = ""
    inspiration_guidance = ""
    if inspiration_text:
        inspiration_block = f"""
            Inspiration material (do not copy verbatim; use for ideas only):
            <inspiration>
            {inspiration_text}
            </inspiration>
        """
        inspiration_guidance = "- IMPORTANT: YOU MUST Use the inspiration text inside <inspiration> tag to guide your proposal"

    prompt = f"""
    You are an expert in database concurrency control research and design.
    Your task is to invent completely new concurrency control protocols — beyond the scope of classical methods like 2PL (Two-Phase Locking), OCC (Optimistic Concurrency Control), or MVCC (Multi-Version Concurrency Control).
    First, propose a truly novel concurrency control protocol conceptually. Do NOT write code yet.

    You will be prompted to write a java code in the future for your protocol based on a codebase that will be provided.
    You will keep improving your protocol until it is correct and novel.

    {inspiration_block}

    Propose a radical, novel, and creative ideas for concurrency control protocols. 
    These should be:
    - Correct (i.e., ensure serializability or a similarly strong isolation level)
    - Efficient (good throughput and minimal overhead)
    - Novel (no rehashed or modified versions of 2PL, OCC, or MVCC)
    {inspiration_guidance}

    Let your imagination run wild — combine ideas from distributed systems, machine learning, cryptography, game theory, biology, physics, or even social systems. 
    The goal is to explore uncharted design space.

    Constraints:
    - Must be fundamentally different from classical 2PL, OCC, MVCC, SSI, or their trivial variants
    - Clearly define: metadata maintained, read/write visibility rule, commit rule, conflict handling, abort conditions
    - Explain why it can ensure serializability or a chosen isolation level
    - Discuss expected trade-offs (throughput, abort rate, latency) and workloads it suits
    - DO NOT repeat previous proposals.

    Output Format:
    - Provide a concise name and a 5-10 bullet point design spec
    - Core Idea: Summarize the intuition behind the protocol.
    - Novelty: Why is this approach truly different from traditional concurrency control mechanisms?
    - Correctness: How does the protocol ensure serializability (or another well-defined correctness criterion)? Provide justification.
    - How It Works (Step-by-Step): Describe the protocol’s execution flow, including:
        - Transaction begin
        - Read/write operations
        - Conflict detection/resolution (if any)
        - Commit/Rollback logic
    - Wrap any example traces or structured artifacts within <observe> ... </observe>
    - Trade-offs or Open Questions (optional): Any areas where further analysis or experimentation is needed.
    """
    return prompt


def get_propose_prompt_with_transactions2(transactions: list[str], inspiration_text: str | None = None) -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    inspiration_block = ""
    inspiration_guidance = ""
    if inspiration_text:
        inspiration_block = f"""
        Inspiration material (do not copy verbatim; use for ideas only):
        <inspiration>
        {inspiration_text}
        </inspiration>
        """
        inspiration_guidance = "- Use the inspiration text to guide your proposal"


    if transactions is None or len(transactions) <= 0:
        transactions = ""
        transactions_dir = os.path.join(os.path.dirname(__file__), "transactions")
        transaction_contents = []
        if os.path.isdir(transactions_dir):
            for fname in sorted(os.listdir(transactions_dir)):
                file_path = os.path.join(transactions_dir, fname)
                # Only include files (skip directories) and skip hidden files
                if os.path.isfile(file_path) and not fname.startswith('.'):
                    try:
                        with open(file_path, "r") as tfile:
                            transaction_contents.append(f"# File: {fname}\n" + tfile.read())
                    except Exception:
                        continue
        transactions = "\n\n".join(transaction_contents)
        

    transactions_block = ""
    if transactions:
        transactions_block = f"""
The pre-defined transactions are as follows:
<transactions>
{transactions}
</transactions>
        """
    
    prompt = f"""
You are an expert in database concurrency control research and design.
Your task is to design a concurrency control protocol that is optimized for a set of pre-known transaction types.

IMPORTANT:
    - This concurrency control protocol is optimized for a set of pre-known transaction types. 
    - Use the provided pre-defined transactions code to optimize your protocol design. 
    - You have access to the full code of these transactions. provided in the <transactions>...</transactions> tag.
    - FOR EXAMPLE: you can skip lock for specific keys, reorder locks, use commutative properties, or any other optimization that can make the protocol more efficient for these transactions. 
        Try any optimization that can make the protocol more efficient for these transactions.

GOAL:
    - The goal is to design a concurrency control to be as efficient as possible for the pre-defined transactions.

CONSTRAINTS:
    - Your protocol should be a design beyond the scope of classical methods like 2PL (Two-Phase Locking), OCC (Optimistic Concurrency Control), or MVCC (Multi-Version Concurrency Control).
        Simple implementations of 2PL, OCC, or MVCC are not allowed. Optimize them for the pre-defined transactions.
    - Clearly define: metadata maintained, read/write visibility rule, commit rule, conflict handling, abort conditions 
    - Explain why it can ensure serializability or a chosen isolation level
    - Discuss expected trade-offs (throughput, abort rate, latency) and workloads it suits
    - IMPORTANT: DO NOT repeat previous proposals. Your idea should be different from the previous proposals.


{transactions_block}


First, propose a the design of the concurrency control protocol conceptually. Do NOT write code yet.
You will be prompted to write a java code in the future for your protocol based on a codebase that will be provided.
You will keep improving your protocol until it is correct and serializable.

{inspiration_block}
{inspiration_guidance}



Output Format:
- Provide a concise name and a 5-10 bullet point design spec
- Core Idea: Summarize the intuition behind the protocol.
- Performance: How does the procotol is optimized for the pre-defined transactions?
- Correctness: How does the protocol ensure serializability (or another well-defined correctness criterion)? Provide justification.
- Complete details of how It Works (Step-by-Step): Describe the protocol’s execution flow, including:
    - Transaction begin
    - Read/write operations
    - Conflict detection/resolution (if any)
    - Commit/Rollback logic
- Wrap any example traces or structured artifacts within <observe> ... </observe>
- Trade-offs or Open Questions (optional): Any areas where further analysis or experimentation is needed.
    """
    return prompt



def get_propose_prompt_baseline_design(baseline_name: str, inspiration_text: str | None = None) -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    inspiration_block = ""
    inspiration_guidance = ""
    if inspiration_text:
        inspiration_block = f"""
        Inspiration material (do not copy verbatim; use for ideas only):
        <inspiration>
        {inspiration_text}
        </inspiration>
        """
        inspiration_guidance = "- Use the inspiration text to guide your proposal"

    
    prompt = f"""
You are an expert in database concurrency control research and design.
Your task is to give the design of a well-known concurrency control protocol.
The protocol you are giving the design for is: {baseline_name}.

GOAL:
    - The goal is to design a concurrency control to be as efficient as possibleo.

CONSTRAINTS:
    - Clearly define: metadata maintained, read/write visibility rule, commit rule, conflict handling, abort conditions 
    - Explain why it can ensure serializability or a chosen isolation level
    - Discuss expected trade-offs (throughput, abort rate, latency) and workloads it suits
    - IMPORTANT: DO NOT repeat previous proposals. Your idea should be different from the previous proposals.



First, propose a the design of the concurrency control protocol conceptually. Do NOT write code yet.
You will be prompted to write a java code in the future for your protocol based on a codebase that will be provided.
You will keep improving your protocol until it is correct and serializable.

{inspiration_block}
{inspiration_guidance}



Output Format:
- Provide a concise name and a 5-10 bullet point design spec
- Core Idea: Summarize the intuition behind the protocol.
- Correctness: How does the protocol ensure serializability (or another well-defined correctness criterion)? Provide justification.
- Complete details of how It Works (Step-by-Step): Describe the protocol’s execution flow, including:
    - Transaction begin
    - Read/write operations
    - Conflict detection/resolution (if any)
    - Commit/Rollback logic
- Wrap any example traces or structured artifacts within <observe> ... </observe>
- Trade-offs or Open Questions (optional): Any areas where further analysis or experimentation is needed.
    """
    return prompt


def get_propose_prompt_with_transactions(transactions: list[str], inspiration_text: str | None = None) -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    inspiration_block = ""
    inspiration_guidance = ""
    if inspiration_text:
        inspiration_block = f"""
        Inspiration material (do not copy verbatim; use for ideas only):
        <inspiration>
        {inspiration_text}
        </inspiration>
        """
        inspiration_guidance = "- Use the inspiration text to guide your proposal"
    prompt = f"""
    You are an expert in database concurrency control research and design.
    Your task is to invent completely new concurrency control protocols — beyond the scope of classical methods like 2PL (Two-Phase Locking), OCC (Optimistic Concurrency Control), or MVCC (Multi-Version Concurrency Control).
    This concurrency control protocol is optimized for some specific pre-defined transactions, however, it should be generalizable to other transactions.
    First, propose a truly novel concurrency control protocol conceptually. Do NOT write code yet.

    You will be prompted to write a java code in the future for your protocol based on a codebase that will be provided.
    You will keep improving your protocol until it is correct and novel.

    {inspiration_block}

    Propose a radical, novel, and creative ideas for concurrency control protocols. 
    These should be:
    - Correct (i.e., ensure serializability or a similarly strong isolation level)
    - Efficient (good throughput and minimal overhead)
    - Novel (no rehashed or modified versions of 2PL, OCC, or MVCC)
    {inspiration_guidance}

    Let your imagination run wild — combine ideas from distributed systems, machine learning, cryptography, game theory, biology, physics, or even social systems. 
    The goal is to explore uncharted design space.

    The pre-defined transactions are as follows:
    ```python
def wl_counter(r: ConcurrentRunner, txn: Transaction) -> None:
    v = r.log_read(txn, "ctr") or 0
    r.log_write(txn, "ctr", v + 1)
    r.log_commit(txn)


def wl_bank(r: ConcurrentRunner, txn: Transaction) -> None:
    # Initialize lazily if keys are absent
    va = r.log_read(txn, "a") or 0
    vb = r.log_read(txn, "b") or 0
    if va == 0 and vb == 0:
        # Initialize total to 1000 on first txn that finds zeros
        r.log_write(txn, "a", 500)
        r.log_write(txn, "b", 500)
        r.log_commit(txn)
        return
    x = random.randint(1, 5)
    if random.random() < 0.5:
        va, vb = va - x, vb + x
    else:
        va, vb = va + x, vb - x
    r.log_write(txn, "a", va)
    r.log_write(txn, "b", vb)
    r.log_commit(txn)
    ```

    

    Constraints:
    - Must be fundamentally different from classical 2PL, OCC, MVCC, SSI, or their trivial variants
    - Clearly define: metadata maintained, read/write visibility rule, commit rule, conflict handling, abort conditions
    - Explain why it can ensure serializability or a chosen isolation level
    - Discuss expected trade-offs (throughput, abort rate, latency) and workloads it suits
    - DO NOT repeat previous proposals.

    Output Format:
    - Provide a concise name and a 5-10 bullet point design spec
    - Core Idea: Summarize the intuition behind the protocol.
    - Novelty: Why is this approach truly different from traditional concurrency control mechanisms?
    - Correctness: How does the protocol ensure serializability (or another well-defined correctness criterion)? Provide justification.
    - How It Works (Step-by-Step): Describe the protocol’s execution flow, including:
        - Transaction begin
        - Read/write operations
        - Conflict detection/resolution (if any)
        - Commit/Rollback logic
    - Wrap any example traces or structured artifacts within <observe> ... </observe>
    - Trade-offs or Open Questions (optional): Any areas where further analysis or experimentation is needed.
    """
    return prompt





def get_generate_prompt() -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    prompt = f"""
    You are an expert in database concurrency control implementation.
    Your task is to implement a concurrency control protocol in Python based on the provided codebase.
    To do this, you will be provided a codebase as a guide to for implementing a concurrency control protocol. 
    Eventually, you'll create a concrete class that extends the abstract class ConcurrencyControl, 
        implementing the required methods (begin, write, read, commit, and rollback) in a way that confirms to concurrency control principles.
    We'll proceed step by step.

    Step 1: Understand the codebase
    The codebase is as follows:
    file: ../cc_core.py
    ```python
    {codebase}
    ```
    You can import required modules from the codebase as follows: 
    from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus

    

    Purpose: This step is essential to ground your implementation in the architecture already provided.

    Step 2: Implement the proposed Concurrency Control Protocol. 
    Your mission is to implement the completely new concurrency control protocols. You can fix this protocol based on the feedback provided.

    Make a plan first. The plan should be a numbered list of steps that you will take to solve the task. Be specific and detailed.
    Format your plan as a checklist with empty checkboxes like this:
    1. [ ] First step
    2. [ ] Second step
    3. [ ] Third step

    Follow the plan step by step. After completing each step, update the checklist by replacing the empty checkbox with a checkmark:
    1. [✓] First step (completed)
    2. [ ] Second step
    3. [ ] Third step

    If a step fails or needs modification, mark it with an X and explain why:
    1. [✓] First step (completed)
    2. [✗] Second step (failed because...)
    3. [ ] Modified second step
    4. [ ] Third step

    Always show the updated plan after each step so the user can track progress.

    At each turn, you should first provide your thinking and reasoning. 
    
    After all the steps are completed, you should provide the final implementation of the concurrency control protocol.
        Output format:
        * You should return your final implementation of the concurrency control protocol in python.
        * Your code should be enclosed using "<execute>" tag, for example: <execute> print("Hello World!") </execute>.
        * IMPORTANT: You must end the code block with </execute> tag.
        * the code inside the <execute> tag should be a valid python code. do not include any other text.
        * do not include any extra text such as "```python" or "```" in the code block.

        * Only return the code you newly implemented. Do not return the codebase as it already exists. 

        * Your final concurrency control protocol class should be named as "GeneratedCC" and it should extend the ConcurrencyControl class.

        * You can import required modules from the codebase as follows: 
          from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus

        * You can implement your own classes to be used in your concurrency control protocol if needed. 
        * IMPORTANT: do not re-implement any existing classes. You can only import and use them.
        * Your final implementation should be thread-safe and runnable in a concurrent setting.


    
    Your code will be tested in a concurrent setting afterward by a test harness that will be provided to check if it conforms to the concurrency control principles.
    If it does not, you should modify your code and try again given the feedback provided. 
    If it does, your implementation goes thorough novelty check and performance check and if it does not satisfy the requirements, you should modify your code and try again given the feedback provided.
    The feedback will be provided to you in the <observe> tag.
    """
    return prompt




def get_incorrect_code_prompt(feedback: str) -> str:
    prompt = f"""
    Your previous code is incorrect. Please modify it to fix the issues.
    The feedback is as follows:
    <observe>
    {feedback}
    </observe>
    You are just fixing the bug/problem in the code. Do not change the design concurrency control protocol.
    Follow the initial design, just fix issues that can result in incorrect behavior.
    """
    return prompt


def get_invalid_generate_prompt() -> str:
    prompt = f"""
    Your previous code is invalid. Please generate a new code.
    The code should be enclosed using "<execute>" tag, for example: <execute> print("Hello World!") </execute>.
    do not include any extra text such as "```python" or "```" in the code block.
    IMPORTANT: You must end the code block with </execute> tag.
    the code inside the <execute> tag should be a valid python code. do not include any other text.
    Only return the code you newly implemented. Do not return the codebase as it already exists. 
    Your final concurrency control protocol class should be named as "GeneratedCC" and it should extend the ConcurrencyControl class.
    You can import required modules from the codebase as follows: 
    from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus
    """
    return prompt


def get_final_report_prompt() -> str:
    prompt = f"""
    Please provide a concise final technical report of the implemented concurrency control protocol contained in the following code. 
    Only output the report, do not include any other text.
    Output: 
    * algorithm overview, read/write/lock or validation strategy, deadlock handling if any, correctness guarantees (e.g., conflict-serializability), complexity and performance notes, assumptions, and limitations.
    """
    return prompt


def get_generate_prompt_java() -> str:
    """Generate prompt for Java CC implementation."""
    # Read the Java interface files
    import os
    java_dir = os.path.join(os.path.dirname(__file__), "..", "tests_java")
    
    concurrency_java = ""
    kvstore_java = ""
    try:
        with open(os.path.join(java_dir, "Concurrency.java"), "r") as f:
            concurrency_java = f.read()
        with open(os.path.join(java_dir, "KVStore.java"), "r") as f:
            kvstore_java = f.read()
    except Exception:
        pass
    
    prompt = f"""
    You are an expert in database concurrency control implementation.
    Your task is to implement a concurrency control protocol in Java based on the provided codebase.
    To do this, you will be provided with Java interface files as a guide for implementing a concurrency control protocol. 
    Eventually, you'll create a concrete class that extends the abstract class ConcurrencyControl, 
    implementing the required methods (begin, write, read, commit, and rollback) in a way that conforms to concurrency control principles.
    We'll proceed step by step.
    
    Step 1: Understand the codebase
    The codebase is as follows:

    file: Concurrency.java (contains Transaction, CommitResult, TxStatus, and ConcurrencyControl abstract class)
    ```java
    {concurrency_java}
    ```

    file: KVStore.java (simple thread-safe key-value store)
    ```java
    {kvstore_java}
    ```

    IMPORTANT: Your implementation MUST:
    - Be in package `tests_java`
    - Create a class named `GeneratedCC` that extends `ConcurrencyControl`
    - Include a static method `public static ConcurrencyControl buildCc()` that returns a new instance of your CC implementation
    - Be thread-safe and work correctly in a highly concurrent environment (64+ threads)
    - Your implementation should be free of syntax errors, compile errors, and runtime errors.

    Purpose: This step is essential to ground your implementation in the architecture already provided.

    Step 2: Implement the proposed Concurrency Control Protocol. 
    Your mission is to implement the completely new concurrency control protocols. You can fix this protocol based on the feedback provided.

    Make a plan first. The plan should be a numbered list of steps that you will take to solve the task. Be specific and detailed.
    Format your plan as a checklist with empty checkboxes like this:
    1. [ ] First step
    2. [ ] Second step
    3. [ ] Third step

    Follow the plan step by step. After completing each step, update the checklist by replacing the empty checkbox with a checkmark:
    1. [✓] First step (completed)
    2. [ ] Second step
    3. [ ] Third step

    If a step fails or needs modification, mark it with an X and explain why:
    1. [✓] First step (completed)
    2. [✗] Second step (failed because...)
    3. [ ] Modified second step
    4. [ ] Third step

    Always show the updated plan after each step so the user can track progress.

    At each turn, you should first provide your thinking and reasoning. 
    
    After all the steps are completed, you should provide the final implementation of the concurrency control protocol.
        Output format:
        * You should return your final implementation of the concurrency control protocol in Java.
        * Your code should be enclosed using "<execute>" tag, for example: <execute> public class GeneratedCC ... </execute>.
        * IMPORTANT: You must end the code block with </execute> tag.
        * The code inside the <execute> tag should be valid Java code. Do not include any other text.
        * Do not include any extra text such as "```java" or "```" in the code block.

        * Only return the code you newly implemented. Do not return the codebase as it already exists. 

        * Your final concurrency control protocol class MUST be named "GeneratedCC" and it MUST extend the ConcurrencyControl class. Do not include `public` when defining the class.
        * Your class MUST be in package `tests_java`.
        * Your class MUST include: `public static ConcurrencyControl buildCc() {{ return new GeneratedCC(); }}`
        

        * You can implement your own helper classes within the same file if needed. 
        * IMPORTANT: Do not re-implement any existing classes from Concurrency.java or KVStore.java.
        * Your final implementation should be thread-safe and runnable in a concurrent setting with 64+ threads.


    
    Your code will be tested in a concurrent setting afterward by a test harness that will be provided to check if it conforms to the concurrency control principles.
    If it does not, you should modify your code and try again given the feedback provided. 
    If it does, your implementation goes through novelty check and performance check and if it does not satisfy the requirements, you should modify your code and try again given the feedback provided.
    The feedback will be provided to you in the <observe> tag.
    """
    return prompt


def get_incorrect_code_prompt_java(feedback: str) -> str:
    """Prompt for fixing incorrect Java code."""
    prompt = f"""
    Your previous code is INCORRECT. It does not meet all the requirements 
    * serializability
    * progress
    * ensuring freedom from stalls


    Rview the feedback metrics and artifacts provided below to identify and understand the specific issues in your code implementation.
    Revise your code to address the issues.


    The feedback is as follows:
    <observe>
    {feedback}
    </observe>


    You are just fixing the bug/problem in the code. Do not change the design of the concurrency control protocol.
    Follow the initial design, just fix issues that can result in incorrect behavior.
    
    Remember:
    - Your class MUST be named "GeneratedCC" and extend ConcurrencyControl
    - Your class MUST be in package `tests_java`
    - Your class MUST include: `public static ConcurrencyControl buildCc() {{ return new GeneratedCC(); }}`
    - Your code should be enclosed using "<execute>" tag
    - The code must be thread-safe for 64+ concurrent threads
    """
    return prompt


def get_invalid_generate_prompt_java() -> str:
    """Prompt for invalid Java code generation."""
    prompt = f"""
    Your previous Java code is invalid. Please generate new code.
    The code should be enclosed using "<execute>" tag, for example: <execute> package tests_java; public class GeneratedCC ... </execute>.
    Do not include any extra text such as "```java" or "```" in the code block.
    IMPORTANT: You must end the code block with </execute> tag.
    The code inside the <execute> tag should be valid Java code. Do not include any other text.
    Only return the code you newly implemented. Do not return the codebase as it already exists. 
    Your final concurrency control protocol class MUST be named "GeneratedCC" and it MUST extend the ConcurrencyControl class.
    Your class MUST be in package `tests_java`.
    Your class MUST include: `public static ConcurrencyControl buildCc() {{ return new GeneratedCC(); }}`
    """
    return prompt


def get_novelty_prompt(code: str, protocol_proposal: str) -> str:
    prompt = f"""
    You are a senior database researcher and prior-art reviewer. 
    Your job is to judge how **new and novel** a proposed concurrency control (CC) protocol is, 
    considering both its conceptual proposal and the concrete Python implementation.

    ## Inputs:

    * **Proposal (concept):**
    
    {protocol_proposal}

    * **Implementation (code):**
    ```python
    {code}
    ```
    """ + """
    ## What to Evaluate

    Focus strictly on *novelty/originality* of the protocol’s **core control mechanics**, not general code quality or raw performance.
    Determine whether the protocol is materially different from known families rather than a rebranding or minor tweak.

    ### Extract and analyze these axes

    1. **Core mechanism:** Ordering basis (e.g., timestamps/logical clocks/deterministic preordering), locking vs. versioning vs. validation vs. scheduling tricks, conflict model (RW/WW/WR), and whether it’s single- or multi-version.
    2. **Metadata/structures:** Lock tables, wait-for graphs, read/write sets, dependency graphs, per-item version chains, commit timestamps, validation summaries, escrow counters, CRDT/commutativity metadata, certificates, quorum proofs, etc.
    3. **Read/Write visibility rules** and **commit/abort rules**, deadlock/livelock handling, starvation/progress guarantees.
    4. **Where it runs:** centralized, partitioned, replicated; deterministic batching; optimistic vs. pessimistic phases; speculative or learning-guided choices.
    5. **Correctness rationale:** How serializability (or chosen isolation) is *ensured*, not just asserted.

    ### Compare against known CC families and patterns

    Check for overlap with (non-exhaustive):
    * 2PL (strict/rigorous), wound-wait / wait-die, deadlock detection
    * OCC (Kung–Robinson style read-set validation at commit)
    * MVCC / SSI / S2PL+SI / snapshot reads with commit-timestamp visibility
    * Timestamp ordering (TO), MVTO, Thomas write rule
    * Deterministic ordering (e.g., Calvin/BOHM/Aria)
    * Hardware transactional memory patterns; Silo/Cicada/Hekaton; TicToc; MaaT; FOEDUS
    * Dependency-graph / serialization graph testing; commit ordering constraints
    * RAMP/atomic visibility, escrow locking, commutativity/CRDT approaches, phase reconciliation
    * Quorum/consensus-centric CC (Raft/Paxos ordering used as CC), RCU-like reads
    * Any other known concurrency control protocols

    > If the proposal/code ultimately reduces to one of the above (or a simple hybrid of them) with renamed parts or parameter tweaks, it is **not** novel.

    ### Novelty Rubric (100 points total)

    Score each dimension, then compute the overall score as the **weighted sum** below. Round final scores to the nearest integer in [0,100].

    * **Conceptual originality (40%)** — Is the core mechanism genuinely new (not a simple variation of locks/OCC/MVCC/TO/deterministic preordering/SSI)?
    * **Distance from known families (30%)** — How dissimilar is it from the most similar existing family (structure, rules, invariants)?
    * **Implementation distinctiveness (15%)** — Does the code operationalize the new idea (data structures, invariants) rather than quietly implementing a standard scheme?
    * **Correctness argument originality (10%)** — Does the *reason* it’s correct differ meaningfully from standard proofs/validations?
    * **Rebranding penalty (− up to 15%)** — Subtract if it’s largely renaming/tuning/classic-hybrid with trivial novelty. (This can’t take the total below 0.)

    Guidance for verdicts:

    * **0–19 Derivative** — Classic method in new clothes.
    * **20–39 Minor variation** — Small twist on known scheme.
    * **40–59 Incremental hybrid** — Some new combination but largely standard.
    * **60–79 Moderately novel** — Fresh organizing principle with real differences.
    * **80–100 Highly/Radically novel** — New control paradigm with distinct invariants.

    ### What to check in the code (quick reality check)

    * Do reads observe snapshots or current versions? Are versions/locks maintained?
    * Is there commit-time validation with read-set checks (OCC)?
    * Are there explicit lock modes and a two-phase pattern (2PL)?
    * Are timestamps (physical/logic/HLC) the *ultimate* arbiter (TO/MVTO)?
    * Is there up-front deterministic ordering/batching (Calvin/BOHM)?
    * Any serialization/dependency graph cycle check (SGT/SSI variants)?
    If “yes” in a way that controls conflicts, the design likely maps to that family.

    ## Output Format (JSON only)

    Return **only** valid JSON with this schema (no extra text):

    ```json
    {
    "novelty_score_overall": 0,
    "verdict": "Derivative | Minor Variation | Incremental Hybrid | Moderately Novel | Highly Novel",
    "score_breakdown": {
        "conceptual_originality_40": 0,
        "distance_from_known_families_30": 0,
        "implementation_distinctiveness_15": 0,
        "correctness_argument_originality_10": 0,
        "rebranding_penalty_minus15": 0
    },
    "separate_scores": {
        "proposal_novelty": 0,
        "code_novelty": 0
    },
    "overlap_analysis": [
        {
        "family": "NAME (e.g., OCC, MVCC, 2PL, Calvin, TicToc, etc.)",
        "overlap_reasoning": "Concise evidence of similarity/differences.",
        "overlap_strength_0_to_100": 0
        }
    ],
    "feature_diff": [
        {
        "axis": "e.g., commit rule / visibility / metadata / ordering basis",
        "ours": "What this protocol does",
        "typical_in_nearest_family": "What the closest prior does",
        "difference_note": "Why that difference matters for novelty"
        }
    ],
    "code_checks": {
        "uses_locks_or_lock_modes": true,
        "uses_versions_or_snapshots": true,
        "commit_time_validation": true,
        "timestamp_ordering_core": false,
        "deterministic_preordering": false,
        "dependency_graph_cycles": false,
        "other_key_constructs": ["..."]
    },
    "correctness_rationale_summary": "≤120 words on how correctness is ensured and whether that rationale itself is novel.",
    "risks_and_edge_cases": ["Concise bullets about places it collapses to known behavior or hides classic mechanisms."],
    "suggestions_to_increase_novelty": [
        "Actionable, concrete suggestions that would push it farther from known families."
    ],
    "confidence_0_to_1": 0.0
    }
    ```

    ## Review Procedure
    1. **Extract** the protocol’s key mechanics from the proposal and code along the axes above.
    2. **Map** to closest families and assign `overlap_strength_0_to_100` for each (0=no overlap, 100=identical).
    3. **Judge** whether the *operational control* in the code actually realizes the claimed new idea (penalize if it degenerates to classic patterns).
    4. **Score** each rubric dimension and compute the overall weighted score minus the rebranding penalty; round to integer 0–100.
    5. **Return JSON only**, following the schema exactly.

    **Important rules**
    * Prefer concrete, testable evidence from the code and proposal over vague claims.
    * Using ML/heuristics/schedulers is **not novel** if conflicts are ultimately governed by a classic mechanism.
    * Hybridization is not automatically novel; it must create a new invariant or control law.
    * Be conservative: when in doubt and evidence is weak, reduce the novelty score.
    """

    return prompt