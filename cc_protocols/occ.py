from typing import Any, Optional
from dataclasses import dataclass
from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus
from versioned_store import VersionedKVStore


@dataclass
class OCCTransaction(Transaction):
    pass


class OCC(ConcurrencyControl):
    """
    Optimistic Concurrency Control:
    - No locks on read/write; reads from a snapshot at start_ts with read-your-own-writes.
    - On commit, validates read-set against latest committed versions.
    - If validation passes, applies write-set at a fresh commit timestamp; otherwise aborts.
    
    Note: We maintain a simple logical clock (monotonic commit timestamps) inside OCC.
    """

    def __init__(self) -> None:
        self._next_txid = 0
        self._time = 0

    def _now(self) -> int:
        return self._time

    def _tick(self) -> int:
        self._time += 1
        return self._time

    def begin(self, store: VersionedKVStore) -> OCCTransaction:
        self._next_txid += 1
        start_ts = self._tick()
        return OCCTransaction(txid=self._next_txid, start_ts=start_ts)

    def read(self, txn: Transaction, key: str, store: VersionedKVStore) -> Optional[Any]:
        if txn.status is not TxStatus.ACTIVE:
            return None
        # Read-your-own-writes
        if key in txn.write_set:
            return txn.write_set[key]
        # Snapshot read at start_ts
        value = store.get(key, txn.start_ts)
        # Record version timestamp we observed
        txn.read_set[key] = txn.start_ts
        return value

    def write(self, txn: Transaction, key: str, value: Any, store: VersionedKVStore) -> None:
        if txn.status is not TxStatus.ACTIVE:
            return
        txn.write_set[key] = value

    def _validate(self, txn: Transaction, store: VersionedKVStore, commit_ts: int) -> bool:
        # For each read key, ensure no newer committed version appeared between start_ts and commit_ts
        for key, read_ver_ts in txn.read_set.items():
            latest = store.get(key, commit_ts)
            at_start = store.get(key, txn.start_ts)
            if latest != at_start:
                return False
        return True

    def commit(self, txn: Transaction, store: VersionedKVStore) -> CommitResult:
        if txn.status is not TxStatus.ACTIVE:
            return CommitResult(ok=False, reason="not-active")
        # Choose a commit timestamp
        commit_ts = self._tick()
        # Validate
        if not self._validate(txn, store, commit_ts):
            txn.status = TxStatus.ABORTED
            return CommitResult(ok=False, reason="validation-failed")
        # Apply writes
        store.put(txn.write_set, commit_ts)
        txn.status = TxStatus.COMMITTED
        return CommitResult(ok=True, commit_ts=commit_ts)

    def rollback(self, txn: Transaction, store: VersionedKVStore) -> None:
        if txn.status is TxStatus.ACTIVE:
            txn.status = TxStatus.ABORTED
        txn.read_set.clear()
        txn.write_set.clear()
        txn.meta.clear()
