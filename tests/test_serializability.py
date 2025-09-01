import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import threading
import time
import random
import unittest
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple


from versioned_store import VersionedKVStore
from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus
from cc_protocols.strict2pl import Strict2PL
from cc_protocols.read_committed import ReadCommitted

import logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def get_cc_factory():
    name = os.environ.get("CC_IMPL", "Strict2PL").strip()
    mapping = {
        "Strict2PL": Strict2PL,
        "ReadCommitted": ReadCommitted,
    }
    return mapping.get(name, Strict2PL)


@dataclass
class Op:
    txid: int
    kind: str  # 'R' or 'W'
    key: str


class ConcurrentRunner:
    """
    Runs a concurrent workload against a single store and a CC instance,
    logging read/write operations for building the precedence graph.
    Only committed transactions' operations are considered for serializability.

    Tracks per-thread progress using pre-assigned slots and thread-local indices
    to allow lock-free progress updates on the hot path.
    """

    def __init__(self, cc_factory, num_workers: int = 8, duration_s: float = 1.0, stall_threshold_s: float = 0.5):
        self.cc_factory = cc_factory
        self.num_workers = num_workers
        self.duration_s = duration_s
        self.stall_threshold_s = stall_threshold_s

        self.store = VersionedKVStore()
        self.cc: ConcurrencyControl = self.cc_factory()

        self._ops: List[Op] = []
        self._committed: Set[int] = set()
        self._aborted: Set[int] = set()

        # Progress tracking
        self._tls = threading.local()             # each thread stores its slot index here
        self._progress_slots: List[float] = [0.0] * self.num_workers
        self._slot_for_tid: Dict[int, int] = {}
        self._slot_lock = threading.Lock()
        self._next_slot = 0

    def run(self, workload) -> Tuple[List[Op], Set[int], Set[int]]:
        stop = threading.Event()
        threads: List[threading.Thread] = []
        start_time = time.time()

        def worker() -> None:
            tid = threading.get_ident()
            # Assign a slot for this thread once
            with self._slot_lock:
                slot = self._next_slot
                self._next_slot += 1
                self._slot_for_tid[tid] = slot
                setattr(self._tls, "slot", slot)
                self._progress_slots[slot] = time.time()
            while not stop.is_set():
                try:
                    txn = self.cc.begin(self.store)
                    workload(self, txn)
                except Exception:
                    # On exception, still update progress to avoid false stalls
                    slot_idx = getattr(self._tls, "slot", None)
                    if slot_idx is not None and 0 <= slot_idx < len(self._progress_slots):
                        self._progress_slots[slot_idx] = time.time()

        for _ in range(self.num_workers):
            t = threading.Thread(target=worker, daemon=True)
            threads.append(t)
            t.start()

        time.sleep(self.duration_s)
        stop.set()
        for t in threads:
            t.join(timeout=1)
        # Filter ops to only those from committed transactions
        committed_ops = [op for op in self._ops if op.txid in self._committed]

        # Detect stalled threads: no progress update within stall_threshold
        now = time.time()
        stalled: Set[int] = set()
        for t in threads:
            tid = t.ident
            if tid is None:
                continue
            slot = self._slot_for_tid.get(tid)
            if slot is None:
                continue
            last = self._progress_slots[slot]
            if now - last > self.stall_threshold_s:
                stalled.add(tid)
        return committed_ops, set(self._committed), stalled

    # ---------- Logging wrappers ----------

    def _touch_progress(self) -> None:
        slot_idx = getattr(self._tls, "slot", None)
        if slot_idx is not None and 0 <= slot_idx < len(self._progress_slots):
            # list assignment is atomic under the GIL; no lock needed here
            self._progress_slots[slot_idx] = time.time()

    def log_read(self, txn: Transaction, key: str) -> Optional[int]:
        val = self.cc.read(txn, key, self.store)
        self._ops.append(Op(txid=txn.txid, kind='R', key=key))
        self._touch_progress()
        return val if isinstance(val, int) else (val if val is not None else None)

    def log_write(self, txn: Transaction, key: str, value: int) -> None:
        self.cc.write(txn, key, value, self.store)
        self._ops.append(Op(txid=txn.txid, kind='W', key=key))
        self._touch_progress()

    def log_commit(self, txn: Transaction) -> CommitResult:
        res = self.cc.commit(txn, self.store)
        if res.ok:
            self._committed.add(txn.txid)
        else:
            self._aborted.add(txn.txid)
        self._touch_progress()
        return res

    def log_rollback(self, txn: Transaction) -> None:
        self.cc.rollback(txn, self.store)
        self._aborted.add(txn.txid)
        self._touch_progress()


# ---------- Precedence graph ----------

def build_precedence_graph(ops: List[Op]) -> Dict[int, Set[int]]:
    """
    For each key, for every pair of conflicting ops between different txns,
    add an edge from earlier op's txn to later op's txn.
    Conflicts: RW, WR, WW (on the same key).
    """
    graph: Dict[int, Set[int]] = {}
    by_key: Dict[str, List[Op]] = {}
    for op in ops:
        by_key.setdefault(op.key, []).append(op)
        graph.setdefault(op.txid, set())

    for key, key_ops in by_key.items():
        for i in range(len(key_ops)):
            oi = key_ops[i]
            for j in range(i + 1, len(key_ops)):
                oj = key_ops[j]
                if oi.txid == oj.txid:
                    continue
                if oi.kind == 'R' and oj.kind == 'R':
                    continue
                # Conflict -> add edge oi.txid -> oj.txid
                graph.setdefault(oi.txid, set()).add(oj.txid)
                graph.setdefault(oj.txid, set())
    return graph


def has_cycle(graph: Dict[int, Set[int]]) -> bool:
    visited: Set[int] = set()
    stack: Set[int] = set()

    def dfs(u: int) -> bool:
        if u in stack:
            return True
        if u in visited:
            return False
        visited.add(u)
        stack.add(u)
        for v in graph.get(u, set()):
            if dfs(v):
                return True
        stack.remove(u)
        return False

    for node in list(graph.keys()):
        if dfs(node):
            return True
    return False


# ---------- Workloads ----------

def wl_counter(r: ConcurrentRunner, txn: Transaction) -> None:
    v = r.log_read(txn, "ctr") or 0
    r.log_write(txn, "ctr", v + 1)
    r.log_commit(txn)


def wl_bank(r: ConcurrentRunner, txn: Transaction) -> None:
    # Initialize lazily if keys are absent
    va = r.log_read(txn, "a") or 0
    vb = r.log_read(txn, "b") or 0
    if va == 0 and vb == 0:
        # Initialize total to 1000 on first txn that finds zeros
        r.log_write(txn, "a", 500)
        r.log_write(txn, "b", 500)
        r.log_commit(txn)
        return
    x = random.randint(1, 5)
    if random.random() < 0.5:
        va, vb = va - x, vb + x
    else:
        va, vb = va + x, vb - x
    r.log_write(txn, "a", va)
    r.log_write(txn, "b", vb)
    r.log_commit(txn)


def wl_deadlock_prone(r: ConcurrentRunner, txn: Transaction) -> None:
    """Write two hot keys in random opposite orders to provoke potential deadlocks if CC blocks."""
    keys = ("A", "B")
    if random.random() < 0.5:
        first, second = keys
    else:
        first, second = keys[::-1]
    v1 = r.log_read(txn, first) or 0
    v2 = r.log_read(txn, second) or 0
    r.log_write(txn, first, v1 + 1)
    r.log_write(txn, second, v2 + 1)
    r.log_commit(txn)


class TestSerializability(unittest.TestCase):
    def test_counters_conflict_serializable(self) -> None:
        runner = ConcurrentRunner(get_cc_factory(), num_workers=8, duration_s=1.0)
        ops, committed, _ = runner.run(wl_counter)
        logger.info(f"Throughput: {len(committed) / runner.duration_s} transactions per second")
        graph = build_precedence_graph(ops)
        self.assertFalse(has_cycle(graph), msg=f"Cycle detected in the precedence graph")
        self.assertGreater(len(committed), 0)

    def test_bank_conflict_serializable(self) -> None:
        runner = ConcurrentRunner(get_cc_factory(), num_workers=8, duration_s=1.0)
        ops, committed, _ = runner.run(wl_bank)
        logger.info(f"Throughput: {len(committed) / runner.duration_s} transactions per second")
        graph = build_precedence_graph(ops)
        self.assertFalse(has_cycle(graph), msg=f"Cycle detected in the precedence graph")
        self.assertGreater(len(committed), 0)

    def test_progress_no_deadlock_freeze(self) -> None:
        """Ensure the system does not leave threads indefinitely waiting under deadlock-prone workload."""
        runner = ConcurrentRunner(get_cc_factory(), num_workers=8, duration_s=5, stall_threshold_s=0.2)
        ops, committed, stalled = runner.run(wl_deadlock_prone)
        self.assertEqual(len(stalled), 0, msg=f"Stalled threads detected: {stalled}")
        graph = build_precedence_graph(ops)
        self.assertFalse(has_cycle(graph), msg=f"Cycle detected in the precedence graph")


if __name__ == "__main__":
    unittest.main()
