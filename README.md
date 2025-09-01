# LLM-Generated Concurrency Control Playground

This repository provides a minimal, pluggable core (`cc_core.py`) that lets you evaluate different concurrency control (CC) algorithms. Your LLM will generate implementations that conform to the `ConcurrencyControl` interface.

## Layout

- `cc_core.py`: Core versioned store and plugin interface
- `cc_protocols/`: CC plugin implementations
  - `template.py`: Minimal template for LLM-generated CC
  - `read_committed.py`: Simple baseline CC (Read Committed + RYOW)
- `demo.py`: Small runnable demo

## Quick start

```bash
python3 demo.py
```

Expected output shows commits and reads under the baseline policy.

## Writing a new CC policy (for the LLM)

Create a new module under `cc_protocols/` implementing `ConcurrencyControl`:

```python
from typing import Any, Optional
from cc_core import ConcurrencyControl, Transaction, CommitResult, VersionedKVStore

class MyCC(ConcurrencyControl):
    def __init__(self) -> None:
        # manage your own txid/time if needed
        self._next_txid = 0
        self._time = 0

    def begin(self, store: VersionedKVStore) -> Transaction:
        self._next_txid += 1
        self._time += 1
        return Transaction(txid=self._next_txid, start_ts=self._time)

    def read(self, txn: Transaction, key: str, store: VersionedKVStore) -> Optional[Any]:
        # visibility logic
        return store.get(key, txn.start_ts)

    def write(self, txn: Transaction, key: str, value: Any, store: VersionedKVStore) -> None:
        # write intent logic
        txn.write_set[key] = value

    def commit(self, txn: Transaction, store: VersionedKVStore) -> CommitResult:
        # validation/serialization logic
        self._time += 1
        store.put(txn.write_set, self._time)
        return CommitResult(ok=True, commit_ts=self._time)

    def rollback(self, txn: Transaction, store: VersionedKVStore) -> None:
        # release state
        txn.meta.clear()
        txn.write_set.clear()
```

Use it directly with a store:

```python
from cc_core import VersionedKVStore
from cc_protocols.my_cc import MyCC

store = VersionedKVStore()
cc = MyCC()

txn = cc.begin(store)
cc.write(txn, "k", 1, store)
res = cc.commit(txn, store)
```

## Notes

- Only CC exposes `read`/`write`. The store uses `get`/`put` to avoid naming confusion.
- Baseline `ReadCommitted` is intentionally simple; adapt `template.py` for more advanced protocols (OCC, SI, 2PL, etc.).
