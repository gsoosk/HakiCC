
package tests_java;

import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// Helper enums for the versioning system
enum VersionType { ABSOLUTE, DELTA }
enum VersionStatus { SPECULATIVE, ABORTED }

// A class representing a single version of a key's value.
class Version {
    final Object value;
    final long seq;
    final VersionType type;
    final VersionStatus status;

    Version(Object value, long seq, VersionType type, VersionStatus status) {
        this.value = value;
        this.seq = seq;
        this.type = type;
        this.status = status;
    }

    public Object value() { return value; }
    public long seq() { return seq; }
    public VersionType type() { return type; }
    public VersionStatus status() { return status; }

    Version asAborted() {
        return new Version(this.value, this.seq, this.type, VersionStatus.ABORTED);
    }
}

// Manages the chain of versions for a single key.
class VersionChain {
    final ConcurrentSkipListMap<Long, Version> versions = new ConcurrentSkipListMap<>();

    VersionChain(Object initialValue) {
        versions.put(0L, new Version(initialValue, 0L, VersionType.ABSOLUTE, VersionStatus.SPECULATIVE));
    }
}

/**
 * Deterministic Version-Chain Execution (DVCE) Concurrency Control Protocol.
 * This version fixes a NullPointerException caused by putting null into a ConcurrentHashMap.
 */
class GeneratedCC extends ConcurrencyControl {
    private static final String SEQ_KEY = "seq";

    private final ConcurrentHashMap<String, VersionChain> multiVersionStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Scheduler> schedulers = new ConcurrentHashMap<>();

    private static class Scheduler {
        final AtomicLong seqCounter = new AtomicLong(1);
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    @Override
    public Transaction begin(KVStore store) {
        return new Transaction((int) System.nanoTime(), (int) System.nanoTime());
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        assignSequence(txn, key);
        long txnSeq = (long) txn.meta.get(SEQ_KEY);

        VersionChain vc = multiVersionStore.computeIfAbsent(key, k -> {
            Object initialValue = store.get(k);
            if (initialValue instanceof AtomicLong) {
                initialValue = ((AtomicLong) initialValue).get();
            }
            return new VersionChain(initialValue != null ? initialValue : 0L);
        });

        NavigableMap<Long, Version> visibleVersions = vc.versions.headMap(txnSeq, false);

        long baseValue = 0L;
        long lastAbsoluteSeq = 0L;

        for (Version v : visibleVersions.descendingMap().values()) {
            if (v.status() == VersionStatus.ABORTED) continue;
            if (v.type() == VersionType.ABSOLUTE) {
                baseValue = ((Number) v.value()).longValue();
                lastAbsoluteSeq = v.seq();
                break;
            }
        }

        long deltaSum = 0L;
        for (Version v : visibleVersions.tailMap(lastAbsoluteSeq, false).values()) {
             if (v.seq() > lastAbsoluteSeq && v.status() != VersionStatus.ABORTED && v.type() == VersionType.DELTA) {
                deltaSum += ((Number) v.value()).longValue();
            }
        }

        return baseValue + deltaSum;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        assignSequence(txn, key);
        long txnSeq = (long) txn.meta.get(SEQ_KEY);
        
        VersionChain vc = multiVersionStore.computeIfAbsent(key, k -> {
            Object initialValue = store.get(k);
            if (initialValue instanceof AtomicLong) {
                initialValue = ((AtomicLong) initialValue).get();
            }
            return new VersionChain(initialValue != null ? initialValue : 0L);
        });

        VersionType type = classifyWrite(key);
        Object writeValue = value;

        if (type == VersionType.DELTA) {
            Object readResult = read(txn, key, store);
            long currentValue = (readResult instanceof Number) ? ((Number) readResult).longValue() : 0L;
            long finalValue = ((Number) value).longValue();
            writeValue = finalValue - currentValue;
        }

        Version newVersion = new Version(writeValue, txnSeq, type, VersionStatus.SPECULATIVE);
        vc.versions.put(txnSeq, newVersion);
        
        // FIX: ConcurrentHashMap does not allow null values. Use a non-null placeholder.
        txn.writeSet.put(key, Boolean.TRUE); 
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already " + txn.status);
        }
        txn.status = TxStatus.COMMITTED;
        return CommitResult.success(txn.startTs);
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;

        if (txn.meta.get(SEQ_KEY) == null) return;

        long txnSeq = (long) txn.meta.get(SEQ_KEY);

        for (String key : txn.writeSet.keySet()) {
            VersionChain vc = multiVersionStore.get(key);
            if (vc != null) {
                Version versionToAbort = vc.versions.get(txnSeq);
                if (versionToAbort != null && versionToAbort.status() == VersionStatus.SPECULATIVE) {
                    vc.versions.replace(txnSeq, versionToAbort, versionToAbort.asAborted());
                }
            }
        }
    }

    private void assignSequence(Transaction txn, String key) {
        if (txn.meta.get(SEQ_KEY) == null) {
            synchronized (txn) {
                if (txn.meta.get(SEQ_KEY) == null) {
                    int partitionId = getPartitionIdFromKey(key);
                    Scheduler scheduler = schedulers.computeIfAbsent(partitionId, k -> new Scheduler());
                    long seq = scheduler.seqCounter.getAndIncrement();
                    txn.meta.put(SEQ_KEY, seq);
                }
            }
        }
    }

    private VersionType classifyWrite(String key) {
        if (key.endsWith("_ytd") || key.endsWith("_bal")) {
            return VersionType.DELTA;
        }
        return VersionType.ABSOLUTE;
    }

    private int getPartitionIdFromKey(String key) {
        String[] parts = key.split("_");
        if (parts.length > 1 && (parts[0].equals("w") || parts[0].equals("d") || parts[0].equals("s") || parts[0].equals("c") || parts[0].equals("o"))) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
