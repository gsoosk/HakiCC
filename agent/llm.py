from langchain.chat_models import init_chat_model
import os
from langchain_openai import AzureChatOpenAI


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

def get_llm(type: str = "gemini"):
    if type == "gemini":
        return get_gemini_llm()
    elif type == "zotgpt":
        return get_zotgpt_llm()
    else:
        raise ValueError(f"Invalid LLM type: {type}")