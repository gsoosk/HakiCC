#!/usr/bin/env python3
"""Copilot CLI bridge server (laptop side).

Exposes an HTTP endpoint that runs each request as a GitHub Copilot CLI
agentic loop and returns only the final text response. Intended to be reached
from a remote machine (e.g. a Chameleon node) via a reverse SSH tunnel, so the
Copilot subscription/token never leaves this laptop.

Flow per request:
  1. Receive POST /invoke  {"prompt": "<full prompt text>"}
  2. Write the prompt to prompt.txt inside a fresh isolated temp working dir.
  3. Run:  copilot -C <dir> -p "<read prompt.txt and respond>" -s \
             --allow-all-tools --no-ask-user --add-dir <dir>
     The CLI reads the file with its own tools (the agentic loop) and prints
     only its final answer (-s).
  4. Return {"content": "<agent text>"}.

No external dependencies (stdlib only) so it runs on Windows ARM64 without
compiling anything.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


COPILOT_BIN = os.environ.get("COPILOT_BIN", "copilot")
COPILOT_MODEL = os.environ.get("COPILOT_MODEL")  # optional, e.g. "gpt-5.4"
CALL_TIMEOUT = int(os.environ.get("COPILOT_BRIDGE_TIMEOUT", "600"))

READ_INSTRUCTION = (
    "Read the file prompt.txt in the current working directory and fully "
    "carry out the request it contains. Output ONLY your final response with "
    "no preamble, commentary, or restating of the task. If the request asks "
    "for code inside tags such as <execute>...</execute>, reproduce those tags "
    "exactly in your output."
)


def run_copilot(prompt: str) -> tuple[str, str, int]:
    """Run one Copilot CLI agentic loop for the given prompt. Returns (stdout, stderr, rc)."""
    work = os.path.join(tempfile.gettempdir(), f"copilot-bridge-{uuid.uuid4().hex}")
    os.makedirs(work, exist_ok=True)
    try:
        with open(os.path.join(work, "prompt.txt"), "w", encoding="utf-8") as f:
            f.write(prompt)

        cmd = [
            COPILOT_BIN,
            "-C", work,
            "-p", READ_INSTRUCTION,
            "-s",
            "--allow-all-tools",
            "--no-ask-user",
            "--add-dir", work,
        ]
        if COPILOT_MODEL:
            cmd += ["--model", COPILOT_MODEL]

        env = dict(os.environ)
        env["COPILOT_ALLOW_ALL"] = "1"
        proc = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=CALL_TIMEOUT,
            env=env,
            cwd=work,
        )
        return proc.stdout.strip(), proc.stderr.strip(), proc.returncode
    finally:
        shutil.rmtree(work, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        if self.path == "/health":
            self._send(200, {"status": "ok"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path != "/invoke":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length) if length else b"{}"
            data = json.loads(raw.decode("utf-8"))
            prompt = data.get("prompt", "")
            if not prompt:
                self._send(400, {"error": "missing 'prompt'"})
                return
            print(f"[bridge] invoke: {len(prompt)} chars", flush=True)
            stdout, stderr, rc = run_copilot(prompt)
            if rc != 0:
                print(f"[bridge] copilot rc={rc} stderr={stderr[:400]}", flush=True)
                self._send(502, {"error": f"copilot exited {rc}", "stderr": stderr, "content": stdout})
                return
            print(f"[bridge] ok: {len(stdout)} chars", flush=True)
            self._send(200, {"content": stdout})
        except subprocess.TimeoutExpired:
            self._send(504, {"error": "copilot timed out"})
        except Exception as exc:  # noqa: BLE001
            self._send(500, {"error": str(exc)})

    def log_message(self, *args):  # silence default noisy logging
        pass


def main():
    parser = argparse.ArgumentParser(description="Copilot CLI bridge server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8100)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"[bridge] listening on http://{args.host}:{args.port}  (copilot bin: {COPILOT_BIN})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[bridge] shutting down", flush=True)
        server.shutdown()


if __name__ == "__main__":
    sys.exit(main())
