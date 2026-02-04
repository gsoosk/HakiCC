from typing import Annotated, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages
from langgraph.checkpoint.memory import MemorySaver

from agent.llm import get_llm


class ChatState(TypedDict):
    messages: list[BaseMessage]
    ended: bool


def build_graph():
    llm = get_llm()
    memory = MemorySaver()

    def input_node(state: ChatState) -> dict:
        try:
            text_str = input("You: ").strip()
        except (KeyboardInterrupt, EOFError):
            text_str = "end"
        if text_str.lower() == "end":
            return {"ended": True}
        if text_str:
            return {"messages": [HumanMessage(content=text_str)], "ended": False}
        return {"ended": False}

    def route_from_input(state: ChatState) -> str:
        ended = bool(state.get("ended", False))
        return "end" if ended else "continue"

    def call_model(state: ChatState) -> dict:
        response = llm.invoke(state["messages"])  # type: ignore[arg-type]
        content = getattr(response, "content", "")
        if content:
            print(f"Assistant: {content}")
        state["messages"] = [response]
        return state

    workflow = StateGraph(ChatState)
    workflow.add_node("input", input_node)
    workflow.add_node("model", call_model)
    workflow.add_edge(START, "input")
    workflow.add_conditional_edges(
        "input",
        route_from_input,
        {"continue": "model", "end": END},
    )
    workflow.add_edge("model", "input")
    return workflow.compile(checkpointer=memory)


def chat_once() -> None:
    graph = build_graph()
    graph.invoke({}, config={"configurable": {"thread_id": "single-run"}})


if __name__ == "__main__":
    graph = build_graph()
    print("Simple LangGraph chat. Type 'end' to finish. Press Ctrl+C to exit.")
    session_id = "cli-session"
    graph.invoke({}, config={"configurable": {"thread_id": session_id}})
    print("Conversation ended.")



