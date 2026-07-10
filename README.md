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
  - `llm.py`: model configuration (Gemini / Azure OpenAI)
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
- `HakiCC_openEvolve/`: git submodule — Stage 2 OpenEvolve-based evolutionary optimizer

## Environment Variables

| Variable | Required | Description |
|---|---|---|
| `GOOGLE_API_KEY` | Yes (Gemini) | API key for Google Gemini (default LLM provider) |
| `GOOGLE_CLOUD_PROJECT` | Yes (Gemini) | Google Cloud project ID for Gemini access |
| `AZURE_OPENAI_API_KEY` | Yes (Azure OpenAI) | API key for Azure OpenAI endpoint (alternative provider) |

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

Stage 2 lives in the `HakiCC_openEvolve/` submodule. It takes a conflict-serializable protocol produced by Stage 1 and runs an LLM-driven evolutionary loop (OpenEvolve) to optimize throughput for the target workload.

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
