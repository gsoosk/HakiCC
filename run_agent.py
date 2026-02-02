from agent.agent import Agent
from langgraph.graph import StateGraph
from agent.agent import State
from utils import Utils
import argparse
from rich import print as rich_print
from rich.markdown import Markdown
from langchain_core.messages import AIMessage, HumanMessage

def get_workflow(inspiration_pdf: str | None = None, language_mode: str = "java", target_cc_count: int = 1) -> StateGraph:
    agent = Agent(inspiration_pdf_path=inspiration_pdf, language_mode=language_mode, target_cc_count=target_cc_count)
    workflow = agent.get_workflow()
    workflow.get_graph().draw_mermaid_png(max_retries=2)
    with open("workflow_diagram.png", "wb") as f:
        f.write(workflow.get_graph().draw_mermaid_png(max_retries=2))
    return workflow


def format_content_for_markdown(content: str, language: str = "java") -> str:
    # Match console pretty-printer: render special tags as fenced blocks
    code_lang = "java" if language == "java" else "python"
    return (
        content.replace("<execute>", f"```{code_lang}")
        .replace("</execute>", "```")
        .replace("<observe>", "```json")
        .replace("</observe>", "```")
        .replace("<transactions>", f"```java")
        .replace("</transactions>", "```")
    )



def stream_workflow_updates(workflow: StateGraph, target_cc_count: int = 1):    
    init_state = State({
        "messages": [], 
        "next_step": None,
        "generated_cc_count": 0,
        "target_cc_count": target_cc_count
    })
    config = {"recursion_limit": 5000, "configurable": {"thread_id": 1}}
    printed_messages_count = 0  # Keep track of how many messages have been printed

    # Stream workflow updates and print only new messages that haven't been printed yet.
    previous_messages = []
    for chunk in workflow.stream(init_state, stream_mode="values", config=config):
        current_messages = chunk.get("messages", [])
        # Only print messages that are new compared to the previous_messages list
        new_messages = current_messages[len(previous_messages):]


        for message in chunk["messages"]:
            if isinstance(message, AIMessage):
                rich_print(("\n--------------------------------------------------------\n-------------------------------- Assistant --------------------------------\n--------------------------------------------------------\n"))
                rich_print(Markdown(format_content_for_markdown(message.content)))
            elif isinstance(message, HumanMessage):
                rich_print(("\n--------------------------------------------------------\n-------------------------------- User --------------------------------\n--------------------------------------------------------\n"))
                rich_print(Markdown(format_content_for_markdown(message.content)))
        previous_messages = list(current_messages)



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--inspiration-pdf", dest="inspiration_pdf", type=str, default=None, help="Path to a PDF to inspire the proposal prompt")
    parser.add_argument("--language", dest="language", type=str, default="java", choices=["python", "java"], help="Target language for code generation (python or java)")
    parser.add_argument("--target-cc-count", dest="target_cc_count", type=int, default=1, help="Number of final CC protocols to generate before stopping")
    args = parser.parse_args()
    print(args)
    workflow = get_workflow(inspiration_pdf=args.inspiration_pdf, language_mode=args.language, target_cc_count=args.target_cc_count)
    stream_workflow_updates(workflow, target_cc_count=args.target_cc_count)
