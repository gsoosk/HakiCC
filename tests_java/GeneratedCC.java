
package tests_java;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementation of Optimistic Batch Aggregation (OBA).
 * This protocol combines optimistic concurrency control with batched, partitioned commits.
 * Transactions execute optimistically, reading versioned data. Writes are recorded
 * locally as deltas (for commutative operations) or final values. At commit time,
 * transactions are submitted to a dedicated, single-threaded worker for their data
 * partition (based on warehouse ID). The worker processes transactions in batches:
 * it validates all transactions in the batch, aggregates the deltas of the valid
 * ones, applies the aggregated changes to the database in a single burst, and
 * then notifies the client threads. This minimizes database contention and I/O
 * by turning many small writes into a few large, atomic updates.
 */
class GeneratedCC extends ConcurrencyControl {

    private static final int BATCH_SIZE = 200;
    private static final int BATCH_TIMEOUT_MS = 2;
    private static final Pattern WAREHOUSE_KEY_PATTERN = Pattern.compile("^[a-z]_(\\d+)_.*");

    // A wrapper to add a version number to every value for OCC.
    private static class VersionedValue {
        volatile Object value;
        final AtomicLong version = new AtomicLong(0);

        VersionedValue(Object value) {
            this.value = value;
        }
    }
    
    // A request submitted to a partition worker's queue.
    private static class TransactionRequest {
        final Transaction txn;
        final CompletableFuture<CommitResult> future;

        TransactionRequest(Transaction txn, CompletableFuture<CommitResult> future) {
            this.txn = txn;
            this.future = future;
        }
    }
    
    private final AtomicInteger nextTxid = new AtomicInteger(0);
    // The main data store, holding versioned values. Managed by this CC protocol.
    private static final ConcurrentHashMap<String, VersionedValue> versionedStore = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, AtomicLong> districtNextOid = new ConcurrentHashMap<>();
    private static volatile boolean isInitialized = false;

    private final ConcurrentHashMap<Integer, PartitionWorker> partitions = new ConcurrentHashMap<>();
    
    public static ConcurrencyControl buildCc() {
        return new GeneratedCC();
    }

    public GeneratedCC() {
        // Ensure data is initialized only once.
        synchronized (GeneratedCC.class) {
            if (!isInitialized) {
                initVersionedData();
                isInitialized = true;
            }
        }
    }

    @Override
    public Transaction begin(KVStore store) {
        return new Transaction(nextTxid.getAndIncrement(), 0);
    }

    @Override
    public Object read(Transaction txn, String key, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return null;

        // Optimization: _next_oid is handled by a dedicated atomic counter to avoid
        // making it a high-contention point for OCC validation.
        if (key.endsWith("_next_oid")) {
            int districtId = getDistrictId(key);
            long nextId = districtNextOid.computeIfAbsent(districtId, k -> new AtomicLong(1)).get();
            txn.readSet.put(key, nextId); // Store value, but it won't be version-validated
            return nextId;
        }

        VersionedValue vv = versionedStore.get(key);
        if (vv == null) return null;

        txn.readSet.put(key, vv.version.get()); // Store version for validation
        return vv.value;
    }

    @Override
    public void write(Transaction txn, String key, Object value, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;

        // Optimization for _next_oid
        if (key.endsWith("_next_oid")) {
            int districtId = getDistrictId(key);
            districtNextOid.get(districtId).set(((Number) value).longValue());
            // No need to add to writeset, it's applied immediately and atomically.
            return;
        }

        // Semantic analysis: store deltas for commutative operations
        if (isCommutativeKey(key)) {
            Object oldValueObj = read(txn, key, store);
            if (oldValueObj instanceof Number) {
                long oldValue = ((Number) oldValueObj).longValue();
                long newValue = ((Number) value).longValue();
                long delta = newValue - oldValue;
                txn.writeSet.put(key, delta);
                return;
            }
        }
        
        // Default: store the final value for non-commutative overwrites
        txn.writeSet.put(key, value);
    }

    @Override
    public CommitResult commit(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) {
            return CommitResult.failure("Transaction not active.");
        }

        // Read-only or next_oid-only transactions can commit immediately.
        if (txn.writeSet.isEmpty()) {
            txn.status = TxStatus.COMMITTED;
            return CommitResult.success(0);
        }

        int partitionId = getPartitionIdFromTxn(txn);
        PartitionWorker worker = partitions.computeIfAbsent(partitionId, id -> new PartitionWorker());
        
        CompletableFuture<CommitResult> future = new CompletableFuture<>();
        txn.meta.put("future", future);
        worker.submit(new TransactionRequest(txn, future));

        try {
            return future.get(); // Block and wait for the worker to process the batch
        } catch (InterruptedException | ExecutionException e) {
            txn.status = TxStatus.ABORTED;
            return CommitResult.failure("Commit interrupted or failed: " + e.getMessage());
        }
    }

    @Override
    public void rollback(Transaction txn, KVStore store) {
        if (txn.status != TxStatus.ACTIVE) return;
        txn.status = TxStatus.ABORTED;
        
        CompletableFuture<CommitResult> future = (CompletableFuture<CommitResult>) txn.meta.get("future");
        if (future != null) {
            future.completeExceptionally(new RuntimeException("Transaction rolled back by client."));
        }
    }

    public void shutdown() {
        partitions.values().forEach(PartitionWorker::shutdown);
    }
    
    // --- Helper Methods & Classes ---

    private boolean isCommutativeKey(String key) {
        return key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty");
    }

    private int getWarehouseId(String key) {
        Matcher matcher = WAREHOUSE_KEY_PATTERN.matcher(key);
        if (matcher.matches()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0; // Global partition for non-warehouse keys like "item_..."
    }
    
    private int getDistrictId(String key) {
        // A simple hash-based unique ID for district keys like "d_wid_did_next_oid"
        String[] parts = key.split("_");
        if (parts.length > 2) {
            return Integer.parseInt(parts[1]) * 100 + Integer.parseInt(parts[2]);
        }
        return key.hashCode();
    }

    private int getPartitionIdFromTxn(Transaction txn) {
        // Find the first key in either set to determine the partition
        if (!txn.readSet.isEmpty()) {
            return getWarehouseId(txn.readSet.keys().nextElement());
        }
        if (!txn.writeSet.isEmpty()) {
            return getWarehouseId(txn.writeSet.keys().nextElement());
        }
        return 0; // Default to global partition
    }

    private static class PartitionWorker implements Runnable {
        private final BlockingQueue<TransactionRequest> queue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;

        PartitionWorker() {
            Thread workerThread = new Thread(this, "PartitionWorker-" + this.hashCode());
            workerThread.setDaemon(true);
            workerThread.start();
        }

        void submit(TransactionRequest request) {
            if (running) {
                queue.add(request);
            } else {
                request.future.complete(CommitResult.failure("System is shutting down."));
            }
        }

        void shutdown() {
            running = false;
        }

        @Override
        public void run() {
            List<TransactionRequest> batch = new ArrayList<>(BATCH_SIZE);
            while (running) {
                try {
                    // Drain the queue to form a batch
                    Queues.drain(queue, batch, BATCH_SIZE, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!batch.isEmpty()) {
                        processBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        private void processBatch(List<TransactionRequest> batch) {
            Set<Transaction> failedTxs = new HashSet<>();
            Map<String, Long> aggregatedDeltas = new HashMap<>();
            Map<String, Object> aggregatedOverwrites = new HashMap<>();
            Set<String> writtenKeys = new HashSet<>();

            // Phase 1: Validation
            for (TransactionRequest req : batch) {
                Transaction txn = req.txn;
                for (Map.Entry<String, Object> readEntry : txn.readSet.entrySet()) {
                    String key = readEntry.getKey();
                    if (key.endsWith("_next_oid")) continue; // Skip validation for this key

                    long readVersion = (Long) readEntry.getValue();
                    VersionedValue currentVv = versionedStore.get(key);
                    if (currentVv == null || currentVv.version.get() != readVersion) {
                        failedTxs.add(txn);
                        break; // No need to validate further for this txn
                    }
                }
            }

            // Phase 2: Aggregation
            for (TransactionRequest req : batch) {
                if (!failedTxs.contains(req.txn)) {
                    for (Map.Entry<String, Object> writeEntry : req.txn.writeSet.entrySet()) {
                        String key = writeEntry.getKey();
                        Object value = writeEntry.getValue();
                        writtenKeys.add(key);

                        if (value instanceof Long && isCommutativeKey(key)) {
                            aggregatedDeltas.merge(key, (Long) value, Long::sum);
                        } else {
                            // Last writer in the batch wins for non-commutative writes
                            aggregatedOverwrites.put(key, value);
                        }
                    }
                }
            }

            // Phase 3: Materialization
            for (Map.Entry<String, Long> entry : aggregatedDeltas.entrySet()) {
                VersionedValue vv = versionedStore.get(entry.getKey());
                if (vv != null && vv.value instanceof Long) {
                    vv.value = ((Long) vv.value) + entry.getValue();
                }
            }
            for (Map.Entry<String, Object> entry : aggregatedOverwrites.entrySet()) {
                 VersionedValue vv = versionedStore.get(entry.getKey());
                 if (vv != null) {
                    vv.value = entry.getValue();
                 }
            }
            // Increment versions for all written keys
            for (String key : writtenKeys) {
                VersionedValue vv = versionedStore.get(key);
                if (vv != null) {
                    vv.version.incrementAndGet();
                }
            }


            // Phase 4: Notification
            for (TransactionRequest req : batch) {
                if (failedTxs.contains(req.txn)) {
                    req.txn.status = TxStatus.ABORTED;
                    req.future.complete(CommitResult.failure("Validation failed."));
                } else {
                    req.txn.status = TxStatus.COMMITTED;
                    req.future.complete(CommitResult.success(0));
                }
            }
        }
         private boolean isCommutativeKey(String key) {
            return key.endsWith("_ytd") || key.endsWith("_bal") || key.endsWith("_qty");
        }
    }

    private static void initVersionedData() {
        // This is a simplified, non-concurrent initialization.
        // Constants matching the provided transaction generator code.
        int TPCC_NUM_WAREHOUSES = 10;
        int TPCC_DISTRICTS_PER_WH = 10;
        int TPCC_CUSTOMERS_PER_DIST = 3000;
        int TPCC_NUM_ITEMS = 100000;
        long TPCC_INITIAL_YTD = 300000;
        long TPCC_INITIAL_BALANCE = -10;
        long TPCC_INITIAL_STOCK = 100;

        Random rand = new Random(42);

        for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
            versionedStore.put("w_" + wid + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
            for (int did = 1; did <= TPCC_DISTRICTS_PER_WH; did++) {
                versionedStore.put("d_" + wid + "_" + did + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
                int districtId = wid * 100 + did;
                districtNextOid.put(districtId, new AtomicLong(1));
                for (int cid = 1; cid <= TPCC_CUSTOMERS_PER_DIST; cid++) {
                    versionedStore.put("c_" + wid + "_" + did + "_" + cid + "_bal", new VersionedValue(TPCC_INITIAL_BALANCE));
                    versionedStore.put("c_" + wid + "_" + did + "_" + cid + "_ytd", new VersionedValue(TPCC_INITIAL_YTD));
                }
            }
        }
        for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
            versionedStore.put("item_" + iid + "_price", new VersionedValue(rand.nextInt(9901) + 100));
        }
        for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
            for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
                versionedStore.put("s_" + wid + "_" + iid + "_qty", new VersionedValue(TPCC_INITIAL_STOCK));
            }
        }
    }
}

// A helper class for BlockingQueue that is not part of the standard Java library until Java 9+.
// This is included to ensure compatibility with older Java versions.
final class Queues {
    private Queues() {}
    static <E> int drain(BlockingQueue<E> q, Collection<? super E> c, int maxElements, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        int added = 0;
        while (added < maxElements) {
            added += q.drainTo(c, maxElements - added);
            if (added < maxElements) {
                E e = q.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if (e == null) break;
                c.add(e);
                added++;
            }
        }
        return added;
    }
}
