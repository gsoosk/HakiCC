# HakiCC — LLM-Driven Concurrency Control Design

HakiCC is an LLM-driven multi-agent pipeline that automatically designs, verifies, and optimizes concurrency control (CC) protocols tailored to a given target application. Given a workload description, HakiCC generates an application-specific CC protocol, iteratively repairs it for conflict-serializability, and produces a verified implementation ready for performance evaluation.

The pipeline has two stages:

- **Stage 1** (this repository): a multi-agent system that takes a workload description, generates a CC protocol in Java, and iteratively verifies it for conflict-serializability using a Java-based correctness harness.
- **Stage 2** (`HakiCC_openEvolve/` submodule): an LLM-driven evolutionary loop (OpenEvolve) that takes a Stage 1 verified protocol and optimizes it for throughput against the target workload.

## Layout

- `cc_core.py`: CC interface and shared transaction types (Python baseline)
- `versioned_store.py`: In-memory multiversion KV store used by Python CC implementations
- `utils.py`: Shared utilities
- `cc_protocols/`: Python CC implementations and artifacts
  - `strict2pl.py`, `read_committed.py`, `occ.py`, `template.py`: baseline protocols
  - `generated_cc.py`: latest LLM-generated Python implementation (overwritten per iteration)
  - `final_generations/`: archived snapshots (Python and Java) produced by the final report node
- `agent/`: agent workflow, prompts, and LLM wiring
  - `agent.py`: builds the LangGraph workflow graph
  - `prompts.py`: system/user prompts for proposal, generation, correction, and reporting
  - `llm.py`: model configuration (Gemini / ZotGPT)
  - `transactions/`: Java transaction definitions (`Transaction.java`, `AuctionMarkTransaction.java`)
- `tests_java/`: Java-based evaluation framework (active correctness and performance harness)
  - `Evaluator.java`: correctness checker and concurrent workload runner
  - `GeneratedCC.java`: placeholder replaced at runtime with the LLM-generated implementation
  - `CCImplementationTemp.java`, `Concurrency.java`, `KVStore.java`: evaluation infrastructure
  - `run_evaluator.sh`: shell driver — compiles and runs the evaluator against a CC file
  - `cc_implementations/`: archived CC implementations grouped by run
    - `archive1/`–`archive5-auction/`: historical snapshots by workload
    - `baseline/`: reference implementations (2pl, occ, bamboo)
    - `improved-tpcc/`, `improved-auction/`: manually improved variants
  - `final_generations/`: final Java snapshots produced by the agent's report node
  - `benchmark_plotter.py`: plots benchmark results from `output.json`
- `run_agent.py`: runs the workflow and streams messages; emits `workflow_diagram.png`
- `run_with_ui.py`: runs the workflow with a terminal UI
- `HakiCC_openEvolve/`: git submodule — Stage 2 OpenEvolve-based evolutionary optimizer ([gsoosk/openevolve_HakiCC](https://github.com/gsoosk/openevolve_HakiCC))

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `GOOGLE_API_KEY` | Yes (Gemini) | API key for Google Gemini (default LLM provider) |
| `GOOGLE_CLOUD_PROJECT` | Yes (Gemini) | Google Cloud project ID for Gemini access |
| `ZOTGPT_API_KEY` | Yes (ZotGPT) | API key for UCI's ZotGPT Azure OpenAI endpoint (alternative provider) |

Create a `.env` file or export variables before running:

```bash
export GOOGLE_API_KEY=your_google_api_key
export GOOGLE_CLOUD_PROJECT=your_project_id
```

## Quick start

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt

python run_agent.py
```

This will:
- Build and render the workflow diagram as `workflow_diagram.png`
- Stream agent messages while iterating on a CC implementation
- Run the Java evaluator automatically and feed correctness results back to the LLM
- On completion, produce a final report and archive the generated CC under `tests_java/final_generations/`

## CLI Options

```
python run_agent.py [OPTIONS]

  --workload {tpcc,auction}     Target workload (default: tpcc)
  --target-cc-count N           Number of CC protocols to generate before stopping (default: 1)
  --language {java,python}      Code generation language (default: java)
  --inspiration-pdf PATH        Optional PDF to guide the proposal prompt
```

Example — generate 10 AuctionMark protocols:

```bash
python run_agent.py --workload auction --target-cc-count 10
```

## Workflow Overview

1. **cc_proposer**: LLM proposes a CC design based on the target workload's transaction patterns.
2. **cc_generator**: LLM generates Java code inside `<execute>...</execute>`; saved to `tests_java/GeneratedCC.java`.
3. **cc_correctness_checker**: Runs `tests_java/run_evaluator.sh`; feeds correctness and serializability results back to the LLM.
4. **cc_performance_evaluator**: Runs the evaluator for throughput metrics.
5. **cc_novelty_evaluator**: Decides whether to iterate further or finalize.
6. **cc_final_report**: Saves an artifact copy to `tests_java/final_generations/generated_cc_n.java` and produces a final technical report.

## Burr Orchestrator (branch `burr`)

The `burr` branch provides an alternative, CodeWeaver-style implementation of the
**Stage 1** pipeline. The orchestration is moved from LangGraph to a deterministic
**[Apache Burr](https://burr.apache.org/)** state machine, and the LLM agents become
**GitHub Copilot CLI custom agents** defined as markdown profiles. It reuses the
existing HakiCC prompts verbatim and the existing Java benchmark unchanged.

- **Burr** = the deterministic orchestrator (state machine, repair loop, restart
  loop, SQLite crash-resume, telemetry UI). It never calls an LLM.
- **Agents = markdown** (`agents/*.agent.md`), each run as one `copilot --agent NAME`
  subprocess that owns its full agent loop (reasoning, file edits). The full HakiCC
  prompt text is passed at invocation (imported verbatim from `agent/prompts.py`).
- **Verification = the existing Java benchmark**, `tests_java/run_evaluator.sh
  --correctness-only` (precedence-graph conflict-serializability), reused unchanged.

Because Copilot CLI authenticates on the developer's laptop while the benchmark
wants a high-concurrency machine, the agents run **locally** and verification is
shipped over **SSH to a compute node** (`--verify-mode ssh`, the default) — or run
locally with `--verify-mode local` (needs a JDK).

### Stage mapping (LangGraph → Burr)

| LangGraph node | Burr action | Kind |
|---|---|---|
| `cc_proposer` | `propose` | Copilot agent `proposer` → `proposal.md` |
| `cc_generator` | `generate` | Copilot agent `generator` → `tests_java/GeneratedCC.java` |
| `cc_correctness_checker` | `verify` | **deterministic** — `run_evaluator.sh` on the node |
| `cc_novelty_evaluator` | `novelty` | Copilot agent `novelty` → `novelty.json` |
| `cc_final_report` | `report` | Copilot agent `reporter` + versions `final_generations/` |
| `cc_restart` | `restart` | bookkeeping |

Transitions mirror Stage 1 exactly: `propose→generate→verify`, repair loop
`verify→generate` (capped by `--max-iter`, default 40), `verify→restart` on budget
exhaustion, `verify→novelty→report`, and `report→restart` until `--target` protocols
are finalized.

### Layout (added on `burr`)

- `haki_burr/`: the package — `config.py`, `state.py`, `prompts.py` (reuses
  `agent/prompts.py`), `copilot.py` (the `copilot` subprocess boundary), `verify.py`
  (SSH/local Java benchmark runner), `actions.py`, `app.py` (Burr wiring), `mock.py`
  (offline agent), `cli.py`.
- `agents/`: `proposer.agent.md`, `generator.agent.md`, `novelty.agent.md`,
  `reporter.agent.md`.
- `requirements-burr.txt`: adds `burr[tracking]`.

### Setup & usage

```bash
pip install -r requirements-burr.txt          # Apache Burr (+ telemetry UI)
python -m haki_burr install-agents            # mirror agents/*.agent.md -> ~/.copilot/agents

# Offline smoke test (mock agents + mock verify; no Copilot, no node, no cost):
python -m haki_burr check

# Real run: Copilot agents locally, Java benchmark on the node over SSH.
python -m haki_burr run --workload tpcc --target 1 --app-id run-001 \
    --verify-mode ssh --ssh-host chameleon --remote-repo projects/HakiCC

# Resume a crashed run: re-run with the SAME --app-id (SQLite persister continues).
# Telemetry UI:  burr
```

Useful options: `--target N` (protocols to finalize), `--max-iter N` (repair cap,
default 40), `--novelty-threshold N` (reject a verified protocol whose
`novelty_score_overall` is below `N` and restart for a more novel one — bounded by
`max_novelty_restarts`; `0` disables, the default), `--model`, `--effort` (default
`max`), and `--verify-mode {ssh,local}`.

On a failed verification, the repair loop receives a **categorized diagnosis**
(serializability violation / progress stalls / compile-runtime error / timeout)
extracted from the benchmark's per-scenario summaries, in addition to the full
evaluator JSON — giving the generator a sharper repair signal.

Requires the **GitHub Copilot CLI** (`copilot`) authenticated on the machine that
runs the agents, and (for `--verify-mode ssh`) an SSH alias to a node that has this
repo checked out with a JDK. Runtime artifacts land in `haki_burr/pipeline/`
(gitignored); finalized protocols are versioned into `tests_java/final_generations/`.


## Workloads

| Workload | Description |
|---|---|
| `tpcc` | TPC-C benchmark — warehouse, order, stock, and payment transactions |
| `auction` | AuctionMark benchmark — auction lifecycle transactions (defined in `agent/transactions/AuctionMarkTransaction.java`) |

## Running the Java Evaluator Manually

```bash
cd tests_java
./run_evaluator.sh [--correctness-only] [--pre-known-txn tpcc|auction] [CCImpl.java] [output.json]
```

Results are written to `output.json` and can be visualized with:

```bash
python tests_java/benchmark_plotter.py
```

## Artifacts

- Latest implementation: `tests_java/GeneratedCC.java` (overwritten each generation).
- Archived snapshots: `tests_java/final_generations/generated_cc_n.java` (created by the final report node).
- Workflow visualization: `workflow_diagram.png`.

## Stage 2 — Evolutionary Optimization

Stage 2 lives in the `HakiCC_openEvolve/` submodule ([gsoosk/openevolve_HakiCC](https://github.com/gsoosk/openevolve_HakiCC)). It takes a conflict-serializable protocol produced by Stage 1 and runs an LLM-driven evolutionary loop (OpenEvolve) to optimize throughput for the target workload.

To initialize the submodule after cloning:

```bash
git submodule update --init --recursive
```

See `HakiCC_openEvolve/README.md` for its own setup and usage instructions.

## Notes

- CC implementations must implement the `ConcurrencyControl` Java interface; they call `store.get`/`store.put` and must not modify store internals.
- Use `cc_protocols/template.py` as a starting point for new Python baseline protocols.
- Prompts for generation, correction, and final reporting live in `agent/prompts.py`.
- The `tests/` directory contains a deprecated Python test suite and is no longer used.
