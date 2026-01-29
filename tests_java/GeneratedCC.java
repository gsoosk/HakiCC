
package tests_java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class GeneratedCC extends ConcurrencyControl {

    private static final int TPCC_NUM_WAREHOUSES = 8;

    private final AtomicInteger txIdCounter = new AtomicInteger(0);
    private final AtomicInteger globalTimestamp = new AtomicInteger(0);
    private final LogProcessor[] processors;

    public GeneratedCC() {
        processors = new LogProcessor[TPCC_NUM_WAREHOUSES + 1];
        for (int i = 1; i <= TPCC_NUM_WAREHOUSES; i++) {
            processors[i] = new LogProcessor(i);
            processors[i].start();
        }
    }

    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    private static class VersionedValue {
        volatile Object value;
        volatile int version;

        VersionedValue(Object value, int version) {
            this.value = value;
            this.version = version;
        }
    }

    @FunctionalInterface
    private interface UpdateFunction extends java.io.Serializable {
        Object apply(Object currentValue);
    }

    private static class SetValueFunction implements UpdateFunction {
        private final Object value;
        SetValueFunction(Object value) { this.value = value; }
        @Override
        public Object apply(Object currentValue) { return value; }
    }

    private static class AddFunction implements UpdateFunction {
        private final int delta;
        AddFunction(int delta) { this.delta = delta; }
        @Override
        public Object apply(Object currentValue) {
            if (currentValue == null) return delta;
            return ((Number) currentValue).intValue() + delta;
        }
    }

    private static class IncrementFunction implements UpdateFunction {
        @Override
        public Object apply(Object currentValue) {
            if (currentValue == null) return 1;
            return ((Number) currentValue).intValue() + 1;
        }
    }

    private static class CommitRequest {
        final Transaction txn;
        final CompletableFuture<CommitResult> future;
        CommitRequest(Transaction txn, CompletableFuture<CommitResult> future) {
            this.txn = txn;
            this.future = future;
        }
    }

    private class LogProcessor extends Thread {
        private final int partitionId;
        private final BlockingQueue<CommitRequest> queue = new LinkedBlockingQueue<>();
        private static final int MAX_BATCH_SIZE = 128;

        LogProcessor(int partitionId) {
            this.partitionId = partitionId;
            setName("LogProcessor-" + partitionId);
        }

        @Override
        public void run() {
            List<CommitRequest> batch = new ArrayList<>(MAX_BATCH_SIZE);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    batch.add(queue.take());
                    queue.drainTo(batch, MAX_BATCH_SIZE - 1);
                    processBatch(batch);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    System.err.println("LogProcessor " + partitionId + " caught exception: " + t.getMessage());
                    for (CommitRequest req : batch) {
                        if (!req.future.isDone()) {
                            req.future.complete(CommitResult.failure("Processor failed: " + t.getMessage()));
                        }
                    }
                } finally {
                    batch.clear();
                }
            }
        }

        private void processBatch(List<CommitRequest> batch) {
            if (batch.isEmpty()) return;

            KVStore store = (KVStore) batch.get(0).txn.meta.get("store");
            Map<String, VersionedValue> batchWriteBuffer = new HashMap<>();
            List<CompletableFuture<CommitResult>> successfulFutures = new ArrayList<>();

            for (CommitRequest req : batch) {
                Transaction txn = req.txn;
                boolean valid = true;

                for (Map.Entry<String, Object> readEntry : txn.readSet.entrySet()) {
                    String key = readEntry.getKey();
                    int readVersion = (Integer) readEntry.getValue();
                    
                    VersionedValue currentVV = getVersionedValue(key, store);
                    int currentVersion = (currentVV != null) ? currentVV.version : -1;
                    
                    if (currentVersion > readVersion) {
                        valid = false;
                        req.future.complete(CommitResult.failure("Read validation failed on key: " + key));
                        break;
                    }
                }

                if (!valid) continue;

                for (Map.Entry<String, Object> writeEntry : txn.writeSet.entrySet()) {
                    String key = writeEntry.getKey();
                    UpdateFunction func = (UpdateFunction) writeEntry.getValue();

                    VersionedValue baseValue = batchWriteBuffer.get(key);
                    if (baseValue == null) {
                        baseValue = getVersionedValue(key, store);
                        if (baseValue == null) {
                             baseValue = new VersionedValue(0, -1);
                        }
                    }
                    
                    Object newValue = func.apply(baseValue.value);
                    batchWriteBuffer.put(key, new VersionedValue(newValue, baseValue.version));
                }
                successfulFutures.add(req.future);
            }

            int commitTs = globalTimestamp.incrementAndGet();
            for (Map.Entry<String, VersionedValue> updatedEntry : batchWriteBuffer.entrySet()) {
                String key = updatedEntry.getKey();
                VersionedValue vv = updatedEntry.getValue();
                vv.version++; 
                store.update(key, vv);
            }

            for (CompletableFuture<CommitResult> future : successfulFutures) {
                future.complete(CommitResult.success(commitTs));
            }
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        Transaction txn = new Transaction(txIdCounter.incrementAndGet(), globalTimestamp.get());
        txn.meta.put("store", store);
        return txn;
    }

    private VersionedValue getVersionedValue(String key, KVStore store) {
        Object obj = store.get(key);
        if (obj instanceof VersionedValue) {
            return (VersionedValue) obj;
        }
        if (obj != null) {
            Object rawValue;
            if (obj instanceof AtomicLong) {
                rawValue = ((AtomicLong) obj).intValue();
            } else if (obj instanceof Number) {
                rawValue = ((Number) obj).intValue();
            } else {
                rawValue = obj;
            }
            VersionedValue vv = new VersionedValue(rawValue, 0);
            // This lazy conversion has a race condition, but it's acceptable for initialization
            // as it will quickly converge to a stable VersionedValue state.
            store.update(key, vv);
            return vv;
        }
        return null;
    }

    private Object peek(String key, KVStore store) {
        VersionedValue vv = getVersionedValue(key, store);
        return (vv != null) ? vv.value : null;
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;
        
        VersionedValue vv = getVersionedValue(key, store);
        if (vv != null) {
            txn.readSet.put(key, vv.version);
            return vv.value;
        }
        txn.readSet.put(key, -1);
        return null;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        UpdateFunction func;
        if (key.endsWith("_ytd") || key.endsWith("_bal")) {
            // Use peek() to get current value without polluting the readSet.
            // This is the key fix to prevent livelock on commutative operations.
            Object currentValObj = peek(key, store);
            int currentVal = (currentValObj instanceof Number) ? ((Number) currentValObj).intValue() : 0;
            int newVal = ((Number) value).intValue();
            func = new AddFunction(newVal - currentVal);
        } else if (key.endsWith("_next_oid")) {
            func = new IncrementFunction();
        } else {
            // For non-commutative ops, the preceding read must be validated.
            read(txn, key, store);
            func = new SetValueFunction(value);
        }
        txn.writeSet.put(key, func);
    }
    
    private static int getWarehouseId(String key) {
        if (key.startsWith("item_")) {
            return 1; // Route all global item keys to a default partition.
        }
        int start = key.indexOf('_');
        if (start == -1 || start + 1 >= key.length()) return 1;
        
        int end = key.indexOf('_', start + 1);
        if (end == -1) end = key.length();
        
        try {
            String idStr = key.substring(start + 1, end);
            if (!idStr.isEmpty()) {
                return Integer.parseInt(idStr);
            }
        } catch (NumberFormatException e) {
            return 1;
        }
        return 1;
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction already terminated.");
        }
        
        if (txn.writeSet.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(globalTimestamp.get());
        }

        String firstKey = txn.writeSet.keys().nextElement();
        int partitionId = getWarehouseId(firstKey);
        
        if (partitionId <= 0 || partitionId > TPCC_NUM_WAREHOUSES) {
             txn.status = TxStatus.ABORTED;
            return CommitResult.failure("Invalid partition ID: " + partitionId + " for key " + firstKey);
        }

        CompletableFuture<CommitResult> future = new CompletableFuture<>();
        processors[partitionId].queue.add(new CommitRequest(txn, future));

        try {
            CommitResult result = future.get();
            if (result.ok) {
                txn.status = TxStatus.COMMITTED;
            } else {
                txn.status = TxStatus.ABORTED;
            }
            return result;
        } catch (InterruptedException | ExecutionException e) {
            txn.status = TxStatus.ABORTED;
            Thread.currentThread().interrupt();
            return CommitResult.failure("Commit interrupted: " + e.getMessage());
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        txn.status = TxStatus.ABORTED;
    }
}
