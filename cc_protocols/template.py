from typing import Any, Optional
from cc_core import ConcurrencyControl, Transaction, CommitResult
from versioned_store import VersionedKVStore


class TemplateCC(ConcurrencyControl):
    """
    Minimal template CC implementation.
    - Maintains simple monotonic counters for txid and logical time.
    - Reads: read-your-own-writes, else snapshot at start_ts
    - Writes: buffered in txn.write_set
    - Commit: applies buffered writes at a fresh commit_ts
    - Rollback: clears per-txn state
    """

    def __init__(self) -> None:
        self._next_txid = 0
        self._time = 0

    def _now(self) -> int:
        return self._time

    def _tick(self) -> int:
        self._time += 1
        return self._time

    def begin(self, store: VersionedKVStore) -> Transaction:
        self._next_txid += 1
        start_ts = self._tick()
        return Transaction(txid=self._next_txid, start_ts=start_ts)

    def read(self, txn: Transaction, key: str, store: VersionedKVStore) -> Optional[Any]:
        if key in txn.write_set:
            return txn.write_set[key]
        return store.get(key, txn.start_ts)

    def write(self, txn: Transaction, key: str, value: Any, store: VersionedKVStore) -> None:
        txn.write_set[key] = value

    def commit(self, txn: Transaction, store: VersionedKVStore) -> CommitResult:
        commit_ts = self._tick()
        store.put(txn.write_set, commit_ts)
        return CommitResult(ok=True, commit_ts=commit_ts)

    def rollback(self, txn: Transaction, store: VersionedKVStore) -> None:
        txn.meta.clear()
        txn.write_set.clear()