# LLM-Generated Concurrency Control Playground

This repository provides a minimal, pluggable core (`cc_core.py`) and an agentic workflow that lets an LLM propose, generate, test, and finalize concurrency control (CC) algorithms implementing the `ConcurrencyControl` interface.

## Layout

- `cc_core.py`: CC interface and shared transaction types
- `versioned_store.py`: In-memory multiversion KV store used by CC implementations
- `cc_protocols/`: CC implementations and artifacts
  - `strict2pl.py`, `read_committed.py`, `occ.py`, `template.py`
  - `generated_cc.py`: latest LLM-generated implementation (overwritten per iteration)
  - `generated_cc_n.py`: archived snapshots produced by the final report node
- `tests/test_serializability.py`: correctness tests and concurrent workload runner
- `agent/`: agent workflow, prompts, and LLM wiring
  - `agent.py`: builds the workflow graph
  - `prompts.py`: system/user prompts (includes `get_final_report_prompt`)
  - `llm.py`: model configuration
- `run_agent.py`: runs the workflow and streams messages; emits `workflow_diagram.png`

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
- Run tests automatically and provide feedback to the LLM
- On completion, produce a final report and archive the generated CC

## Workflow overview

1. cc_proposer: LLM proposes a CC design.
2. cc_generator: LLM generates code inside `<execute>...</execute>`; saved to `cc_protocols/generated_cc.py`.
3. cc_correctness_checker: Runs `tests/test_serializability.py`; feeds results back to the LLM.
4. cc_performance_evaluator: Placeholder for perf checks.
5. cc_novelty_evaluator: Decides whether to improve or end.
6. cc_final_report: When ending, saves an artifact copy as `generated_cc_n.py` and requests a final technical report from the LLM.

## Artifacts

- Latest implementation: `cc_protocols/generated_cc.py` (overwritten each generation).
- Archived snapshots: `cc_protocols/generated_cc_1.py`, `cc_protocols/generated_cc_2.py`, ... (created by the final report node).
- Workflow visualization: `workflow_diagram.png`.

## Running tests manually

The test suite can be run directly:

```bash
export CC_IMPL=GeneratedCC  # or Strict2PL / ReadCommitted / OCC
python -m unittest -v tests.test_serializability
```

## Concurrent runner behavior

The concurrent workload runner inside `tests/test_serializability.py` stops all workers on the first exception and raises a `RuntimeError` with a traceback. This provides immediate feedback for failures rather than hanging or silently continuing.

## Notes

- CC implementations call `store.get`/`store.put`; do not modify the store internals.
- Use `template.py` as a starting point for new protocols (OCC, SI, 2PL, etc.).
- Prompts for generation, correction, and final reporting live in `agent/prompts.py`.
