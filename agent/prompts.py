

def get_propose_prompt() -> str:
    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    prompt = f"""
    You are an expert in database concurrency control research and design.
    Your task is to invent completely new concurrency control protocols — beyond the scope of classical methods like 2PL (Two-Phase Locking), OCC (Optimistic Concurrency Control), or MVCC (Multi-Version Concurrency Control).
    First, propose a truly novel concurrency control protocol conceptually. Do NOT write code yet.

    You will be prompted to write a python code in the future for your protocol based on a codebase that will be provided.
    You will keep improving your protocol until it is correct and novel.

    Propose a radical, novel, and creative ideas for concurrency control protocols. 
    These should be:
    - Correct (i.e., ensure serializability or a similarly strong isolation level)
    - Efficient (good throughput and minimal overhead)
    - Novel (no rehashed or modified versions of 2PL, OCC, or MVCC)

    Let your imagination run wild — combine ideas from distributed systems, machine learning, cryptography, game theory, biology, physics, or even social systems. 
    The goal is to explore uncharted design space.

    Constraints:
    - Must be fundamentally different from classical 2PL, OCC, MVCC, SSI, or their trivial variants
    - Clearly define: metadata maintained, read/write visibility rule, commit rule, conflict handling, abort conditions
    - Explain why it can ensure serializability or a chosen isolation level
    - Discuss expected trade-offs (throughput, abort rate, latency) and workloads it suits

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
        implementing the required methods (begin, write, read, commit, and rollback) in a way that conforms to concurrency control principles.
    We'll proceed step by step.

    Step 1: Understand the codebase
    The codebase is as follows:
    file: ../cc_core.py
    '''python
    {codebase}
    '''
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
    Output: 
    * algorithm overview, read/write/lock or validation strategy, deadlock handling if any, correctness guarantees (e.g., conflict-serializability), complexity and performance notes, assumptions, and limitations.
    * Code in the following format:
    ```python
    code_text
    ```
    """
    return prompt