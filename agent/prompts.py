


def get_generate_prompt() -> str:

    with open('cc_core.py', 'r') as file:
        codebase = file.read()
    prompt = f"""
    You are an expert in database concurrency control design and implementation.
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



    Step 2: Design a new and novel Concurrency Control Protocol
    Your mission is to invent completely new concurrency control protocols — beyond the scope of classical methods like 2PL (Two-Phase Locking), OCC (Optimistic Concurrency Control), or MVCC (Multi-Version Concurrency Control).
    IMPORTANT: Implementing a protocol that is already known or similar to existing protocols is not allowed

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

        * Only return the code you newly implemented. Do not return the codebase as it already exists. 

        * Your final concurrency control protocol class should be named as "GeneratedCC" and it should extend the ConcurrencyControl class.

        * You can import required modules from the codebase as follows: 
          from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus

        * You can implement your own classes to be used in the concurrency control protocol. 
        * IMPORTANT: do not re-implement any existing classes. You can only import and use them.


    
    Your code will be tested afterward by a test harness that will be providedto to check if it conforms to the concurrency control principles.
    If it does not, you should modify your code and try again given the feedback provided. 
    If it does, your implementation goes thorough novelty check and performance check and if it does not satisfy the requirements, you should modify your code and try again given the feedback provided.
    The feedback will be provided to you in the <observe> tag.
    """

    return prompt