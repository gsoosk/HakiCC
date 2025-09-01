from dataclasses import dataclass
from typing import Any, Dict, List, Optional

@dataclass
class Version:
    """A committed version. end_ts is implied by the next version's begin_ts."""
    begin_ts: int
    value: Any

class VersionedKVStore:
    """
    In-memory versioned key-value store:
      - get(key, ts): returns the committed value visible at ts
      - put(writes, commit_ts): adds new versions at commit_ts
    """
    def __init__(self):
        self._data: Dict[str, List[Version]] = {}

    def get(self, key: str, ts: int) -> Optional[Any]:
        vs = self._data.get(key)
        if not vs:
            return None
        # Versions are sorted by begin_ts ascending.
        # Visible version is the latest with begin_ts <= ts.
        lo, hi = 0, len(vs) - 1
        ans: Optional[Any] = None
        while lo <= hi:
            mid = (lo + hi) // 2
            if vs[mid].begin_ts <= ts:
                ans = vs[mid].value
                lo = mid + 1
            else:
                hi = mid - 1
        return ans

    def put(self, writes: Dict[str, Any], commit_ts: int) -> None:
        for k, v in writes.items():
            chain = self._data.setdefault(k, [])
            chain.append(Version(begin_ts=commit_ts, value=v))
            chain.sort(key=lambda ver: ver.begin_ts)
