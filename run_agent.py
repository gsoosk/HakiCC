from agent.agent import Agent
from langgraph.graph import StateGraph
from agent.agent import State
from utils import Utils

agent = Agent()
workflow = agent.get_workflow()
# workflow.get_graph().print_ascii()
workflow.get_graph().draw_mermaid_png(max_retries=2)
with open("workflow_diagram.png", "wb") as f:
    f.write(workflow.get_graph().draw_mermaid_png(max_retries=2))





def stream_workflow_updates(workflow: StateGraph):    
    init_state = State({"messages": [], "next_step": None})
    config = {"recursion_limit": 500, "configurable": {"thread_id": 1}}
    # TODO: fix interface for correct streaming of messages
    for stream_mode, chunk in workflow.stream(init_state, stream_mode=["values", "messages"], config=config):
        # if stream_mode == "values" and len(chunk["messages"]) > 0:
        #     Utils.pretty_print(chunk["messages"][-1])
        if stream_mode == "messages":
            Utils.pretty_print(chunk)



stream_workflow_updates(workflow)
