"""Prompt rendering for the Burr pipeline.

Hard requirement: **reuse HakiCC's existing prompts verbatim.** We import the
prompt functions from ``agent/prompts.py`` (single source of truth -- no copy)
and only append the *agentic execution contract*: where to read inputs and which
artifact file to write. The one necessary adaptation vs. the LangGraph pipeline is
the OUTPUT mechanism -- the old pipeline parsed ``<execute>...</execute>`` from
stdout; a Copilot CLI agent instead writes the artifact file directly.
"""
from __future__ import annotations

import os
import re
from contextlib import contextmanager
from pathlib import Path

from .config import Config

# Reuse the existing HakiCC prompts unchanged.
from agent.prompts import (  # noqa: E402
    get_propose_prompt_with_transactions2,
    get_generate_prompt_java,
    get_incorrect_code_prompt_java,
    get_invalid_generate_prompt_java,
    get_novelty_prompt,
    get_final_report_prompt,
)


@contextmanager
def _cwd(path: Path):
    old = os.getcwd()
    os.chdir(str(path))
    try:
        yield
    finally:
        os.chdir(old)


def previous_proposals(cfg: Config) -> str:
    """Replicate Agent.get_previous_proposals() (java branch): the technical
    reports of prior finalized protocols, so the proposer does not repeat itself."""
    out = "Previous Proposals:\n"
    d = cfg.final_generations_dir
    if d.exists():
        for p in sorted(d.glob("generated_cc_*.java")):
            m = re.match(r"generated_cc_(\d+)\.java", p.name)
            if not m:
                continue
            idx = int(m.group(1))
            content = p.read_text(encoding="utf-8")
            tech_report = ""
            start = content.find("/* Technical Report:")
            if start != -1:
                start += len("/* Technical Report:")
                end = content.find("*/", start)
                if end != -1:
                    tech_report = content[start:end].strip()
            out += f"# PROPOSAL {idx}: {tech_report}\n"
    return out


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def render(stage: str, cfg: Config, **runtime) -> str:
    """Build the ``-p`` prompt for a stage's Copilot agent."""
    if stage == "propose":
        with _cwd(cfg.root):
            base = get_propose_prompt_with_transactions2(
                [], inspiration_text=None, workload=cfg.workload
            )
        prior = previous_proposals(cfg)
        return (
            f"{base}\n\n{prior}\n\n"
            "## Execution contract\n"
            f"Write ONLY your final protocol proposal (the design spec described "
            f"above) to `{cfg.proposal_path}`, overwriting any existing file. "
            "Do not write code in this step. End by confirming the file was written."
        )

    if stage == "generate":
        mode = runtime.get("mode", "implement")
        if mode == "repair":
            base = get_incorrect_code_prompt_java(runtime.get("feedback", ""))
            intro = (
                "You are in REPAIR mode. Read the current implementation at "
                f"`{cfg.generated_java_path}` and the failing verification report, "
                "then fix exactly the reported problems without changing the design."
            )
        else:
            base = get_generate_prompt_java()
            intro = (
                "Read the protocol design proposal at "
                f"`{cfg.proposal_path}` and implement it."
            )
        return (
            f"{intro}\n\n{base}\n\n"
            "## Execution contract (ADAPTATION)\n"
            "Instead of returning your code inside `<execute>...</execute>` tags, "
            f"WRITE your final, complete Java implementation directly to the file "
            f"`{cfg.generated_java_path}` (overwrite it). The class MUST be named "
            "`GeneratedCC`, be in package `tests_java`, extend `ConcurrencyControl`, "
            "and include `public static ConcurrencyControl buildCc() {{ return new "
            "GeneratedCC(); }}`. Write nothing else to disk. End by confirming the "
            "file was written and summarizing what you implemented/fixed."
        )

    if stage == "novelty":
        code = _read(cfg.generated_java_path)
        proposal = _read(cfg.proposal_path)
        base = get_novelty_prompt(code, proposal)
        return (
            f"{base}\n\n"
            "## Execution contract\n"
            f"Write ONLY the JSON object described above to `{cfg.novelty_path}` "
            "(valid JSON, no markdown fences, overwrite any existing file). "
            "End by confirming the file was written."
        )

    if stage == "report":
        base = get_final_report_prompt()
        return (
            f"Read the finalized implementation at `{cfg.generated_java_path}`.\n\n"
            f"{base}\n\n"
            "## Execution contract\n"
            f"Write ONLY the technical report text to `{cfg.report_md_path}` "
            "(plain text/markdown, overwrite any existing file). "
            "End by confirming the file was written."
        )

    raise ValueError(f"unknown stage: {stage!r}")
