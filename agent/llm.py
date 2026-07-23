from langchain.chat_models import init_chat_model
import os
import json
from typing import Any, List, Optional
from langchain_openai import AzureChatOpenAI, ChatOpenAI


def get_zotgpt_llm():
    llm = AzureChatOpenAI(
        azure_endpoint="https://azureapi.zotgpt.uci.edu/",
        openai_api_version="2024-02-01",
        azure_deployment="gpt-4o",
        openai_api_key=os.getenv("ZOTGPT_API_KEY", "") #,  # supply your api-key via env variable
        # temperature=1 # TODO: make this configurable
    )
    return llm


def get_gemini_llm():
    gemini_api_key = os.getenv("GOOGLE_API_KEY", "default_api_key")
    project_id = os.getenv("GOOGLE_CLOUD_PROJECT", "default_project_id")
    return init_chat_model("gemini-2.5-pro", model_provider="google_genai")


def get_copilot_llm():
    """GitHub Copilot via a LiteLLM OpenAI-compatible proxy.

    Point this at a running LiteLLM proxy that exposes a `github_copilot/*`
    model. Configure via env vars:
      - COPILOT_BASE_URL : proxy base URL (default http://localhost:4000/v1)
      - COPILOT_MODEL    : model name the proxy exposes (default copilot-gpt-4o)
      - COPILOT_API_KEY  : proxy master key, if any (default sk-litellm)
    """
    base_url = os.getenv("COPILOT_BASE_URL", "http://localhost:4000/v1")
    model = os.getenv("COPILOT_MODEL", "copilot-gpt-4o")
    api_key = os.getenv("COPILOT_API_KEY", "sk-litellm")
    return ChatOpenAI(
        base_url=base_url,
        api_key=api_key,
        model=model,
        temperature=0.7,
    )


def get_llm(type: str = "gemini"):
    if type == "gemini":
        return get_gemini_llm()
    elif type == "zotgpt":
        return get_zotgpt_llm()
    elif type == "copilot":
        return get_copilot_llm()
    elif type == "copilot-cli":
        return get_copilot_cli_llm()
    else:
        raise ValueError(f"Invalid LLM type: {type}")


# ---------------------------------------------------------------------------
# Copilot CLI agentic backend
# ---------------------------------------------------------------------------
# Each .invoke() sends the full conversation to a bridge HTTP server that runs
# one GitHub Copilot CLI agentic loop and returns its final text response. The
# bridge typically runs on the user's laptop (where Copilot is authenticated)
# and is reached over a reverse SSH tunnel, so no Copilot token is needed on
# this machine. Configure via env:
#   COPILOT_CLI_BRIDGE_URL : bridge endpoint (default http://localhost:8100/invoke)
#   COPILOT_CLI_TIMEOUT    : per-call timeout in seconds (default 600)

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult


def _messages_to_prompt(messages: List[BaseMessage]) -> str:
    """Flatten a LangChain message list into a single role-tagged prompt."""
    parts: List[str] = []
    for m in messages:
        role = getattr(m, "type", "") or m.__class__.__name__
        role_label = {
            "system": "SYSTEM",
            "human": "USER",
            "ai": "ASSISTANT",
        }.get(role, role.upper())
        content = m.content if isinstance(m.content, str) else str(m.content)
        parts.append(f"### {role_label}\n{content}")
    return "\n\n".join(parts)


class CopilotCLIChatModel(BaseChatModel):
    """LangChain chat model that delegates each call to a Copilot CLI agentic loop
    running behind an HTTP bridge."""

    bridge_url: str = "http://localhost:8100/invoke"
    timeout: int = 600

    @property
    def _llm_type(self) -> str:
        return "copilot-cli"

    def _generate(
        self,
        messages: List[BaseMessage],
        stop: Optional[List[str]] = None,
        run_manager: Any = None,
        **kwargs: Any,
    ) -> ChatResult:
        import requests

        prompt = _messages_to_prompt(messages)
        resp = requests.post(
            self.bridge_url,
            data=json.dumps({"prompt": prompt}),
            headers={"Content-Type": "application/json"},
            timeout=self.timeout,
        )
        if resp.status_code != 200:
            raise RuntimeError(
                f"Copilot CLI bridge error {resp.status_code}: {resp.text[:500]}"
            )
        content = resp.json().get("content", "")
        message = AIMessage(content=content)
        return ChatResult(generations=[ChatGeneration(message=message)])


def get_copilot_cli_llm():
    bridge_url = os.getenv("COPILOT_CLI_BRIDGE_URL", "http://localhost:8100/invoke")
    timeout = int(os.getenv("COPILOT_CLI_TIMEOUT", "600"))
    return CopilotCLIChatModel(bridge_url=bridge_url, timeout=timeout)