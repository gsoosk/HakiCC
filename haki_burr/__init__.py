"""haki_burr: a CodeWeaver-style re-implementation of HakiCC **Stage 1**.

Apache Burr is the deterministic orchestrator (state machine + repair/restart
loops + SQLite crash-resume + telemetry UI); it never calls an LLM. The four
LLM roles (proposer, generator, novelty, reporter) are GitHub Copilot CLI custom
agents defined in ``agents/*.agent.md`` and invoked one subprocess per stage.
Verification reuses the existing Java benchmark (``tests_java/run_evaluator.sh``)
unchanged -- run on the Chameleon node over SSH for high-concurrency fidelity.
"""

__all__ = ["config", "state", "prompts", "copilot", "verify", "actions", "app"]
