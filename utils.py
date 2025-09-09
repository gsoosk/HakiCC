from langchain_core.messages.base import get_msg_title_repr
from langchain_core.utils.interactive_env import is_interactive_env

class Utils:
    global_ai_printout = False

    @staticmethod
    def pretty_print(message, printout=True):
        if isinstance(message, tuple):
            m = message[0]
            if printout:
                if not Utils.global_ai_printout:
                    print(get_msg_title_repr("AI Message", bold=is_interactive_env()))
                    Utils.global_ai_printout = True
                print(m.content, end="")
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
                print(f"{title}")
        else:
            if message.type == "ai":
                Utils.global_ai_printout = False
                return
            title = get_msg_title_repr(message.type.title() + " Message", bold=is_interactive_env())
            if message.name is not None:
                title += f"\nName: {message.name}"
            title += f"\n\n{message.content}"
            if printout:
                print(f"{title}")
