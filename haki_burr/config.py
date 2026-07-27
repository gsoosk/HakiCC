"""Project configuration for the HakiCC Stage-1 Burr pipeline.

Everything project-specific (workload, targets, verification transport, model
selection, artifact paths) lives here so the Burr actions and the Copilot agents
stay generic. A single active Config is registered (`set_active`) so the
module-level ``@action`` functions can reach it.
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


# Repo root = parent of this package directory.
REPO_ROOT = Path(__file__).resolve().parent.parent


@dataclass
class VerifyConfig:
    """How/where the Java serializability benchmark runs.

    mode="ssh" (default): ship GeneratedCC.java to the Chameleon node and run
    tests_java/run_evaluator.sh there (the node has the high thread count Stage-1
    correctness needs). mode="local": run the script on this machine (needs a JDK).
    """
    mode: str = "ssh"                       # "ssh" | "local"
    ssh_host: str = "chameleon"             # ~/.ssh/config alias for the node
    remote_repo: str = "projects/HakiCC"    # repo path on the node (relative to $HOME)
    timeout: int = 420                      # seconds for the whole remote verify


@dataclass
class ModelConfig:
    """Copilot CLI model + reasoning effort. None => let the CLI pick its default
    (keeps us robust to model-name changes)."""
    default: str | None = None              # e.g. "claude-sonnet-4.5", "gpt-5"
    effort_default: str | None = "max"      # none|minimal|low|medium|high|xhigh|max
    effort: dict[str, str] = field(default_factory=dict)  # per-agent override

    def effort_for(self, agent: str) -> str | None:
        return self.effort.get(agent, self.effort_default)


@dataclass
class Config:
    # --- pipeline knobs (mirror agent/agent.py) ---
    workload: str = "tpcc"                   # "tpcc" | "auction"
    target_cc_count: int = 1                 # how many final protocols to produce
    max_iterations: int = 40                 # == MAX_CODE_ITERATIONS (repair cap)

    # --- layout ---
    root: Path = REPO_ROOT
    slug: str = "hakicc-stage1"

    model: ModelConfig = field(default_factory=ModelConfig)
    verify: VerifyConfig = field(default_factory=VerifyConfig)

    agent_timeout: float | None = 1800.0     # per Copilot agent invocation (s)

    def __post_init__(self) -> None:
        self.root = Path(self.root)
        self.workload = self.workload.lower()
        if self.workload not in ("tpcc", "auction"):
            raise ValueError(f"workload must be tpcc|auction, got {self.workload!r}")

    # ---- artifact paths (the file-based hand-off between stages) ----
    @property
    def pipeline_path(self) -> Path:
        return self.root / "haki_burr" / "pipeline"

    @property
    def logs_path(self) -> Path:
        return self.pipeline_path / "logs"

    @property
    def proposal_path(self) -> Path:
        return self.pipeline_path / "proposal.md"

    @property
    def report_path(self) -> Path:
        return self.pipeline_path / "report.json"

    @property
    def novelty_path(self) -> Path:
        return self.pipeline_path / "novelty.json"

    @property
    def report_md_path(self) -> Path:
        return self.pipeline_path / "report.md"

    @property
    def generated_java_path(self) -> Path:
        return self.root / "tests_java" / "GeneratedCC.java"

    @property
    def final_generations_dir(self) -> Path:
        return self.root / "tests_java" / "final_generations"

    @property
    def pre_known_txn(self) -> str:
        return "auction" if self.workload == "auction" else "tpcc"

    @property
    def resolved_db_path(self) -> str:
        return str(self.pipeline_path / "burr_state.db")

    def extra_env(self) -> dict:
        return {}

    def ensure_dirs(self) -> None:
        self.pipeline_path.mkdir(parents=True, exist_ok=True)
        self.logs_path.mkdir(parents=True, exist_ok=True)
        self.final_generations_dir.mkdir(parents=True, exist_ok=True)


# ---- active-config registry (so module-level @action can reach the config) ----
_ACTIVE: Config | None = None


def set_active(cfg: Config) -> None:
    global _ACTIVE
    _ACTIVE = cfg


def active() -> Config:
    if _ACTIVE is None:
        raise RuntimeError("No active Config set; call config.set_active(cfg) first.")
    return _ACTIVE
