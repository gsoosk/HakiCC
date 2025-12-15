from agent.agent import Agent
from langgraph.graph import StateGraph
from agent.agent import State
from utils import Utils
import argparse

def get_workflow(inspiration_pdf: str | None = None, language_mode: str = "python") -> StateGraph:
    agent = Agent(inspiration_pdf_path=inspiration_pdf, language_mode=language_mode)
    workflow = agent.get_workflow()
    workflow.get_graph().draw_mermaid_png(max_retries=2)
    with open("workflow_diagram.png", "wb") as f:
        f.write(workflow.get_graph().draw_mermaid_png(max_retries=2))
    return workflow





def stream_workflow_updates(workflow: StateGraph):    
    init_state = State({"messages": [], "next_step": None})
    config = {"recursion_limit": 500, "configurable": {"thread_id": 1}}
    printed_messages_count = 0  # Keep track of how many messages have been printed

    # TODO: fix interface for correct streaming of messages
    for chunk in workflow.stream(init_state, stream_mode="values", config=config):
        if len(chunk["messages"]) > 0:
            # Print only the messages that haven't been printed yet
            for message in chunk["messages"]:
                Utils.pretty_print(message)
        # if stream_mode == "messages":
        #     Utils.pretty_print(chunk)



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--inspiration-pdf", dest="inspiration_pdf", type=str, default=None, help="Path to a PDF to inspire the proposal prompt")
    parser.add_argument("--language", dest="language", type=str, default="python", choices=["python", "java"], help="Target language for code generation (python or java)")
    args = parser.parse_args()
    workflow = get_workflow(inspiration_pdf=args.inspiration_pdf, language_mode=args.language)
    stream_workflow_updates(workflow)
