from versioned_store import VersionedKVStore
from cc_protocols import ReadCommitted


def main() -> None:
    store = VersionedKVStore()
    cc = ReadCommitted()

    # T1 writes then commits
    t1 = cc.begin(store)
    cc.write(t1, "x", 1, store)
    res1 = cc.commit(t1, store)
    print(f"T1 commit ok={res1.ok} ts={res1.commit_ts}")

    # T2 reads committed value, then writes and commits
    t2 = cc.begin(store)
    v = cc.read(t2, "x", store)
    print(f"T2 read x={v}")
    cc.write(t2, "x", 2, store)
    res2 = cc.commit(t2, store)
    print(f"T2 commit ok={res2.ok} ts={res2.commit_ts}")

    # T3 read-your-own-writes check
    t3 = cc.begin(store)
    cc.write(t3, "y", 42, store)
    v3a = cc.read(t3, "y", store)
    print(f"T3 RYOW y={v3a}")
    res3 = cc.commit(t3, store)
    print(f"T3 commit ok={res3.ok} ts={res3.commit_ts}")

    # Verify final values
    t4 = cc.begin(store)
    print(f"Final x={cc.read(t4, 'x', store)} y={cc.read(t4, 'y', store)}")


if __name__ == "__main__":
    main()