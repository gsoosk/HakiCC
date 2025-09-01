from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, Optional, Set
from cc_core import ConcurrencyControl, Transaction, CommitResult, TxStatus
from versioned_store import VersionedKVStore


class LockMode(Enum):
    SHARED = "S"      # Read lock
    EXCLUSIVE = "X"   # Write lock


@dataclass
class Strict2PLTransaction(Transaction):
    """
    Transaction with added lock tracking for Strict 2PL.
    Inherits all base Transaction fields and adds:
    - held_locks: tracks locks this transaction currently holds
    - lock_mode: tracks mode of each held lock
    """
    held_locks: Set[str] = field(default_factory=set)  # Keys this txn has locked
    lock_mode: Dict[str, LockMode] = field(default_factory=dict)  # Key -> current lock mode


class Strict2PL(ConcurrencyControl):
    """
    Strict Two-Phase Locking implementation:
    - Uses extended transaction type to track locks
    - Acquires locks before each read/write
    - All locks held until commit/abort (strict)
    - Read locks are shared, write locks exclusive
    - Detects and prevents deadlocks via simple wait-die
    """
    
    def __init__(self) -> None:
        self._next_txid = 0
        self._time = 0
        # Global lock table: key -> {holder_txids: mode}
        self._locks: Dict[str, Dict[int, LockMode]] = {}

    def _now(self) -> int:
        return self._time

    def _tick(self) -> int:
        self._time += 1
        return self._time

    def begin(self, store: VersionedKVStore) -> Strict2PLTransaction:
        """Create a new transaction with lock tracking."""
        self._next_txid += 1
        start_ts = self._tick()
        return Strict2PLTransaction(txid=self._next_txid, start_ts=start_ts)

    def _can_acquire_lock(self, txid: int, key: str, mode: LockMode) -> bool:
        """
        Check if txid can acquire lock in given mode.
        Uses simple wait-die scheme: if younger transaction would wait for older, abort instead.
        """
        holders = self._locks.get(key, {})
        
        if not holders:
            return True  # No current holders
            
        if txid in holders:
            # Already hold lock - check if upgrade needed
            return mode == holders[txid] or (
                mode == LockMode.EXCLUSIVE and 
                holders[txid] == LockMode.SHARED and
                len(holders) == 1
            )
            
        # Check other holders
        for holder_id, holder_mode in holders.items():
            # Can share read locks
            if mode == LockMode.SHARED and holder_mode == LockMode.SHARED:
                continue
            # Otherwise, conflict - use wait-die: abort if younger
            if txid > holder_id:  # Higher txid means younger
                return False
        return True

    def _acquire_lock(self, txn: Strict2PLTransaction, key: str, mode: LockMode) -> bool:
        """
        Try to acquire lock in given mode.
        Returns True if acquired, False if should abort.
        """
        if not self._can_acquire_lock(txn.txid, key, mode):
            return False
            
        holders = self._locks.setdefault(key, {})
        holders[txn.txid] = mode
        txn.held_locks.add(key)
        txn.lock_mode[key] = mode
        return True

    def _release_all_locks(self, txn: Strict2PLTransaction) -> None:
        """Release all locks held by transaction."""
        for key in txn.held_locks:
            holders = self._locks.get(key, {})
            if txn.txid in holders:
                del holders[txn.txid]
            if not holders:
                del self._locks[key]
        txn.held_locks.clear()
        txn.lock_mode.clear()

    def read(self, txn: Transaction, key: str, store: VersionedKVStore) -> Optional[Any]:
        """Read with shared lock, unless we already have an exclusive lock."""
        strict_txn = txn if isinstance(txn, Strict2PLTransaction) else None
        if not strict_txn or txn.status is not TxStatus.ACTIVE:
            return None
            
        # Return from write set if present
        if key in strict_txn.write_set:
            return strict_txn.write_set[key]
            
        # Get appropriate lock
        mode = LockMode.SHARED
        if key in strict_txn.held_locks:
            mode = strict_txn.lock_mode[key]
        elif not self._acquire_lock(strict_txn, key, mode):
            strict_txn.status = TxStatus.ABORTED
            self.rollback(strict_txn, store)
            return None
            
        # Read latest committed value
        value = store.get(key, self._now())
        if value is not None:
            strict_txn.read_set[key] = self._now()
        return value

    def write(self, txn: Transaction, key: str, value: Any, store: VersionedKVStore) -> None:
        """Write with exclusive lock."""
        strict_txn = txn if isinstance(txn, Strict2PLTransaction) else None
        if not strict_txn or txn.status is not TxStatus.ACTIVE:
            return
            
        # Get exclusive lock (upgrading if needed)
        if key not in strict_txn.held_locks or strict_txn.lock_mode[key] != LockMode.EXCLUSIVE:
            if not self._acquire_lock(strict_txn, key, LockMode.EXCLUSIVE):
                strict_txn.status = TxStatus.ABORTED
                self.rollback(strict_txn, store)
                return
                
        strict_txn.write_set[key] = value

    def commit(self, txn: Transaction, store: VersionedKVStore) -> CommitResult:
        """Apply writes and release locks."""
        strict_txn = txn if isinstance(txn, Strict2PLTransaction) else None
        if not strict_txn or txn.status is not TxStatus.ACTIVE:
            return CommitResult(ok=False, reason="not-active")
            
        # Apply writes at next timestamp
        commit_ts = self._tick()
        store.put(strict_txn.write_set, commit_ts)
        
        # Release all locks
        self._release_all_locks(strict_txn)
        strict_txn.status = TxStatus.COMMITTED
        
        return CommitResult(ok=True, commit_ts=commit_ts)

    def rollback(self, txn: Transaction, store: VersionedKVStore) -> None:
        """Release locks and clean up."""
        strict_txn = txn if isinstance(txn, Strict2PLTransaction) else None
        if not strict_txn:
            return
            
        if strict_txn.status is TxStatus.ACTIVE:
            strict_txn.status = TxStatus.ABORTED
            
        # Release all locks
        self._release_all_locks(strict_txn)
        
        # Clear transaction state
        strict_txn.read_set.clear()
        strict_txn.write_set.clear()
        strict_txn.meta.clear()
