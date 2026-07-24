"""Offline mock agent: replace Copilot so the Burr graph, transitions, repair loop,
restart loop and crash-resume can be exercised with no LLM cost.

Enabled by ``HAKI_MOCK=1`` (or ``haki-burr check``). Each mock "agent" writes the
same artifact a real agent would, so downstream stages and the versioning in
``report`` behave identically. Verification is mocked separately in verify.py
(scriptable via ``HAKI_MOCK_FAIL`` = number of verify failures before a pass).
"""
from __future__ import annotations

from . import config as C

_STUB_JAVA = """package tests_java;

// MOCK GeneratedCC (offline check only)
class GeneratedCC extends ConcurrencyControl {
    public static ConcurrencyControl buildCc() { return new GeneratedCC(); }
    public void begin(Transaction t) {}
    public Object read(Transaction t, String k) { return null; }
    public void write(Transaction t, String k, Object v) {}
    public CommitResult commit(Transaction t) { return null; }
    public void rollback(Transaction t) {}
}
"""

_STUB_NOVELTY = '{\n  "novelty_score_overall": 72,\n  "verdict": "Moderately Novel"\n}\n'


def respond(agent_name: str, prompt: str) -> str:
    cfg = C.active()
    cfg.ensure_dirs()
    if agent_name == "proposer":
        cfg.proposal_path.write_text("# MOCK Proposal\nA stub CC protocol design.\n", encoding="utf-8")
        return "mock: wrote proposal.md"
    if agent_name == "generator":
        cfg.generated_java_path.parent.mkdir(parents=True, exist_ok=True)
        cfg.generated_java_path.write_text(_STUB_JAVA, encoding="utf-8")
        return "mock: wrote GeneratedCC.java"
    if agent_name == "novelty":
        cfg.novelty_path.write_text(_STUB_NOVELTY, encoding="utf-8")
        return "mock: wrote novelty.json"
    if agent_name == "reporter":
        cfg.report_md_path.write_text("MOCK technical report of the protocol.\n", encoding="utf-8")
        return "mock: wrote report.md"
    return f"mock: unknown agent {agent_name}"
