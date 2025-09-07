from agent.agent import Agent
from langgraph.graph import StateGraph
from agent.agent import State
from utils import pretty_print


agent = Agent()
workflow = agent.get_workflow()
# workflow.get_graph().print_ascii()
workflow.get_graph().draw_mermaid_png(max_retries=2)
with open("workflow_diagram.png", "wb") as f:
    f.write(workflow.get_graph().draw_mermaid_png(max_retries=2))





def stream_workflow_updates(workflow: StateGraph):    
    init_state = State({"messages": [], "next_step": None})
    config = {"recursion_limit": 500, "configurable": {"thread_id": 1}}
    for value in workflow.stream(init_state, stream_mode="values", config=config):
        if len(value["messages"]) > 0:
            pretty_print(value["messages"][-1])


stream_workflow_updates(workflow)
