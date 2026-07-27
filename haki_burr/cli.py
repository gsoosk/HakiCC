"""``haki-burr`` CLI: run the Stage-1 pipeline, smoke-test it offline, or install
the Copilot agent profiles.

  haki-burr run   --workload tpcc --target 1 --app-id run-001 [--verify-mode ssh|local]
  haki-burr check                      # offline mock: happy path + repair loop
  haki-burr install-agents             # mirror agents/*.agent.md -> ~/.copilot/agents
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from . import config as C
from .config import Config, ModelConfig, VerifyConfig, REPO_ROOT
from .copilot import ensure_agents_installed, verify_cli


def _build_cfg(args) -> Config:
    model = ModelConfig(default=args.model, effort_default=args.effort)
    verify = VerifyConfig(mode=args.verify_mode, ssh_host=args.ssh_host,
                          remote_repo=args.remote_repo)
    return Config(
        workload=args.workload,
        target_cc_count=args.target,
        max_iterations=args.max_iter,
        model=model,
        verify=verify,
    )


def cmd_run(args) -> int:
    os.chdir(str(REPO_ROOT))  # so cwd-relative prompt reads (cc_core.py) resolve
    from .app import build_application

    cli = verify_cli()
    if not cli.get("ok") and not os.environ.get("HAKI_MOCK"):
        print(f"[haki-burr] WARNING: copilot CLI not found/healthy: {cli}", file=sys.stderr)

    cfg = _build_cfg(args)
    ensure_agents_installed()
    app = build_application(cfg, app_id=args.app_id)
    print(f"[haki-burr] running app_id={args.app_id} workload={cfg.workload} "
          f"target={cfg.target_cc_count} verify={cfg.verify.mode}")
    action, result, state = app.run(halt_after=["terminal"])
    print(f"[haki-burr] done. finalized={state['generated_cc_count']} "
          f"history_len={len(state['history'])}")
    return 0


def cmd_check(args) -> int:
    os.chdir(str(REPO_ROOT))
    os.environ["HAKI_MOCK"] = "1"
    from .app import build_application

    ok = True
    # Scenario 1: happy path (verify passes first try), 1 protocol.
    os.environ["HAKI_MOCK_FAIL"] = "0"
    cfg = _build_cfg(args)
    app = build_application(cfg, app_id="check-happy")
    _, _, st = app.run(halt_after=["terminal"])
    ok &= st["generated_cc_count"] == 1 and st["done"]
    print(f"[check] happy path: finalized={st['generated_cc_count']} done={st['done']}")

    # Scenario 2: repair loop (verify fails twice, then passes).
    os.environ["HAKI_MOCK_FAIL"] = "2"
    cfg = _build_cfg(args)
    app = build_application(cfg, app_id="check-repair")
    _, _, st = app.run(halt_after=["terminal"])
    repairs = sum(1 for h in st["history"] if not h["verified"])
    ok &= st["generated_cc_count"] == 1 and repairs == 2
    print(f"[check] repair loop: repairs={repairs} finalized={st['generated_cc_count']}")

    print("[check] PASS" if ok else "[check] FAIL")
    return 0 if ok else 1


def cmd_install_agents(args) -> int:
    names = ensure_agents_installed()
    print(f"[haki-burr] installed agents: {', '.join(names) or '(none)'}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="haki-burr", description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp):
        sp.add_argument("--workload", choices=["tpcc", "auction"], default="tpcc")
        sp.add_argument("--target", type=int, default=1, help="protocols to finalize")
        sp.add_argument("--max-iter", type=int, default=40, help="repair cap per protocol")
        sp.add_argument("--model", default=None, help="Copilot model (default: CLI default)")
        sp.add_argument("--effort", default="max", help="reasoning effort")
        sp.add_argument("--verify-mode", choices=["ssh", "local"], default="ssh")
        sp.add_argument("--ssh-host", default="chameleon")
        sp.add_argument("--remote-repo", default="projects/HakiCC")

    r = sub.add_parser("run", help="run the pipeline")
    common(r)
    r.add_argument("--app-id", default="haki-run", help="resume by reusing the same id")
    r.set_defaults(func=cmd_run)

    c = sub.add_parser("check", help="offline mock smoke test")
    common(c)
    c.set_defaults(func=cmd_check)

    i = sub.add_parser("install-agents", help="install Copilot agent profiles")
    i.set_defaults(func=cmd_install_agents)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
