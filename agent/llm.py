from langchain.chat_models import init_chat_model
import os

def get_llm():
    # return init_chat_model(model="gpt-4o-mini")
    gemini_api_key = os.getenv("GOOGLE_API_KEY", "default_api_key")
    project_id = os.getenv("GOOGLE_CLOUD_PROJECT", "default_project_id")
    return init_chat_model("gemini-2.5-pro", model_provider="google_genai")