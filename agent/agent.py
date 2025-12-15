from typing import Annotated, Literal, TypedDict
import os
from pathlib import Path
import re
import sys
import io
import unittest
import importlib
import traceback

from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage
from langgraph.checkpoint.memory import MemorySaver


from .llm import get_llm
from .prompts import (
    get_generate_prompt, get_incorrect_code_prompt, get_invalid_generate_prompt, 
    get_propose_prompt, get_final_report_prompt, get_novelty_prompt, 
    get_propose_prompt_with_transactions, get_propose_prompt_with_transactions2,
    get_generate_prompt_java, get_incorrect_code_prompt_java, get_invalid_generate_prompt_java
)
from utils import Utils
import subprocess
import json



class State(TypedDict):
    messages: Annotated[list[str], add_messages]
    next_step: str | None
    code_iteration: int

    current_protocol_proposal: str
    current_code: str
    novelty_result: str
    language_mode: str  # "python" or "java"


class Agent:

    def __init__(self, inspiration_pdf_path: str | None = None, inspiration_text: str | None = None, language_mode: str = "python") -> None:
        """
        Initialize the agent.
        
        Args:
            inspiration_pdf_path: Path to a PDF file for inspiration
            inspiration_text: Text for inspiration
            language_mode: "python" or "java" - determines which language to generate code in
        """
        self.language_mode = language_mode.lower()
        if self.language_mode not in ("python", "java"):
            raise ValueError(f"Invalid language_mode: {language_mode}. Must be 'python' or 'java'.")
        
        self.workflow = self.build_workflow()
        self.llm = get_llm()
        self.checkpointer = MemorySaver()
        self.workflow.checkpointer = self.checkpointer
        self.novelty_llm = get_llm()
        if inspiration_text:
            self.inspiration_source_text = inspiration_text
        elif inspiration_pdf_path:
            self.inspiration_source_text = Utils.extract_text_from_pdf_path(inspiration_pdf_path, max_chars=120000)
        else:
            self.inspiration_source_text = None




    def get_workflow(self) -> StateGraph:
        return self.workflow



    def trim_fix_loop_messages(self, messages: list, max_fix_messages: int = 12) -> list:
        # trim the message history within the fix loop to a fixed-size window, preserving the preamble before the first fix prompt
        if not messages or max_fix_messages <= 0:
            return messages

        sentinel_substrings = (
            "Your previous code is incorrect.",
            "Your previous code is invalid.",
            "Your previous Java code is incorrect.",
            "Your previous Java code is invalid.",
        )

        fix_start_index = None
        for idx, msg in enumerate(messages):
            content = getattr(msg, "content", "") or ""
            if any(s in content for s in sentinel_substrings):
                fix_start_index = idx
                break

        if fix_start_index is None:
            return messages

        # Keep the full preamble, trim only the fix-loop segment
        head_messages = messages[:fix_start_index]
        fix_segment = messages[fix_start_index:]
        if len(fix_segment) <= max_fix_messages:
            return messages

        return head_messages + fix_segment[-max_fix_messages:]

    def get_previous_proposals(self) -> str:
        previous_proposals = "Previous Proposals:\n"
        project_root = Path(__file__).resolve().parent.parent
        final_gen_dir = project_root / "cc_protocols" / "final_generations"
        
        # Check Python proposals
        for p in final_gen_dir.glob("generated_cc_*.py"):
            m = re.match(r"generated_cc_(\d+)\.py", p.name)
            if m:
                idx = int(m.group(1))
                content = p.read_text(encoding="utf-8")
                # Extract the text between "''' Technical Report:" and the next "'''" from the file content
                tech_report = ""
                tech_report_start = content.find("''' Technical Report:")
                if tech_report_start != -1:
                    tech_report_start += len("''' Technical Report:")
                    tech_report_end = content.find("'''", tech_report_start)
                    if tech_report_end != -1:
                        tech_report = content[tech_report_start:tech_report_end].strip()
                previous_proposals += f"[Python] {idx}. {tech_report}\n"
        
        # Check Java proposals (same directory)
        for p in final_gen_dir.glob("generated_cc_*.java"):
            m = re.match(r"generated_cc_(\d+)\.java", p.name)
            if m:
                idx = int(m.group(1))
                content = p.read_text(encoding="utf-8")
                # Extract the text between "/* Technical Report:" and the next "*/" from the file content
                tech_report = ""
                tech_report_start = content.find("/* Technical Report:")
                if tech_report_start != -1:
                    tech_report_start += len("/* Technical Report:")
                    tech_report_end = content.find("*/", tech_report_start)
                    if tech_report_end != -1:
                        tech_report = content[tech_report_start:tech_report_end].strip()
                previous_proposals += f"[Java] {idx}. {tech_report}\n"
        
        return previous_proposals

    def build_workflow(self):
        def cc_proposer(state: State) -> State:
            # reset previous messages and provide previous proposals
            # state["messages"] = Annotated[list[str], add_messages]
            # state["messages"].clear()

            # Set the language mode from agent configuration
            state["language_mode"] = self.language_mode

            previous_proposals = self.get_previous_proposals()
            # Prepare an LLM summary (<=5000 chars) of any provided inspiration text
            inspiration_summary = None
            if getattr(self, "inspiration_source_text", None):
                try:
                    inspiration_summary = self.summarize_for_prompt(self.inspiration_source_text, max_chars=5000)
                except Exception:
                    inspiration_summary = None

            # prompt = get_propose_prompt(inspiration_text=inspiration_summary)
            prompt = get_propose_prompt_with_transactions2([], inspiration_text=inspiration_summary)


            state["messages"].append(HumanMessage(content=prompt))
            llm_response = str(self.llm.invoke(state["messages"] + [HumanMessage(content=previous_proposals)]).content )
            state["current_protocol_proposal"] = llm_response
            state["messages"].append(AIMessage(content=llm_response))
            state["next_step"] = "generate"
            state["code_iteration"] = 0
            return state

        def cc_generator(state: State) -> State:
            project_root = Path(__file__).resolve().parent.parent
            language_mode = state.get("language_mode", "python")
            
            if language_mode == "java":
                # Java code generation
                java_dir = project_root / "tests_java"
                generated_path = java_dir / "GeneratedCC.java"
                
                if state["code_iteration"] == 0:
                    state["messages"].append(HumanMessage(content=get_generate_prompt_java()))
            else:
                # Python code generation (default)
                proto_dir = project_root / "cc_protocols"
                generated_path = proto_dir / "generated_cc.py"
                
                if state["code_iteration"] == 0: 
                    state["messages"].append(HumanMessage(content=get_generate_prompt()))

            state["messages"] = self.trim_fix_loop_messages(state["messages"], max_fix_messages=6)
            llm_response = str(self.llm.invoke(state["messages"]).content)

            if "<execute>" in llm_response and "</execute>" not in llm_response:
                llm_response += "</execute>"

            if "</observe>" in llm_response and "<observe>" not in llm_response:
                llm_response += "</observe>"

            execute_match = re.search(r"<execute>(.*?)</execute>", llm_response, re.DOTALL)
            execute_code = execute_match.group(1) if execute_match else ""

            state["code_iteration"] += 1    
            state["messages"].append(AIMessage(content=f"Iteration: {state['code_iteration']}, {llm_response.strip()}"))
            
            if execute_match:
                if language_mode == "java":
                    # Write Java code
                    java_dir = project_root / "tests_java"
                    java_dir.mkdir(parents=True, exist_ok=True)
                    generated_path = java_dir / "GeneratedCC.java"
                    with open(generated_path, "w", encoding="utf-8") as f:
                        f.write(execute_code)
                else:
                    # Write Python code
                    proto_dir = project_root / "cc_protocols"
                    proto_dir.mkdir(parents=True, exist_ok=True)
                    generated_path = proto_dir / "generated_cc.py"
                    with open(generated_path, "w", encoding="utf-8") as f:
                        f.write(execute_code)

                    # Ensure package exports GeneratedCC
                    init_path = proto_dir / "__init__.py"
                    try:
                        content = init_path.read_text(encoding="utf-8")
                    except FileNotFoundError:
                        content = ""
                    if "GeneratedCC" not in content:
                        updated = content.rstrip() + "\nfrom .generated_cc import GeneratedCC\n"
                        init_path.write_text(updated, encoding="utf-8")
                
                state["next_step"] = "execute"
            else:
                # does not have <execute> tag
                if language_mode == "java":
                    state["messages"].append(HumanMessage(content=get_invalid_generate_prompt_java()))
                else:
                    state["messages"].append(HumanMessage(content=get_invalid_generate_prompt()))
                state["next_step"] = "generate"

            
            return state

        def cc_correctness_checker(state: State) -> State:
            language_mode = state.get("language_mode", "python")
            project_root = Path(__file__).resolve().parent.parent
            
            if language_mode == "java":
                return _check_java_correctness(state, project_root)
            else:
                return _check_python_correctness(state, project_root)
        
        def _check_python_correctness(state: State, project_root: Path) -> State:
            """Check correctness using Python tests."""
            # Ensure project root on sys.path
            if str(project_root) not in sys.path:
                sys.path.insert(0, str(project_root))

            # Ensure generated code exists
            generated_path = project_root / "cc_protocols" / "generated_cc.py"
            if not generated_path.exists():
                state["messages"].append(AIMessage(content="<observe>GeneratedCC not found at cc_protocols/generated_cc.py. Please generate code first.</observe>"))
                state["next_step"] = "incorrect"
                return state

            # Point tests to use GeneratedCC implementation
            os.environ["CC_IMPL"] = "GeneratedCC"

            # Import tests and run suite, capturing output
            output_stream = io.StringIO()
            try:
                # Always reload in case of iterative changes
                tests_module = importlib.import_module("tests.test_serializability")
                importlib.reload(tests_module)
                
                # Reload GeneratedCC to ensure the latest version is used
                generated_cc_module = importlib.import_module("cc_protocols.generated_cc")
                importlib.reload(generated_cc_module)
                
                suite = unittest.defaultTestLoader.loadTestsFromModule(tests_module)
                runner = unittest.TextTestRunner(stream=output_stream, verbosity=2)
                result = runner.run(suite)
                summary = f"Ran {result.testsRun} tests. Failures: {len(result.failures)}. Errors: {len(result.errors)}."
                details_lines = [summary]
                full_output = output_stream.getvalue()
                if full_output.strip():
                    details_lines.append("Output:\n" + full_output)
                feedback = "\n".join(details_lines)

                # Add feedback message for the LLM
                if result.wasSuccessful():
                    state["messages"].append(HumanMessage(content="All tests passed."))
                    state["next_step"] = "correct"
                    state["current_code"] = generated_path.read_text(encoding="utf-8")
                else:
                    state["messages"].append(HumanMessage(content=get_incorrect_code_prompt(feedback)))
                    state["next_step"] = "incorrect"
            except Exception as e:
                err_text = traceback.format_exc()
                state["messages"].append(HumanMessage(content=f"<observe>Exception while running tests: {e}\n{err_text}</observe>"))
                state["next_step"] = "incorrect"
            finally:
                output_stream.close()
            
            return state
        
        def _check_java_correctness(state: State, project_root: Path) -> State:
            """Check correctness using Java evaluator."""
            java_dir = project_root / "tests_java"
            generated_path = java_dir / "GeneratedCC.java"
            
            if not generated_path.exists():
                state["messages"].append(AIMessage(content="<observe>GeneratedCC.java not found at tests_java/GeneratedCC.java. Please generate code first.</observe>"))
                state["next_step"] = "incorrect"
                return state
            
            # Run the Java evaluator
            output_json_path = java_dir / "output.json"
            run_script = java_dir / "run_evaluator.sh"
            
            try:
                # Run the evaluator script
                result = subprocess.run(
                    ["bash", str(run_script), str(generated_path), str(output_json_path)],
                    cwd=str(java_dir),
                    capture_output=True,
                    text=True,
                    timeout=180  # 3 minute timeout
                )
                
                stdout = result.stdout
                stderr = result.stderr
                
                
                # Check for compilation errors
                if result.returncode != 0:
                    error_msg = f"Java compilation/execution failed.\nstdout:\n{stdout}\nstderr:\n{stderr}"
                    state["messages"].append(HumanMessage(content=get_incorrect_code_prompt_java(error_msg)))
                    state["next_step"] = "incorrect"
                    return state
                
                # Parse the output JSON
                if output_json_path.exists():
                    with open(output_json_path, "r") as f:
                        eval_result = json.load(f)
                    
                    metrics = eval_result.get("metrics", {})
                    feedback = str(eval_result)
                    correctness = metrics.get("correctness", 0.0)    
                    
                    if correctness >= 1.0:
                        state["messages"].append(HumanMessage(content=f"All tests passed!\n{feedback}"))
                        state["next_step"] = "correct"
                        state["current_code"] = generated_path.read_text(encoding="utf-8")
                    else:
                        state["messages"].append(HumanMessage(content=get_incorrect_code_prompt_java(feedback)))
                        state["next_step"] = "incorrect"
                else:
                    # No output file - something went wrong
                    error_msg = f"No output.json generated.\nstdout:\n{stdout}\nstderr:\n{stderr}"
                    state["messages"].append(HumanMessage(content=get_incorrect_code_prompt_java(error_msg)))
                    state["next_step"] = "incorrect"
                    
            except subprocess.TimeoutExpired:
                state["messages"].append(HumanMessage(content=get_incorrect_code_prompt_java("Java evaluation timed out after 3 minutes. The implementation may have deadlocks or infinite loops.")))
                state["next_step"] = "incorrect"
            except Exception as e:
                err_text = traceback.format_exc()
                state["messages"].append(HumanMessage(content=f"<observe>Exception while running Java tests: {e}\n{err_text}</observe>"))
                state["next_step"] = "incorrect"
            
            return state
        


        def cc_performance_evaluator(state: State) -> State:
            return state

        def cc_novelty_evaluator(state: State) -> State:
            prompt = get_novelty_prompt(state["current_code"], state["current_protocol_proposal"])
            llm_response = str(self.novelty_llm.invoke(prompt).content)
            state["novelty_result"] = llm_response
            state["next_step"] = "end"
            return state

        def cc_final_report(state: State) -> State:
            # Save the latest generated CC to a new versioned file and ask LLM for a final report
            project_root = Path(__file__).resolve().parent.parent
            language_mode = state.get("language_mode", "python")
            
            # All final generations go to cc_protocols/final_generations
            final_gen_dir = project_root / "cc_protocols" / "final_generations"
            
            if language_mode == "java":
                current_path = project_root / "tests_java" / "GeneratedCC.java"
                file_ext = ".java"
                comment_start = "/*"
                comment_end = "*/"
            else:
                current_path = project_root / "cc_protocols" / "generated_cc.py"
                file_ext = ".py"
                comment_start = "'''"
                comment_end = "'''"

            try:
                code_text = current_path.read_text(encoding="utf-8")
            except FileNotFoundError:
                code_text = ""

            # Determine next index n for generated_cc_n.{py/java}
            max_idx = 0
            for p in final_gen_dir.glob(f"generated_cc_*{file_ext}"):
                m = re.match(rf"generated_cc_(\d+){re.escape(file_ext)}", p.name)
                if m:
                    idx = int(m.group(1))
                    if idx > max_idx:
                        max_idx = idx
            next_idx = max_idx + 1
            new_path = final_gen_dir / f"generated_cc_{next_idx}{file_ext}"

            # Ask LLM for a final technical report of the protocol
            report_prompt = get_final_report_prompt()
            state["messages"].append(HumanMessage(content=report_prompt))
            llm_response = str(self.llm.invoke(state["messages"]).content)
            # Add the LLM response as a comment at the beginning of the generated file
            if code_text:
                # Remove code within <execute>...</execute> or code blocks from the LLM response
                cleaned_llm_response = re.sub(r"<execute>.*?</execute>", "", llm_response, flags=re.DOTALL)
                cleaned_llm_response = re.sub(r"```python.*?```", "", cleaned_llm_response, flags=re.DOTALL)
                cleaned_llm_response = re.sub(r"```java.*?```", "", cleaned_llm_response, flags=re.DOTALL)
                
                # Ensure there are no comment markers in the cleaned LLM response
                cleaned_llm_response = cleaned_llm_response.replace(comment_start, "")
                cleaned_llm_response = cleaned_llm_response.replace(comment_end, "")

                llm_comment = f"{comment_start} Technical Report:\n{cleaned_llm_response}\n{comment_end}\n\n"
                novelty_comment = f"{comment_start} Novelty Result:\n{state['novelty_result']}\n{comment_end}\n\n"
                final_gen_dir.mkdir(parents=True, exist_ok=True)
                new_path.write_text(llm_comment + novelty_comment + code_text, encoding="utf-8")
            state["messages"].append(AIMessage(content=llm_response))
            state["messages"].append(AIMessage(content=f'''Novelty Result:\n```json\n{state["novelty_result"]}\n```'''))
            state["next_step"] = "end"
            return state
        
        workflow_builder = StateGraph(State)
        workflow_builder.add_node("cc_proposer", cc_proposer)
        workflow_builder.add_node("cc_generator", cc_generator)
        workflow_builder.add_node("cc_correctness_checker", cc_correctness_checker)
        workflow_builder.add_node("cc_performance_evaluator", cc_performance_evaluator)
        workflow_builder.add_node("cc_novelty_evaluator", cc_novelty_evaluator)
        workflow_builder.add_node("cc_final_report", cc_final_report)

        workflow_builder.add_edge(START, "cc_proposer")

        workflow_builder.add_edge("cc_proposer", "cc_generator")

        workflow_builder.add_conditional_edges("cc_generator", lambda state: state["next_step"], {
            "execute": "cc_correctness_checker",
            "generate": "cc_generator"
        })
        workflow_builder.add_conditional_edges("cc_correctness_checker",lambda state: state["next_step"], {
            "correct": "cc_performance_evaluator", 
            "incorrect": "cc_generator",
        })
        workflow_builder.add_edge("cc_performance_evaluator", "cc_novelty_evaluator")
        workflow_builder.add_conditional_edges("cc_novelty_evaluator", lambda state: state["next_step"], {
            "improve": "cc_proposer",
            "end": "cc_final_report",
        })
        workflow_builder.add_edge("cc_final_report", END)

        return workflow_builder.compile()

    def summarize_for_prompt(self, text: str, max_chars: int = 5000) -> str:
        if not text:
            return ""
        instruction = (
            f"Summarize the following academic text into <= {max_chars} characters. "
            "Emphasize ideas relevant to database concurrency control, isolation, transaction processing, ordering, locks/versions, validation, scheduling, and conflict handling. "
            "Return plain text only (no markdown code blocks)."
        )
        prompt = f"{instruction}\n\n<text>\n{text}\n</text>"
        try:
            response = str(self.llm.invoke([HumanMessage(content=prompt)]).content)
        except Exception:
            response = text
        return response[:max_chars]




