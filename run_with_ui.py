import sys
from pathlib import Path
from typing import Any

import streamlit as st
from langchain_core.messages import HumanMessage, AIMessage


# Ensure project root is on sys.path for package imports when run via Streamlit
PROJECT_ROOT = Path(__file__).resolve().parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from agent.agent import Agent, State  # noqa: E402


st.set_page_config(page_title="Agent Workflow Runner", page_icon="🤖", layout="wide")


@st.cache_resource(show_spinner=False)
def get_workflow():
    agent = Agent()
    return agent.get_workflow()


def to_role(message: Any) -> str:
    mtype = getattr(message, "type", "")
    if mtype == "human":
        return "user"
    if mtype == "ai":
        return "assistant"
    return "system"


def format_content_for_markdown(content: str) -> str:
    # Match console pretty-printer: render special tags as fenced blocks
    return (
        content.replace("<execute>", "```python")
        .replace("</execute>", "```")
        .replace("<observe>", "```")
        .replace("</observe>", "```")
    )


def get_message_text(message: Any) -> str:
    raw = getattr(message, "content", "")
    if isinstance(raw, list):
        parts: list[str] = []
        for item in raw:
            if isinstance(item, dict):
                if item.get("type") == "text":
                    parts.append(str(item.get("text", "")))
                elif item.get("type") == "tool_use":
                    parts.append(f"Tool: {item.get('name')}\nInput: {item.get('input')}")
        raw_text = "\n".join([p for p in parts if p]) or str(raw)
    else:
        raw_text = str(raw)
    return format_content_for_markdown(raw_text)


def get_message_plain_text(message: Any) -> str:
    raw = getattr(message, "content", "")
    if isinstance(raw, list):
        parts: list[str] = []
        for item in raw:
            if isinstance(item, dict):
                if item.get("type") == "text":
                    parts.append(str(item.get("text", "")))
                elif item.get("type") == "tool_use":
                    parts.append(f"Tool: {item.get('name')}\nInput: {item.get('input')}")
        return "\n".join([p for p in parts if p]) or str(raw)
    return str(raw)


def stream_run() -> None:
    workflow = get_workflow()
    init_state: State = State({"messages": [], "next_step": None})
    config = {"recursion_limit": 500, "configurable": {"thread_id": "streamlit-session"}}

    printed_messages = 0
    chat_container = st.container()

    with st.status("Running workflow…", expanded=True) as status:
        for chunk in workflow.stream(init_state, stream_mode="values", config=config):
            messages = chunk.get("messages", [])
            if not isinstance(messages, list):
                continue
            if len(messages) > printed_messages:
                new_messages = messages[printed_messages:]
                for m in new_messages:
                    role = to_role(m)
                    if role == "user":
                        with chat_container.chat_message("user"):
                            st.text(get_message_plain_text(m))
                    else:
                        with chat_container.chat_message(role):
                            st.markdown(get_message_text(m))
                printed_messages = len(messages)
        status.update(label="Workflow completed", state="complete")


st.title("Agent Workflow Runner")
st.write("Click the button to run the workflow from the initial state and stream messages.")

col1, col2 = st.columns([1, 3])
with col1:
    run_clicked = st.button("Run workflow", type="primary")
with col2:
    st.caption("Messages will appear below as Human/AI chat bubbles.")

if run_clicked:
    stream_run()


