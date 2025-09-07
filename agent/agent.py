from typing import Annotated, Literal, TypedDict
import os
from pathlib import Path
import re

from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langchain_core.messages import HumanMessage, SystemMessage, AIMessage
from langgraph.checkpoint.memory import MemorySaver


from .llm import get_llm
from .prompts import get_generate_prompt



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


    @staticmethod
    def cc_correctness_checker(state: State) -> State:
        return state
        # Import test harness utilities
        try:
            from tests.test_serializability import (
                ConcurrentRunner,
                build_precedence_graph_fast,
                has_cycle_kahn,
                wl_counter,
                wl_bank,
            )
        except Exception as e:
            state.messages.append(f"correctness:error: import tests failed: {e}")
            return state

        # Import generated CC
        try:
            from cc_protocols.generated_cc import GeneratedCC
            cc_factory = GeneratedCC
        except Exception as e:
            state.messages.append(f"correctness:error: import GeneratedCC failed: {e}")
            return state

        try:
            # Run two workloads similar to tests
            runner = ConcurrentRunner(cc_factory, num_workers=8, duration_s=0.5)
            ops, committed, _ = runner.run(wl_counter)
            graph, evidence = build_precedence_graph_fast(ops)
            has_cycle = has_cycle_kahn(graph)
            if has_cycle:
                state.messages.append("correctness:fail:counter")
                return state

            runner = ConcurrentRunner(cc_factory, num_workers=8, duration_s=0.5)
            ops, committed, _ = runner.run(wl_bank)
            graph, evidence = build_precedence_graph_fast(ops)
            has_cycle = has_cycle_kahn(graph)
            if has_cycle:
                state.messages.append("correctness:fail:bank")
                return state

            state.messages.append("correctness:pass")
            return state
        except Exception as e:
            state.messages.append(f"correctness:error: run failed: {e}")
            return state

    @staticmethod
    def cc_performance_evaluator(state: State) -> State:
        pass

    @staticmethod
    def cc_novelty_evaluator(state: State) -> State:
        pass

    @staticmethod
    def route_correctness(state: State) -> Literal["correct", "incorrect", "end"]:
        msg = state.messages[-1] if state.messages else ""
        if "correctness:pass" in msg:
            return "correct"
        if msg.startswith("correctness:fail"):
            return "incorrect"
        return "end"


    def build_workflow(self):
        def cc_generator(state: State) -> State:

            project_root = Path(__file__).resolve().parent.parent
            proto_dir = project_root / "cc_protocols"
            generated_path = proto_dir / "generated_cc.py"

            # prompt = f"""
            #         create a hello world program in python
            #         only return the code, no other text
            #         Interact with a programming environment and receive the corresponding output within <observe></observe>. Your code should be enclosed using "<execute>" tag, for example: <execute> print("Hello World!") </execute>. IMPORTANT: You must end the code block with </execute> tag.
            # """
            prompt = get_generate_prompt()

            messages = [HumanMessage(content=prompt)] + state["messages"]
            llm_response = str(self.llm.invoke(messages).content)

            if "<execute>" in llm_response and "</execute>" not in llm_response:
                llm_response += "</execute>"

            if "</observe>" in llm_response and "<observe>" not in llm_response:
                llm_response += "</observe>"

            execute_match = re.search(r"<execute>(.*?)</execute>", llm_response, re.DOTALL)
            execute_code = execute_match.group(1) if execute_match else ""
            self.code_iteration += 1
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
                
                state["next_step"] = "cc_correctness_checker"
            else:
                # does not have <execute> tag
                state["next_step"] = "cc_generator"

            state["messages"].append(AIMessage(content=f"Iteration: {self.code_iteration}, {llm_response.strip()}"))
            return state

        


        workflow_builder = StateGraph(State)
        workflow_builder.add_node("cc_generator", cc_generator)
        # workflow_builder.add_node("cc_correctness_checker", Agent.cc_correctness_checker)
        # workflow_builder.add_node("cc_performance_evaluator", Agent.cc_performance_evaluator)
        # workflow_builder.add_node("cc_novelty_evaluator", Agent.cc_novelty_evaluator)

        workflow_builder.add_edge(START, "cc_generator")

        workflow_builder.add_conditional_edges("cc_generator", lambda state: state["next_step"], {
            "cc_correctness_checker": END,
            "cc_generator": "cc_generator"
        })
        # workflow_builder.add_edge("cc_generator", "cc_correctness_checker")
        # workflow_builder.add_conditional_edges("cc_correctness_checker", Agent.route_correctness, {
        #     "correct": "cc_performance_evaluator",
        #     "incorrect": "cc_generator",
        #     "end": END
        # })
        # workflow_builder.add_edge("cc_performance_evaluator", "cc_novelty_evaluator")
        # workflow_builder.add_edge("cc_novelty_evaluator", "cc_generator")

        # FIXME: remove after testing
        # workflow_builder.add_edge("cc_generator", END)


        return workflow_builder.compile()




