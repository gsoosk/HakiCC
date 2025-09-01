from versioned_store import VersionedKVStore
from cc_protocols.strict2pl import Strict2PL


def main() -> None:
    store = VersionedKVStore()
    cc = Strict2PL()

    # Initialize some values
    t1 = cc.begin(store)
    cc.write(t1, "x", 10, store)
    cc.write(t1, "y", 20, store)
    res = cc.commit(t1, store)
    print(f"Init commit ok={res.ok} ts={res.commit_ts}")

    # T2 reads x, y (gets shared locks)
    t2 = cc.begin(store)
    x2 = cc.read(t2, "x", store)
    y2 = cc.read(t2, "y", store)
    print(f"T2 reads x={x2} y={y2}")  # Should see 10, 20

    # T3 tries to write x but aborts (younger than T2)
    t3 = cc.begin(store)  # Higher txid than T2
    cc.write(t3, "x", 30, store)  # Will abort due to wait-die
    print(f"T3 status: {t3.status}")  # Should be ABORTED

    # T2 can still read y again (still has lock)
    y2_again = cc.read(t2, "y", store)
    print(f"T2 reads y again={y2_again}")  # Should see 20

    # T2 commits, releasing locks
    res = cc.commit(t2, store)
    print(f"T2 commit ok={res.ok} ts={res.commit_ts}")

    # Now T4 can write x (no locks held)
    t4 = cc.begin(store)
    cc.write(t4, "x", 40, store)
    res = cc.commit(t4, store)
    print(f"T4 commit ok={res.ok} ts={res.commit_ts}")

    # Verify final values
    t5 = cc.begin(store)
    print(f"Final x={cc.read(t5, 'x', store)} y={cc.read(t5, 'y', store)}")


if __name__ == "__main__":
    main()
