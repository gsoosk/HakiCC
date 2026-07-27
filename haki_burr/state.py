"""Burr state schema for the HakiCC Stage-1 pipeline.

Burr's State is an immutable dict (``state.update(...)`` / ``state.append(...)``).
The schema mirrors HakiCC's LangGraph ``State`` TypedDict so the Burr graph
reproduces the same Stage-1 behaviour.

State keys
----------
  code_iteration    : int   generate->verify repair attempts for the CURRENT protocol
  max_iterations    : int   repair cap (== MAX_CODE_ITERATIONS = 40); restart when hit
  verified          : bool   did the last verify pass (correctness == 1.0)?
  report            : dict   last verification feedback (cleared on pass); repair input
  novelty_result    : dict   parsed novelty.json for the current protocol
  novelty_ok        : bool  did the current protocol clear the novelty gate?
  novelty_restarts  : int   novelty-driven restarts so far (capped)
  generated_cc_count: int   how many protocols have been finalized so far
  target_cc_count   : int   stop after this many finalized protocols
  workload          : str   "tpcc" | "auction"
  history           : list  append-only log of {phase, iter, verified}
  done              : bool   whole pipeline finished (target reached)
  last_agent        : str   most recently run agent/stage
"""
from __future__ import annotations

from .config import Config


def initial_state(cfg: Config) -> dict:
    return {
        "code_iteration": 0,
        "max_iterations": cfg.max_iterations,
        "verified": False,
        "report": {},
        "novelty_result": {},
        "novelty_ok": True,
        "novelty_restarts": 0,
        "generated_cc_count": 0,
        "target_cc_count": cfg.target_cc_count,
        "workload": cfg.workload,
        "history": [],
        "done": False,
        "last_agent": "",
    }
