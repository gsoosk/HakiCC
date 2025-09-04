from typing import Annotated, Literal

from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from IPython.display import Image, display


class State:
    messages: Annotated[list[str], add_messages]




def cc_generator(state: State) -> State:
    pass

def cc_correctness_checker(state: State) -> State:
    pass

def cc_performance_evaluator(state: State) -> State:
    pass

def cc_novelty_evaluator(state: State) -> State:
    pass

def route_correctness(state: State) -> Literal["correct", "incorrect", "end"]:
    pass


def build_workflow():
    workflow_builder = StateGraph(State)
    workflow_builder.add_node("cc_generator", cc_generator)
    workflow_builder.add_node("cc_correctness_checker", cc_correctness_checker)
    workflow_builder.add_node("cc_performance_evaluator", cc_performance_evaluator)
    workflow_builder.add_node("cc_novelty_evaluator", cc_novelty_evaluator)

    workflow_builder.add_edge(START, "cc_generator")
    workflow_builder.add_edge("cc_generator", "cc_correctness_checker")
    workflow_builder.add_conditional_edges("cc_correctness_checker", route_correctness, {
        "correct": "cc_performance_evaluator",
        "incorrect": "cc_generator",
        "end": END
    })
    workflow_builder.add_edge("cc_performance_evaluator", "cc_novelty_evaluator")
    workflow_builder.add_edge("cc_novelty_evaluator", "cc_generator")

    return workflow_builder.compile()







workflow = build_workflow()
# workflow.get_graph().print_ascii()
workflow.get_graph().draw_mermaid_png(max_retries=2)
with open("workflow_diagram.png", "wb") as f:
    f.write(workflow.get_graph().draw_mermaid_png(max_retries=2))


