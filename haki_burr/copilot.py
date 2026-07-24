"""invoke_agent(): the boundary between the deterministic Burr orchestrator and
the GitHub Copilot CLI agent runtime.

Every Burr action that needs LLM work shells out to ``copilot -p ... --agent NAME``.
Copilot owns the entire agent loop (reasoning, tools, file edits); this wrapper
only launches it, captures the JSONL output, and returns a result. Inter-stage
STATE is passed via files in the pipeline dir (see actions.py), not by parsing
agent chatter -- the parsed output is used only for success/failure detection and
telemetry.

Adapted from the CodeWeaver project (same CLI contract, verified against GitHub
Copilot CLI 1.0.7x): -p/--prompt, --agent, --model, --reasoning-effort, --allow-all,
--no-ask-user, --output-format json (JSONL), --add-dir, --log-dir. Custom agents
are discovered from ~/.copilot/agents/; ensure_agents_installed() mirrors
<repo>/agents/*.agent.md there before each run.
"""
from __future__ import annotations

import json
import os
import platform
import shutil
import subprocess
import time
from dataclasses import dataclass, field
from pathlib import Path

MOCK = os.environ.get("HAKI_MOCK") == "1"


def is_mock() -> bool:
    return MOCK or os.environ.get("HAKI_MOCK") == "1"


AGENTS_SRC = Path(
    os.environ.get(
        "HAKI_AGENTS_DIR",
        str(Path(__file__).resolve().parent.parent / "agents"),
    )
)


def _resolve_model(explicit: str | None, cfg=None) -> str | None:
    if explicit:
        return explicit
    env = os.environ.get("HAKI_MODEL")
    if env:
        return env
    if cfg is not None:
        return cfg.model.default
    return None


def _resolve_effort(agent_name: str, explicit: str | None, cfg=None) -> str | None:
    if explicit:
        return explicit
    env = os.environ.get("HAKI_EFFORT")
    if env:
        return env
    if cfg is not None:
        return cfg.model.effort_for(agent_name)
    return None


def ensure_agents_installed() -> list[str]:
    """Copy agents/*.agent.md into the Copilot user-level agents dir so
    ``--agent NAME`` resolves. Idempotent; returns installed profile names."""
    dest = Path(os.environ.get("COPILOT_HOME", Path.home() / ".copilot")) / "agents"
    dest.mkdir(parents=True, exist_ok=True)
    installed = []
    if AGENTS_SRC.exists():
        for src in sorted(AGENTS_SRC.glob("*.agent.md")):
            (dest / src.name).write_text(src.read_text(encoding="utf-8"), encoding="utf-8")
            installed.append(src.stem.replace(".agent", ""))
    return installed


def _truncate(s: str, limit: int) -> str:
    s = s or ""
    return s if len(s) <= limit else s[:limit] + f"\n…[truncated {len(s) - limit} chars]"


def summary_from_events(events: list) -> dict:
    out = {"exit_code": None, "files_modified": [], "lines_added": 0,
           "lines_removed": 0, "premium_requests": None, "duration_s": None}
    for ev in events:
        if isinstance(ev, dict) and ev.get("type") == "result":
            u = ev.get("usage", {}) or {}
            cc = u.get("codeChanges", {}) or {}
            out.update(
                exit_code=ev.get("exitCode"),
                files_modified=cc.get("filesModified") or [],
                lines_added=cc.get("linesAdded", 0),
                lines_removed=cc.get("linesRemoved", 0),
                premium_requests=u.get("premiumRequests"),
                duration_s=round((u.get("sessionDurationMs") or 0) / 1000, 1),
            )
    return out


def _git_bash_dirs() -> list[str]:
    """On Windows, prepend Git-for-Windows bin dirs so `bash`/`ssh` in agent
    shells resolve to Git tooling. Empty on non-Windows."""
    if platform.system() != "Windows":
        return []
    roots: list[Path] = []
    git = shutil.which("git")
    if git:
        roots.append(Path(git).resolve().parent.parent)
    roots.append(Path(r"C:\Program Files\Git"))
    roots.append(Path(r"C:\Program Files (x86)\Git"))
    dirs: list[str] = []
    for root in roots:
        for sub in ("bin", os.path.join("usr", "bin")):
            d = root / sub
            if (d / "bash.exe").exists() and str(d) not in dirs:
                dirs.append(str(d))
    return dirs


@dataclass
class AgentResult:
    agent: str
    ok: bool
    returncode: int
    final_text: str
    duration_s: float
    stdout_path: str | None = None
    events: list = field(default_factory=list)


def _mock(agent_name: str, prompt: str) -> AgentResult:
    from .mock import respond
    text = respond(agent_name, prompt)
    return AgentResult(agent=agent_name, ok=True, returncode=0,
                       final_text=text, duration_s=0.0, events=[])


def _parse_jsonl(raw: str) -> tuple[list, str]:
    events: list = []
    final = ""
    for line in (raw or "").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
        except ValueError:
            continue
        events.append(obj)
        if not isinstance(obj, dict):
            continue
        etype = obj.get("type")
        data = obj.get("data") if isinstance(obj.get("data"), dict) else {}
        if etype == "assistant.message":
            content = data.get("content")
            if isinstance(content, str) and content.strip():
                final = content
        elif etype is None:
            for key in ("result", "text", "content", "message"):
                val = obj.get(key)
                if isinstance(val, str) and val.strip():
                    final = val
    return events, final


def invoke_agent(
    agent_name: str,
    prompt: str,
    *,
    cwd: str | os.PathLike,
    model: str | None = None,
    effort: str | None = None,
    add_dirs: list[str] | None = None,
    timeout: float | None = None,
    log_dir: str | os.PathLike | None = None,
    extra_env: dict | None = None,
    cfg=None,
) -> AgentResult:
    """Run one Copilot custom agent non-interactively to completion."""
    if is_mock():
        return _mock(agent_name, prompt)

    if timeout is None and cfg is not None and cfg.agent_timeout:
        timeout = float(cfg.agent_timeout)

    ensure_agents_installed()
    model = _resolve_model(model, cfg)
    effort = _resolve_effort(agent_name, effort, cfg)
    cwd = str(cwd)

    # Large prompts (e.g. the proposer's, which embeds transaction source) exceed
    # Windows' ~32KB command-line limit for an inline -p. Write the full prompt to
    # a file and pass a short pointer telling the agent to read+execute it. This is
    # robust for any prompt size on any platform.
    prompt_file = None
    if log_dir:
        Path(log_dir).mkdir(parents=True, exist_ok=True)
        prompt_file = Path(log_dir) / f"{agent_name}.prompt.md"
        try:
            prompt_file.write_text(prompt, encoding="utf-8")
        except OSError:
            prompt_file = None
    if prompt_file is not None:
        p_arg = (
            f"Your complete task is written in the file `{prompt_file}`. "
            "Read that entire file first, then carry out its instructions fully and "
            "autonomously. Do not ask for confirmation."
        )
    else:
        p_arg = prompt

    cmd = ["copilot", "-C", cwd, "-p", p_arg, "--agent", agent_name]
    if model:
        cmd += ["--model", model]
    if effort:
        cmd += ["--reasoning-effort", effort]
    cmd += ["--allow-all", "--no-ask-user", "--output-format", "json", "--no-color"]
    for d in (add_dirs or []):
        cmd += ["--add-dir", str(d)]
    if log_dir:
        cmd += ["--log-dir", str(log_dir)]

    env = dict(os.environ)
    gb = _git_bash_dirs()
    if gb:
        env["PATH"] = os.pathsep.join(gb) + os.pathsep + env.get("PATH", "")
    if extra_env:
        env.update({k: str(v) for k, v in extra_env.items()})

    t0 = time.monotonic()
    try:
        proc = subprocess.run(
            cmd, cwd=cwd, env=env, capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=timeout,
        )
        returncode, stdout, stderr = proc.returncode, proc.stdout or "", proc.stderr or ""
    except subprocess.TimeoutExpired as e:
        stdout = (e.stdout.decode("utf-8", "replace") if isinstance(e.stdout, bytes) else (e.stdout or ""))
        stderr = (e.stderr.decode("utf-8", "replace") if isinstance(e.stderr, bytes) else (e.stderr or "")) \
            + f"\n[haki_burr] agent '{agent_name}' timed out after {timeout}s"
        returncode = 124
    except OSError as e:
        stdout, stderr, returncode = "", f"[haki_burr] failed to launch copilot: {e!r}", 1
    dt = time.monotonic() - t0

    events, final = _parse_jsonl(stdout)
    if not final:
        final = (stdout or stderr or "").strip()

    stdout_path = None
    if log_dir:
        Path(log_dir).mkdir(parents=True, exist_ok=True)
        stdout_path = str(Path(log_dir) / f"{agent_name}.stdout.jsonl")
        try:
            Path(stdout_path).write_text(stdout, encoding="utf-8")
        except OSError:
            stdout_path = None

    return AgentResult(agent=agent_name, ok=(returncode == 0), returncode=returncode,
                       final_text=final, duration_s=dt, stdout_path=stdout_path, events=events)


def verify_cli() -> dict:
    try:
        out = subprocess.run(["copilot", "--version"], capture_output=True, text=True, timeout=30)
        return {"ok": out.returncode == 0, "version": out.stdout.strip()}
    except (OSError, subprocess.SubprocessError) as e:
        return {"ok": False, "error": repr(e)}
