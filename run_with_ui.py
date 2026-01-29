import sys
from pathlib import Path
from typing import Any, Callable, TypeVar
import inspect
from utils import Utils

import streamlit as st
from streamlit.runtime.scriptrunner import add_script_run_ctx, get_script_run_ctx
from streamlit.delta_generator import DeltaGenerator
from langchain_core.messages import HumanMessage, AIMessage
from langchain_core.callbacks.base import BaseCallbackHandler

# Ensure project root is on sys.path for package imports when run via Streamlit
PROJECT_ROOT = Path(__file__).resolve().parent
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from agent.agent import Agent, State  # noqa: E402

st.set_page_config(page_title="Agent Workflow Runner", page_icon="🤖", layout="wide")

st.session_state['stop_clicked'] = False  # Initialize the stop button state

@st.cache_resource(show_spinner=False)
def get_workflow(inspiration_text: str | None = None, language_mode: str = "python"):
    print(f"inspiration_text: {inspiration_text}, language_mode: {language_mode}")
    agent = Agent(inspiration_text=inspiration_text, language_mode=language_mode)
    return agent.get_workflow()

def to_role(message: Any) -> str:
    mtype = getattr(message, "type", "")
    if mtype == "human":
        return "user"
    if mtype == "ai":
        return "assistant"
    return "system"

def format_content_for_markdown(content: str, language: str = "python") -> str:
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

def get_streamlit_cb(parent_container: DeltaGenerator) -> BaseCallbackHandler:
    class StreamHandler(BaseCallbackHandler):
        def __init__(self, container: DeltaGenerator, initial_text: str = ""):
            self.parent_container = container
            self.ai_container = None
            self.token_placeholder = None
            self.text = initial_text
            self.last_update_len = len(initial_text)

        def reset_containers(self) -> None:
            print("reset_containers")
            self.ai_container = self.parent_container.chat_message("assistant")
            if self.token_placeholder is not None and self.text != "":
                self.text = format_content_for_markdown(self.text)
                self.token_placeholder.write(self.text)
            self.token_placeholder = self.ai_container.empty()
            self.text = ""
            self.last_update_len = 0

        def on_chat_model_start(self, serialized, messages, **kwargs) -> None:
            # Render the latest human message immediately before token streaming begins
            try:
                convo = messages[-1] if messages else []
                last_human = None
                human_messages = []
                for msg in reversed(convo):
                    if getattr(msg, "type", "") == "ai":
                        break
                    if getattr(msg, "type", "") == "human":
                        human_messages.append(msg)
                
                for human_msg in reversed(human_messages):
                    human_text = get_message_plain_text(human_msg)
                    h = self.parent_container.chat_message("user")
                    h.write(format_content_for_markdown(human_text))

                
                self.reset_containers()
            except Exception:
                pass

        def on_llm_new_token(self, token: str, **kwargs) -> None:
            # if self.ai_container is None:
            #     self.reset_containers()
            
            self.text += token
            if len(self.text) - self.last_update_len >= 500:
                self.text = format_content_for_markdown(self.text)
                self.token_placeholder.write(self.text)
                self.last_update_len = len(self.text)


        
    fn_return_type = TypeVar('fn_return_type')

    def add_streamlit_context(fn: Callable[..., fn_return_type]) -> Callable[..., fn_return_type]:
        ctx = get_script_run_ctx()

        def wrapper(*args, **kwargs) -> fn_return_type:
            add_script_run_ctx(ctx=ctx)
            return fn(*args, **kwargs)

        return wrapper

    st_cb = StreamHandler(parent_container)

    for method_name, method_func in inspect.getmembers(st_cb, predicate=inspect.ismethod):
        if method_name.startswith('on_'):
            setattr(st_cb, method_name, add_streamlit_context(method_func))

    return st_cb

def stream_run(inspiration_text: str | None = None, language_mode: str = "python") -> None:
    # Reset stop button state at the start of the run
    st.session_state['stop_clicked'] = False
    workflow = get_workflow(inspiration_text=inspiration_text, language_mode=language_mode)
    init_state: State = State({"messages": [], "next_step": None})
    config = {"recursion_limit": 500, "configurable": {"thread_id": "streamlit-session"}}

    printed_messages = 0
    chat_container = st.container()
    streamlit_cb = get_streamlit_cb(chat_container)
    config["callbacks"] = [streamlit_cb]

    with st.status("Running workflow…", expanded=True) as status:
        for mode, chunk in workflow.stream(init_state, stream_mode=["values", "messages"], config=config):
            if st.session_state['stop_clicked']:
                status.update(label="Workflow stopped by user", state="stopped")
                break
            if mode == "messages":
                continue
        if not st.session_state['stop_clicked']:
            status.update(label="Workflow completed", state="complete")

st.title("Concurrency Control Proposer Workflow Runner")
st.write("Click the button to run the workflow. This will generate a new concurrency control protocol.")

col1, col2 = st.columns([1, 3])
with col1:
    run_clicked = st.button("Run workflow", type="primary")
    stop_clicked = st.button("Stop workflow", type="secondary")

if stop_clicked:
    st.session_state['stop_clicked'] = True

with col2:
    st.caption("Messages will appear below as Human/AI chat bubbles.")

uploaded_pdf = st.file_uploader("Optional inspiration PDF", type=["pdf"], accept_multiple_files=False)
inspiration_text: str | None = None
if uploaded_pdf is not None:
    try:
        inspiration_text = Utils.extract_text_from_pdf_bytes(uploaded_pdf.getvalue(), max_chars=120000)
    except Exception:
        inspiration_text = None

# Language mode selector
language_mode = st.selectbox(
    "Target Language", 
    options=["java", "python"],
    index=0,
    help="Select the programming language for generated concurrency control code"
)

if run_clicked:
    stream_run(inspiration_text=inspiration_text, language_mode=language_mode)



