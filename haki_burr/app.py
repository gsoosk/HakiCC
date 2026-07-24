"""The Burr application: wire the HakiCC Stage-1 stages into a persisted,
resumable state machine with the repair loop and the multi-protocol restart loop.

    propose -> generate -> verify --(verified)--------------------> novelty -> report
                  ^            |                                                  |
     repair       |           | not verified & code_iter < max_iter              |
     (< max_iter) +-----------+                                                  |
                  |           | not verified & code_iter >= max_iter             |
     restart <----+-----------+                                                  |
        ^                                                                        |
        |  more protocols (count < target)                                       |
        +------------------------------------------------------------------------+
                                       |  target reached
                                       v
                                    terminal

Burr never calls an LLM; the stages invoke Copilot CLI agents (see actions.py).
Resume by reusing the same ``app_id`` -- the SQLite persister continues.
"""
from __future__ import annotations

from pathlib import Path

import burr.core
from burr.core import ApplicationBuilder, default, expr
from burr.core.persistence import SQLLitePersister

from . import actions, config as C, state as S
from .config import Config


def build_application(cfg: Config, app_id: str, db_path: str | None = None):
    C.set_active(cfg)
    cfg.ensure_dirs()
    db_path = db_path or cfg.resolved_db_path
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)

    persister = SQLLitePersister.from_values(db_path=db_path, table_name="haki_burr_state")
    persister.initialize()

    transitions = [
        ("propose", "generate"),
        ("generate", "verify"),
        # verified -> novelty
        ("verify", "novelty", expr("verified")),
        # failed but budget remains -> repair
        ("verify", "generate", expr("not verified and code_iteration < max_iterations")),
        # failed and budget exhausted -> restart (new proposal)
        ("verify", "restart", default),
        ("novelty", "report"),
        # more protocols wanted -> restart; else done
        ("report", "terminal", expr("done")),
        ("report", "restart", default),
        ("restart", "propose"),
    ]

    builder = (
        ApplicationBuilder()
        .with_actions(
            propose=actions.propose,
            generate=actions.generate,
            verify=actions.verify,
            novelty=actions.novelty,
            report=actions.report,
            restart=actions.restart,
            terminal=burr.core.Result("done", "history", "generated_cc_count", "novelty_result"),
        )
        .with_transitions(*transitions)
        .initialize_from(
            persister,
            resume_at_next_action=True,      # crash-resume
            default_state=S.initial_state(cfg),
            default_entrypoint="propose",
        )
        .with_state_persister(persister)
        .with_identifiers(app_id=app_id)
    )
    try:
        builder = builder.with_tracker("local", project=cfg.slug)  # Burr telemetry UI
    except Exception as e:  # tracking extra not installed -> run without the UI
        print(f"[haki_burr] telemetry UI disabled ({e}); install burr[tracking] to enable.")
    return builder.build()
