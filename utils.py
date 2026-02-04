from langchain_core.messages.base import get_msg_title_repr
from langchain_core.utils.interactive_env import is_interactive_env
from rich import print as rich_print
from rich.markdown import Markdown
from typing import Optional


from pypdf import PdfReader


class Utils:
    global_ai_printout = False

    @staticmethod
    def pretty_print(message, printout=True):
        def format_content(content):
            return (content.replace("<execute>", "```python")
                           .replace("</execute>", "```")
                           .replace("<observe>", "```")
                           .replace("</observe>", "```"))

        if isinstance(message, tuple):
            m = message[0]
            if printout:
                if not Utils.global_ai_printout:
                    rich_print(Markdown(get_msg_title_repr("AI Message", bold=is_interactive_env())))
                    Utils.global_ai_printout = True
                rich_print(Markdown(format_content(m.content)), end="")
        elif isinstance(message.content, list):
            title = get_msg_title_repr(message.type.title().upper() + " Message", bold=is_interactive_env())
            if message.name is not None:
                title += f"\nName: {message.name}"

            for i in message.content:
                if i["type"] == "text":
                    title += f"\n{i['text']}\n"
                elif i["type"] == "tool_use":
                    title += f"\nTool: {i['name']}"
                    title += f"\nInput: {i['input']}"
            if printout:
                rich_print(Markdown(format_content(title)))

    @staticmethod
    def extract_text_from_pdf_path(pdf_path: str, max_chars: int = 8000) -> str:
        if PdfReader is None:
            return ""
        try:
            reader = PdfReader(pdf_path)
            parts: list[str] = []
            for page in reader.pages:
                text = page.extract_text() or ""
                if text:
                    parts.append(text)
                if sum(len(p) for p in parts) >= max_chars:
                    break
            combined = "\n\n".join(parts)
            return combined[:max_chars]
        except Exception:
            return ""

    @staticmethod
    def extract_text_from_pdf_bytes(data: bytes, max_chars: int = 8000) -> str:
        if PdfReader is None:
            return ""
        try:
            import io as _io
            reader = PdfReader(_io.BytesIO(data))
            parts: list[str] = []
            for page in reader.pages:
                text = page.extract_text() or ""
                if text:
                    parts.append(text)
                if sum(len(p) for p in parts) >= max_chars:
                    break
            combined = "\n\n".join(parts)
            return combined[:max_chars]
        except Exception:
            return ""
        else:
            # if message.type == "ai":
            #     Utils.global_ai_printout = False
            #     return
            title = get_msg_title_repr(message.type.title() + " Message", bold=is_interactive_env())
            if message.name is not None:
                title += f"\nName: {message.name}"
            title += f"\n\n{message.content}"
            if printout:
                rich_print(Markdown(format_content(title)))
