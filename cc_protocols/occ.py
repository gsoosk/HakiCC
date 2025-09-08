from typing import Any, Optional, List, Dict
from dataclasses import dataclass
import threading
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
        # OCC-level per-key locks (not store locks)
        self._key_locks: Dict[str, threading.RLock] = {}
        self._key_locks_meta = threading.Lock()
        # Per-key commit counters to detect any intervening writes regardless of value
        self._key_commit_counters: Dict[str, int] = {}
        self._counter_lock = threading.Lock()

    def _now(self) -> int:
        return self._time

    def _tick(self) -> int:
        self._time += 1
        return self._time

    def _get_or_create_lock(self, key: str) -> threading.RLock:
        lock = self._key_locks.get(key)
        if lock is not None:
            return lock
        with self._key_locks_meta:
            lock = self._key_locks.get(key)
            if lock is None:
                lock = threading.RLock()
                self._key_locks[key] = lock
            return lock

    def _acquire_sorted_locks(self, keys: List[str]) -> List[Any]:
        """
        Acquire OCC-level per-key locks in a globally sorted key order to
        avoid deadlocks across transactions. Returns the acquired locks.
        """
        acquired: List[Any] = []
        # De-duplicate and sort keys to ensure a consistent global order
        for key in sorted(set(keys)):
            lock = self._get_or_create_lock(key)
            lock.acquire()
            acquired.append(lock)
        return acquired

    def _release_locks(self, locks: List[Any]) -> None:
        """Release locks in reverse order of acquisition."""
        for lock in reversed(locks):
            lock.release()

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
        # Record per-key commit counter observed on first read of key
        read_counters = txn.meta.setdefault("read_counters", {})
        if key not in read_counters:
            with self._counter_lock:
                read_counters[key] = self._key_commit_counters.get(key, 0)
        return value

    def write(self, txn: Transaction, key: str, value: Any, store: VersionedKVStore) -> None:
        if txn.status is not TxStatus.ACTIVE:
            return
        txn.write_set[key] = value

    def _validate(self, txn: Transaction) -> bool:
        """
        Validation rule: For each key read by this transaction, ensure that
        no other transaction committed a write to that key after the first read.
        We track this using per-key commit counters captured at first read.
        """
        read_counters: Dict[str, int] = txn.meta.get("read_counters", {})
        with self._counter_lock:
            for key, observed in read_counters.items():
                current = self._key_commit_counters.get(key, 0)
                if current != observed:
                    return False
        return True

    def commit(self, txn: Transaction, store: VersionedKVStore) -> CommitResult:
        if txn.status is not TxStatus.ACTIVE:
            return CommitResult(ok=False, reason="not-active")
        # Lock all relevant keys (reads and writes) in a sorted order to avoid deadlocks
        keys_to_lock: List[str] = list(txn.read_set.keys()) + list(txn.write_set.keys())
        locks = self._acquire_sorted_locks(keys_to_lock)
        try:
            # Validate under locks so that validate+write is atomic
            if not self._validate(txn):
                txn.status = TxStatus.ABORTED
                return CommitResult(ok=False, reason="validation-failed")
            # Choose a commit timestamp only after validation and while holding locks
            commit_ts = self._tick()
            # Apply writes
            store.put(txn.write_set, commit_ts)
            # Update per-key commit counters
            if txn.write_set:
                with self._counter_lock:
                    for k in txn.write_set.keys():
                        self._key_commit_counters[k] = self._key_commit_counters.get(k, 0) + 1
            txn.status = TxStatus.COMMITTED
            return CommitResult(ok=True, commit_ts=commit_ts)
        finally:
            self._release_locks(locks)

    def rollback(self, txn: Transaction, store: VersionedKVStore) -> None:
        if txn.status is TxStatus.ACTIVE:
            txn.status = TxStatus.ABORTED
        txn.read_set.clear()
        txn.write_set.clear()
        txn.meta.clear()
