"""Deterministic Stage-1 verification: the EXISTING Java benchmark, unchanged.

This is HakiCC's correctness gate -- ``tests_java/run_evaluator.sh
--correctness-only --pre-known-txn {tpcc|auction}`` -- which builds the
precedence graph and checks conflict-serializability. Burr calls this directly
(no LLM); it is the analogue of HakiCC's ``cc_correctness_checker``.

Transport:
  * mode="ssh"   -> scp GeneratedCC.java to the Chameleon node, run the script
                    there (high concurrency), read back output.json. Copilot runs
                    on the laptop; the benchmark runs on the node.
  * mode="local" -> run the script on this machine (requires a local JDK).

Returns a report dict consumed by actions.verify / the generator's repair prompt:
  {passed: bool, correctness: float, feedback: str, metrics: dict}
"""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

from .config import Config


def _is_mock() -> bool:
    return os.environ.get("HAKI_MOCK") == "1"


def _mock_verdict(cfg: Config) -> dict:
    """Offline verify: fail HAKI_MOCK_FAIL times (tracked via a counter file), then
    pass -- lets `check` exercise the repair loop deterministically without a JDK."""
    want_fail = int(os.environ.get("HAKI_MOCK_FAIL", "0") or "0")
    ctr = cfg.pipeline_path / ".mock_verify_count"
    seen = 0
    try:
        seen = int(ctr.read_text())
    except (OSError, ValueError):
        seen = 0
    if seen < want_fail:
        ctr.write_text(str(seen + 1))
        return _fail(f"mock verify failure {seen + 1}/{want_fail}")
    ctr.write_text("0")
    return {"passed": True, "correctness": 1.0, "feedback": "mock pass",
            "metrics": {"correctness": 1.0}}


def _run(cmd: list[str], timeout: float) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=timeout)


def _diagnose(result: dict, correctness: float, log_tail: str) -> str:
    """Categorize a failing verification into the terms the repair prompt expects
    (serializability / progress / freedom from stalls), so the generator gets a
    sharp, actionable signal instead of only the raw JSON."""
    scenarios = ((result.get("artifacts") or {}).get("scenario_summaries") or {})
    non_serializable, stalled = [], []
    for name, s in scenarios.items():
        if not isinstance(s, dict):
            continue
        if s.get("is_serializable") is False:
            non_serializable.append(name)
        if (s.get("stalled_threads") or 0) > 0:
            stalled.append(f"{name}({s.get('stalled_threads')})")

    lines = [f"DIAGNOSIS (correctness={correctness:.3f}, need 1.000):"]
    if non_serializable:
        lines.append(
            "- SERIALIZABILITY VIOLATION: the precedence graph has a cycle in "
            f"scenario(s): {', '.join(non_serializable)}. The commit order these "
            "scenarios produced is not conflict-serializable — tighten conflict "
            "detection / ordering for the keys those transactions touch."
        )
    if stalled:
        lines.append(
            "- PROGRESS / STALLS: threads left stalled in scenario(s): "
            f"{', '.join(stalled)}. Suspect deadlock, livelock, or a lock never "
            "released — ensure forward progress and freedom from stalls."
        )
    lo = (log_tail or "").lower()
    if "compilation failed" in lo or "error:" in lo:
        lines.append("- COMPILE/RUNTIME ERROR: the benchmark log reports a build/run "
                     "failure (see run.log tail below); fix it first.")
    if "timed out" in lo or "timeout" in lo:
        lines.append("- TIMEOUT: the evaluator was killed for running too long — "
                     "likely an infinite loop, livelock, or unbounded blocking.")
    if len(lines) == 1:
        lines.append("- Correctness below 1.0 but no per-scenario flag was parsed; "
                     "inspect the metrics/artifacts below.")
    return "\n".join(lines)


def _fail(feedback: str) -> dict:
    return {"passed": False, "correctness": 0.0, "feedback": feedback, "metrics": {}}


def _interpret(output_json_text: str, stdout: str, stderr: str, rc: int) -> dict:
    """Map evaluator output to a report dict (mirrors _check_java_correctness).

    On failure the feedback is a categorized DIAGNOSIS followed by the full eval
    JSON (HakiCC feeds the whole JSON back to the fixer; we prepend the diagnosis)."""
    if not output_json_text:
        return _fail(f"No output.json generated.\nstdout:\n{stdout}\nstderr:\n{stderr}")
    try:
        result = json.loads(output_json_text)
    except ValueError:
        return _fail(f"Could not parse output.json.\nraw:\n{output_json_text}\nstderr:\n{stderr}")
    metrics = result.get("metrics", {}) or {}
    correctness = float(metrics.get("correctness", 0.0) or 0.0)
    passed = correctness >= 1.0
    if passed:
        return {"passed": True, "correctness": correctness,
                "feedback": json.dumps(result, indent=2), "metrics": metrics}
    diagnosis = _diagnose(result, correctness, stdout)
    feedback = f"{diagnosis}\n\nFull evaluator report:\n{json.dumps(result, indent=2)}"
    return {"passed": False, "correctness": correctness,
            "feedback": feedback, "metrics": metrics}


def verify_ssh(cfg: Config) -> dict:
    v = cfg.verify
    local_java = cfg.generated_java_path
    if not local_java.exists():
        return _fail("GeneratedCC.java not found; generator must write it first.")

    host = v.ssh_host
    repo = v.remote_repo.rstrip("/")
    remote_java = f"{repo}/tests_java/GeneratedCC.java"

    # 1) ship the candidate implementation to the node
    scp = _run(["scp", "-q", str(local_java), f"{host}:{remote_java}"], timeout=120)
    if scp.returncode != 0:
        return _fail(f"scp of GeneratedCC.java to {host} failed.\n{scp.stderr}")

    # 2) run the UNCHANGED benchmark AND read back output.json in ONE ssh session
    #    (a separate `cat` ssh is fragile: the node is saturated by the 64-thread
    #    benchmark and a fresh connection can stall). Markers delimit the payload.
    j = "<<<HAKI_OUTPUT_JSON>>>"
    lg = "<<<HAKI_RUNLOG_TAIL>>>"
    remote_cmd = (
        f"cd {repo}/tests_java && rm -f output.json && "
        f"( bash run_evaluator.sh --correctness-only --pre-known-txn {cfg.pre_known_txn} "
        f"GeneratedCC.java output.json > run.log 2>&1 ; true ) ; "
        f"echo '{j}' ; cat output.json 2>/dev/null ; echo ; "
        f"echo '{lg}' ; tail -n 25 run.log 2>/dev/null"
    )
    run = _run(["ssh", host, remote_cmd], timeout=v.timeout)

    stdout = run.stdout or ""
    output_json_text, log_tail = "", ""
    if j in stdout:
        rest = stdout.split(j, 1)[1]
        if lg in rest:
            output_json_text, log_tail = rest.split(lg, 1)
        else:
            output_json_text = rest
    output_json_text = output_json_text.strip()
    log_tail = (log_tail or run.stderr or "").strip()

    if not output_json_text:
        return _fail(
            f"Java benchmark produced no output.json on {host} (rc={run.returncode}).\n"
            f"run.log tail:\n{log_tail}\nstderr:\n{run.stderr}"
        )
    return _interpret(output_json_text, log_tail, run.stderr, run.returncode)


def verify_local(cfg: Config) -> dict:
    local_java = cfg.generated_java_path
    if not local_java.exists():
        return _fail("GeneratedCC.java not found; generator must write it first.")
    tests_java = cfg.root / "tests_java"
    out = tests_java / "output.json"
    try:
        out.unlink()
    except OSError:
        pass
    run = _run(
        ["bash", "run_evaluator.sh", "--correctness-only",
         "--pre-known-txn", cfg.pre_known_txn, "GeneratedCC.java", "output.json"],
        timeout=cfg.verify.timeout,
    )
    # run_evaluator.sh chdirs to its own dir; run it from tests_java via cwd
    output_json_text = out.read_text(encoding="utf-8") if out.exists() else ""
    if run.returncode != 0 and not output_json_text:
        return _fail(f"Java benchmark failed locally (rc={run.returncode}).\n"
                     f"stdout:\n{run.stdout}\nstderr:\n{run.stderr}")
    return _interpret(output_json_text, run.stdout, run.stderr, run.returncode)


def run_verification(cfg: Config) -> dict:
    if _is_mock():
        return _mock_verdict(cfg)
    if cfg.verify.mode == "local":
        # ensure the script runs from tests_java
        import os
        cwd = os.getcwd()
        try:
            os.chdir(cfg.root / "tests_java")
            return verify_local(cfg)
        finally:
            os.chdir(cwd)
    return verify_ssh(cfg)
