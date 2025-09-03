import os
import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import threading
import time
import random
import unittest
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set, Tuple
from collections import defaultdict, deque


from versioned_store import VersionedKVStore
from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus
from cc_protocols.strict2pl import Strict2PL
from cc_protocols.read_committed import ReadCommitted
from cc_protocols.occ import OCC

import logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def get_cc_factory():
    name = os.environ.get("CC_IMPL", "Strict2PL").strip()
    mapping = {
        "Strict2PL": Strict2PL,
        "ReadCommitted": ReadCommitted,
        "OCC": OCC,
    }
    return mapping.get(name, Strict2PL)


@dataclass
class Op:
    seq: int
    txid: int
    kind: str  # 'R' or 'W'
    key: str


class MockKVStore(VersionedKVStore):
    """
    Store wrapper that logs get/put operations at the time they actually hit storage.
    Uses thread-local binding to attribute operations to the current transaction ID.
    """
    def __init__(self, on_get, on_put, get_txid):
        super().__init__()
        self._on_get = on_get
        self._on_put = on_put
        self._get_txid = get_txid
        self._tls = threading.local()

    def bind_txid(self, txid: int) -> None:
        setattr(self._tls, "txid", txid)

    def current_txid(self) -> Optional[int]:
        return getattr(self._tls, "txid", None)

    # Override
    def get(self, key: str, ts: int) -> Optional[int]:
        val = super().get(key, ts)
        txid = self.current_txid()
        if txid is None:
            txid = self._get_txid()
        if txid is not None:
            self._on_get(txid, key)
        return val

    # Override
    def put(self, writes: Dict[str, int], commit_ts: int) -> None:
        txid = self.current_txid()
        if txid is None:
            txid = self._get_txid()
        # Log per-key writes before applying
        if txid is not None:
            for k in writes.keys():
                self._on_put(txid, k)
        super().put(writes, commit_ts)


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

        self._ops: List[Op] = []
        self._ops_lock = threading.Lock()
        self._seq = 0

        def next_seq() -> int:
            # there should be a lock on ops_lock before the execution of this function. to ensure operation ordering. 
            self._seq += 1
            return self._seq

        # Callbacks used by MockKVStore
        def on_get(txid: int, key: str) -> None:
            with self._ops_lock:
                self._ops.append(Op(seq=next_seq(), txid=txid, kind='R', key=key))

        def on_put(txid: int, key: str) -> None:
            with self._ops_lock:
                self._ops.append(Op(seq=next_seq(), txid=txid, kind='W', key=key))

        def get_txid() -> Optional[int]:
            # Fallback: use thread-local bound txid from runner if store has none
            return getattr(self._tls, "txn_txid", None)

        self._tls = threading.local()
        self.store = MockKVStore(on_get=on_get, on_put=on_put, get_txid=get_txid)
        self.cc: ConcurrencyControl = self.cc_factory()

        self._committed: Set[int] = set()
        self._aborted: Set[int] = set()

        # Progress tracking
        self._progress_tls = threading.local()      # each thread stores its slot index here
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
                setattr(self._progress_tls, "slot", slot)
                self._progress_slots[slot] = time.time()
            while not stop.is_set():
                try:
                    try:
                        txn = self.cc.begin(self.store)
                        # Bind transaction ID for this thread in both runner and store
                        setattr(self._tls, "txn_txid", txn.txid)
                        self.store.bind_txid(txn.txid)
                        workload(self, txn)
                    except Exception as e:
                        logger.error(f"Error during transaction processing: {e}")
                    finally:
                        # Clear binding after workload iteration
                        setattr(self._tls, "txn_txid", None)
                        self.store.bind_txid(-1)
                except Exception:
                    # On exception, still update progress to avoid false stalls
                    slot_idx = getattr(self._progress_tls, "slot", None)
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
        with self._ops_lock:
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
        slot_idx = getattr(self._progress_tls, "slot", None)
        if slot_idx is not None and 0 <= slot_idx < len(self._progress_slots):
            self._progress_slots[slot_idx] = time.time()

    def log_read(self, txn: Transaction, key: str) -> Optional[int]:
        # No direct op logging here; store will log on actual get
        val = self.cc.read(txn, key, self.store)
        self._touch_progress()
        return val if isinstance(val, int) else (val if val is not None else None)

    def log_write(self, txn: Transaction, key: str, value: int) -> None:
        # No direct op logging here; store will log on actual put (likely at commit)
        self.cc.write(txn, key, value, self.store)
        self._touch_progress()

    def log_commit(self, txn: Transaction) -> CommitResult:
        # Bind txn id in case CC performs store IO during commit
        setattr(self._tls, "txn_txid", txn.txid)
        self.store.bind_txid(txn.txid)
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


# ---------- Precedence graph (efficient) ----------

@dataclass
class _KeyState:
    last_writer: Optional[int] = None
    readers: Set[int] = field(default_factory=set)


def build_precedence_graph_fast(ops: List[Op]) -> Dict[int, Set[int]]:
    """
    Linear in ops + edges.
    Assumes ops are only from committed txns and in increasing seq order.
    """
    graph: Dict[int, Set[int]] = defaultdict(set)
    # ensure all txids appear as nodes
    for op in ops:
        graph.setdefault(op.txid, set())

    state: Dict[str, _KeyState] = {}
    # ops assumed sorted by seq (producer ensures monotonic seq)

    for op in ops:
        ks = state.setdefault(op.key, _KeyState())
        if op.kind == 'R':
            lw = ks.last_writer
            if lw is not None and lw != op.txid:
                graph[lw].add(op.txid)            # W→R
            ks.readers.add(op.txid)               # dedup
        else:  # 'W'
            lw = ks.last_writer
            if lw is not None and lw != op.txid:
                graph[lw].add(op.txid)            # W→W
            for r in ks.readers:
                if r != op.txid:
                    graph[r].add(op.txid)         # R→W
            ks.last_writer = op.txid
            ks.readers.clear()
    return graph


def has_cycle_kahn(graph: Dict[int, Set[int]]) -> bool:
    indeg = {u: 0 for u in graph}
    for u, vs in graph.items():
        for v in vs:
            indeg[v] = indeg.get(v, 0) + 1
            if v not in graph:
                graph[v] = set()
    q = deque([u for u, d in indeg.items() if d == 0])
    seen = 0
    while q:
        u = q.popleft()
        seen += 1
        for v in graph[u]:
            indeg[v] -= 1
            if indeg[v] == 0:
                q.append(v)
    return seen != len(indeg)


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
        graph = build_precedence_graph_fast(ops)
        self.assertFalse(has_cycle_kahn(graph), msg=f"Cycle detected in the precedence graph")
        self.assertGreater(len(committed), 0)

    def test_bank_conflict_serializable(self) -> None:
        runner = ConcurrentRunner(get_cc_factory(), num_workers=8, duration_s=1.0)
        ops, committed, _ = runner.run(wl_bank)
        logger.info(f"Throughput: {len(committed) / runner.duration_s} transactions per second")
        graph = build_precedence_graph_fast(ops)
        self.assertFalse(has_cycle_kahn(graph), msg=f"Cycle detected in the precedence graph")
        self.assertGreater(len(committed), 0)

    def test_progress_no_deadlock_freeze(self) -> None:
        """Ensure the system does not leave threads indefinitely waiting under deadlock-prone workload."""
        runner = ConcurrentRunner(get_cc_factory(), num_workers=8, duration_s=5, stall_threshold_s=0.2)
        ops, committed, stalled = runner.run(wl_deadlock_prone)
        self.assertEqual(len(stalled), 0, msg=f"Stalled threads detected: {stalled}")
        graph = build_precedence_graph_fast(ops)
        self.assertFalse(has_cycle_kahn(graph), msg=f"Cycle detected in the precedence graph")


if __name__ == "__main__":
    unittest.main()
