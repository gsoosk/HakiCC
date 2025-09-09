from langchain.chat_models import init_chat_model
import os

def get_llm():
    # return init_chat_model(model="gpt-4o-mini")
    gemini_api_key = "AIzaSyBGYxEjDTdKfi15PtIyimpWJ-2wlYsxOow"
    project_id = "gen-lang-client-0385870402"  # Provide your project ID here
    os.environ["GOOGLE_API_KEY"] = gemini_api_key
    os.environ["GOOGLE_CLOUD_PROJECT"] = project_id
    return init_chat_model("gemini-2.5-flash", model_provider="google_genai")