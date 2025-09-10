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
    printed_messages_count = 0  # Keep track of how many messages have been printed

    # TODO: fix interface for correct streaming of messages
    for chunk in workflow.stream(init_state, stream_mode="values", config=config):
        total_messages = len(chunk["messages"])
        if total_messages > printed_messages_count:
            # Print only the messages that haven't been printed yet
            for message in chunk["messages"][printed_messages_count:]:
                Utils.pretty_print(message)
            printed_messages_count = total_messages  # Update the count of printed messages
        # if stream_mode == "messages":
        #     Utils.pretty_print(chunk)



stream_workflow_updates(workflow)
