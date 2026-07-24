"""Burr actions: HakiCC Stage-1 stages as deterministic nodes.

Each action either (a) invokes a Copilot custom agent via invoke_agent() and reads
the artifact it wrote to the pipeline dir, or (b) does deterministic work -- the
Java benchmark (verify) or bookkeeping (restart). No LLM logic lives here; the
agents own that. This mirrors HakiCC's LangGraph nodes:

  propose   <- cc_proposer            (Copilot: proposer)
  generate  <- cc_generator           (Copilot: generator, implement|repair)
  verify    <- cc_correctness_checker  (deterministic Java benchmark, unchanged)
  novelty   <- cc_novelty_evaluator    (Copilot: novelty)
  report    <- cc_final_report         (Copilot: reporter + versioning)
  restart   <- cc_restart              (bookkeeping)
"""
from __future__ import annotations

import json
import re
from pathlib import Path

from burr.core import action

from . import config as C
from . import prompts, verify as V
from .copilot import invoke_agent, summary_from_events


def _invoke(cfg, agent: str, prompt: str):
    cfg.ensure_dirs()
    return invoke_agent(agent, prompt=prompt, cwd=cfg.root,
                        log_dir=cfg.logs_path, extra_env=cfg.extra_env(), cfg=cfg)


def _log(tracer, *, stage: str, result) -> None:
    if tracer is None:
        return
    try:
        s = summary_from_events(result.events)
        tracer.log_attributes(
            stage=stage, agent=result.agent, final_text=result.final_text or "",
            files_modified=s["files_modified"], returncode=result.returncode,
            duration_s=round(result.duration_s, 1),
            transcript_log=result.stdout_path or "",
        )
    except Exception as e:  # never let telemetry break the pipeline
        print(f"[haki_burr] warning: telemetry failed: {e}")


@action(reads=[], writes=["last_agent", "code_iteration", "verified", "report"])
def propose(state, __tracer) -> dict:
    """Proposer Agent: design a novel, workload-optimized CC protocol -> proposal.md."""
    cfg = C.active()
    prompt = prompts.render("propose", cfg)
    res = _invoke(cfg, "proposer", prompt)
    _log(__tracer, stage="propose", result=res)
    return state.update(last_agent="proposer", code_iteration=0, verified=False, report={})


@action(reads=["code_iteration", "report"], writes=["last_agent", "code_iteration"])
def generate(state, __tracer) -> dict:
    """Generator Agent: implement (or repair) the protocol -> tests_java/GeneratedCC.java."""
    cfg = C.active()
    report = state["report"] or {}
    mode = "repair" if report else "implement"
    prompt = prompts.render("generate", cfg, mode=mode, feedback=report.get("feedback", ""))
    res = _invoke(cfg, "generator", prompt)
    it = state["code_iteration"] + 1
    _log(__tracer, stage=f"generate:{mode}:{it}", result=res)
    return state.update(last_agent="generator", code_iteration=it)


@action(reads=[], writes=["verified", "report", "history", "last_agent"])
def verify(state, __tracer) -> dict:
    """Deterministic correctness gate: the UNCHANGED Java conflict-serializability
    benchmark (run on the node over SSH). Analogue of cc_correctness_checker."""
    cfg = C.active()
    result = V.run_verification(cfg)
    passed = bool(result.get("passed"))
    if __tracer is not None:
        try:
            __tracer.log_attributes(stage="verify", passed=passed,
                                    correctness=result.get("correctness"),
                                    metrics=result.get("metrics", {}))
        except Exception:
            pass
    entry = {"phase": "verify", "iter": state["code_iteration"], "verified": passed}
    return state.update(
        verified=passed,
        report={} if passed else result,   # clear on pass; feed back on failure
        last_agent="verifier",
    ).append(history=entry)


@action(reads=[], writes=["novelty_result", "last_agent"])
def novelty(state, __tracer) -> dict:
    """Novelty Agent: score originality of the verified protocol -> novelty.json."""
    cfg = C.active()
    prompt = prompts.render("novelty", cfg)
    res = _invoke(cfg, "novelty", prompt)
    _log(__tracer, stage="novelty", result=res)
    parsed = {}
    try:
        raw = cfg.novelty_path.read_text(encoding="utf-8")
        m = re.search(r"\{.*\}", raw, re.DOTALL)
        parsed = json.loads(m.group(0)) if m else {}
    except (OSError, ValueError):
        parsed = {}
    return state.update(novelty_result=parsed, last_agent="novelty")


def _next_index(final_dir: Path) -> int:
    mx = 0
    for p in final_dir.glob("generated_cc_*.java"):
        m = re.match(r"generated_cc_(\d+)\.java", p.name)
        if m:
            mx = max(mx, int(m.group(1)))
    return mx + 1


@action(
    reads=["generated_cc_count", "target_cc_count", "novelty_result"],
    writes=["generated_cc_count", "done", "last_agent"],
)
def report(state, __tracer) -> dict:
    """Reporter Agent (-> report.md) + deterministic versioning into
    final_generations/generated_cc_{n}.java. Mirrors cc_final_report."""
    cfg = C.active()
    prompt = prompts.render("report", cfg)
    res = _invoke(cfg, "reporter", prompt)
    _log(__tracer, stage="report", result=res)

    cfg.final_generations_dir.mkdir(parents=True, exist_ok=True)
    code_text = cfg.generated_java_path.read_text(encoding="utf-8") if cfg.generated_java_path.exists() else ""
    report_text = cfg.report_md_path.read_text(encoding="utf-8") if cfg.report_md_path.exists() else (res.final_text or "")
    # scrub comment terminators so the embedded report can't break the /*...*/ block
    report_text = report_text.replace("/*", "").replace("*/", "")
    novelty_text = json.dumps(state["novelty_result"], indent=2)

    if code_text:
        idx = _next_index(cfg.final_generations_dir)
        new_path = cfg.final_generations_dir / f"generated_cc_{idx}.java"
        header = (f"/* Technical Report:\n{report_text.strip()}\n*/\n\n"
                  f"/* Novelty Result:\n{novelty_text}\n*/\n\n")
        new_path.write_text(header + code_text, encoding="utf-8")

    count = state["generated_cc_count"] + 1
    target = state["target_cc_count"]
    done = count >= target
    if __tracer is not None:
        try:
            __tracer.log_attributes(stage="report", generated_cc_count=count,
                                    target_cc_count=target, done=done)
        except Exception:
            pass
    print(f"[haki_burr] finalized CC #{count} of {target} target")
    return state.update(generated_cc_count=count, done=done, last_agent="reporter")


@action(reads=[], writes=["code_iteration", "verified", "report", "novelty_result", "last_agent"])
def restart(state) -> dict:
    """Reset per-protocol state for a fresh proposal (preserving the CC counters)."""
    return state.update(code_iteration=0, verified=False, report={},
                        novelty_result={}, last_agent="restart")
