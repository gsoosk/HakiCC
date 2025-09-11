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
from .prompts import get_generate_prompt, get_incorrect_code_prompt, get_invalid_generate_prompt, get_propose_prompt, get_final_report_prompt



class State(TypedDict):
    messages: Annotated[list[str], add_messages]
    next_step: str | None


class Agent:

    def __init__(self) -> None:
        self.workflow = self.build_workflow()
        self.llm = get_llm()
        self.code_iteration = 0
        self.checkpointer = MemorySaver()
        self.workflow.checkpointer = self.checkpointer




    def get_workflow(self) -> StateGraph:
        return self.workflow



    def build_workflow(self):
        def cc_proposer(state: State) -> State:
            prompt = get_propose_prompt()
            state["messages"].append(HumanMessage(content=prompt))
            llm_response = str(self.llm.invoke(state["messages"]).content)
            state["messages"].append(AIMessage(content=llm_response))
            state["next_step"] = "generate"
            return state

        def cc_generator(state: State) -> State:
            project_root = Path(__file__).resolve().parent.parent
            proto_dir = project_root / "cc_protocols"
            generated_path = proto_dir / "generated_cc.py"
            if self.code_iteration == 0: 
                state["messages"].append(HumanMessage(content=get_generate_prompt()))


            llm_response = str(self.llm.invoke(state["messages"]).content)

            if "<execute>" in llm_response and "</execute>" not in llm_response:
                llm_response += "</execute>"

            if "</observe>" in llm_response and "<observe>" not in llm_response:
                llm_response += "</observe>"

            execute_match = re.search(r"<execute>(.*?)</execute>", llm_response, re.DOTALL)
            execute_code = execute_match.group(1) if execute_match else ""
            self.code_iteration += 1
            state["messages"].append(AIMessage(content=f"Iteration: {self.code_iteration}, {llm_response.strip()}"))
            if execute_match:
                proto_dir.mkdir(parents=True, exist_ok=True)
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
                state["messages"].append(HumanMessage(content=get_invalid_generate_prompt()))
                state["next_step"] = "generate"

            
            return state

        def cc_correctness_checker(state: State) -> State:
            # Ensure project root on sys.path
            project_root = Path(__file__).resolve().parent.parent
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
                # if result.failures:
                #     details_lines.append("Failures:")
                #     for case, tb in result.failures:
                #         details_lines.append(f"- {case.id()}\n{tb}")
                # if result.errors:
                #     details_lines.append("Errors:")
                #     for case, tb in result.errors:
                #         details_lines.append(f"- {case.id()}\n{tb}")
                full_output = output_stream.getvalue()
                if full_output.strip():
                    details_lines.append("Output:\n" + full_output)
                feedback = "\n".join(details_lines)

                # Add feedback message for the LLM
                if result.wasSuccessful():
                    state["messages"].append(HumanMessage(content="All tests passed."))
                    state["next_step"] = "correct"
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
        


        def cc_performance_evaluator(state: State) -> State:
            return state

        def cc_novelty_evaluator(state: State) -> State:
            state["next_step"] = "end"
            return state

        def cc_final_report(state: State) -> State:
            # Save the latest generated CC to a new versioned file and ask LLM for a final report
            project_root = Path(__file__).resolve().parent.parent
            final_gen_dir = project_root / "cc_protocols" / "final_generations"
            current_path = project_root / "cc_protocols" / "generated_cc.py"

            try:
                code_text = current_path.read_text(encoding="utf-8")
            except FileNotFoundError:
                code_text = ""

            # Determine next index n for generated_cc_n.py
            max_idx = 0
            for p in final_gen_dir.glob("generated_cc_*.py"):
                m = re.match(r"generated_cc_(\d+)\.py", p.name)
                if m:
                    idx = int(m.group(1))
                    if idx > max_idx:
                        max_idx = idx
            next_idx = max_idx + 1
            new_path = final_gen_dir / f"generated_cc_{next_idx}.py"

            # Ask LLM for a final technical report of the protocol
            report_prompt = get_final_report_prompt()
            state["messages"].append(HumanMessage(content=report_prompt))
            llm_response = str(self.llm.invoke(state["messages"]).content)
            # Add the LLM response as a comment at the beginning of the generated file
            if code_text:
                # Remove code within <execute>...</execute> or '''python''' blocks from the LLM response
                cleaned_llm_response = re.sub(r"<execute>.*?</execute>", "", llm_response, flags=re.DOTALL)
                cleaned_llm_response = re.sub(r"```python.*?```", "", cleaned_llm_response, flags=re.DOTALL)
                
                # Ensure there are no ''' characters in the cleaned LLM response
                cleaned_llm_response = cleaned_llm_response.replace("'''", "")

                llm_comment = f"''' LLM Response:\n{cleaned_llm_response}\n'''\n\n"
                final_gen_dir.mkdir(parents=True, exist_ok=True)
                new_path.write_text(llm_comment + code_text, encoding="utf-8")
        
            state["messages"].append(AIMessage(content=llm_response))
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




