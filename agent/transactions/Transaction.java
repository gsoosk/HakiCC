
// ---------- TPC-C Workload ----------

static final int TPCC_NUM_WAREHOUSES = 8;
static final int TPCC_DISTRICTS_PER_WH = 10;
static final int TPCC_CUSTOMERS_PER_DIST = 100;
static final int TPCC_NUM_ITEMS = 1000;
static final int TPCC_INITIAL_STOCK = 100;
static final int TPCC_INITIAL_BALANCE = 10000;
static final int TPCC_INITIAL_YTD = 0;

static void initTpccData(KVStore store) {
    Map<String, Object> writes = new HashMap<>();
    Random rand = new Random(42);  // Fixed seed for reproducibility

    // Initialize warehouses
    for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
        writes.put("w_" + wid + "_ytd", TPCC_INITIAL_YTD);

        // Initialize districts per warehouse
        for (int did = 1; did <= TPCC_DISTRICTS_PER_WH; did++) {
            writes.put("d_" + wid + "_" + did + "_ytd", TPCC_INITIAL_YTD);
            writes.put("d_" + wid + "_" + did + "_next_oid", 1);

            // Initialize customers per district
            for (int cid = 1; cid <= TPCC_CUSTOMERS_PER_DIST; cid++) {
                writes.put("c_" + wid + "_" + did + "_" + cid + "_bal", TPCC_INITIAL_BALANCE);
                writes.put("c_" + wid + "_" + did + "_" + cid + "_ytd", TPCC_INITIAL_YTD);
            }
        }
    }

    // Initialize items (global, not per-warehouse)
    for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
        writes.put("item_" + iid + "_price", rand.nextInt(9901) + 100);  // 100-10000
    }

    // Initialize stock per warehouse per item
    for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
        for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
            writes.put("s_" + wid + "_" + iid + "_qty", TPCC_INITIAL_STOCK);
        }
    }

    store.updateAll(writes);
}

static boolean tpccNewOrder(ConcurrentRunner r, Transaction txn) {
    Random rand = ThreadLocalRandom.current();

    int wid = rand.nextInt(TPCC_NUM_WAREHOUSES) + 1;
    int did = rand.nextInt(TPCC_DISTRICTS_PER_WH) + 1;
    int cid = rand.nextInt(TPCC_CUSTOMERS_PER_DIST) + 1;

    // Read and update district's next order ID
    String nextOidKey = "d_" + wid + "_" + did + "_next_oid";
    Object nextOidObj = r.logRead(txn, nextOidKey);
    int nextOid = (nextOidObj instanceof Integer) ? (Integer) nextOidObj : 1;
    r.logWrite(txn, nextOidKey, nextOid + 1);
    int oid = nextOid;

    // Read customer balance
    String custBalKey = "c_" + wid + "_" + did + "_" + cid + "_bal";
    r.logRead(txn, custBalKey);

    // Generate order lines (5-15 items per order)
    int numItems = rand.nextInt(11) + 5;
    int totalAmount = 0;

    for (int olNum = 1; olNum <= numItems; olNum++) {
        int iid = rand.nextInt(TPCC_NUM_ITEMS) + 1;

        // Read item price
        String itemPriceKey = "item_" + iid + "_price";
        Object priceObj = r.logRead(txn, itemPriceKey);
        int price = (priceObj instanceof Integer) ? (Integer) priceObj : 100;

        // Read and decrement stock
        String stockKey = "s_" + wid + "_" + iid + "_qty";
        Object stockQtyObj = r.logRead(txn, stockKey);
        int stockQty = (stockQtyObj instanceof Integer) ? (Integer) stockQtyObj : TPCC_INITIAL_STOCK;

        int olQty = rand.nextInt(10) + 1;

        int newQty = stockQty - olQty;
        if (newQty < 10) {
            newQty += 91;  // Restock
        }
        r.logWrite(txn, stockKey, newQty);

        totalAmount += price * olQty;
    }

    // Write order record
    String orderKey = "o_" + wid + "_" + did + "_" + oid + "_total";
    r.logWrite(txn, orderKey, totalAmount);

    return true;
}

static boolean tpccPayment(ConcurrentRunner r, Transaction txn) {
    Random rand = ThreadLocalRandom.current();

    int wid = rand.nextInt(TPCC_NUM_WAREHOUSES) + 1;
    int did = rand.nextInt(TPCC_DISTRICTS_PER_WH) + 1;
    int cid = rand.nextInt(TPCC_CUSTOMERS_PER_DIST) + 1;

    int paymentAmount = rand.nextInt(499901) + 100;  // 100-500000

    // Update warehouse YTD
    String whYtdKey = "w_" + wid + "_ytd";
    Object whYtdObj = r.logRead(txn, whYtdKey);
    int whYtd = (whYtdObj instanceof Integer) ? (Integer) whYtdObj : 0;
    r.logWrite(txn, whYtdKey, whYtd + paymentAmount);

    // Update district YTD
    String distYtdKey = "d_" + wid + "_" + did + "_ytd";
    Object distYtdObj = r.logRead(txn, distYtdKey);
    int distYtd = (distYtdObj instanceof Integer) ? (Integer) distYtdObj : 0;
    r.logWrite(txn, distYtdKey, distYtd + paymentAmount);

    // Update customer balance and YTD payment
    String custBalKey = "c_" + wid + "_" + did + "_" + cid + "_bal";
    String custYtdKey = "c_" + wid + "_" + did + "_" + cid + "_ytd";

    Object custBalObj = r.logRead(txn, custBalKey);
    Object custYtdObj = r.logRead(txn, custYtdKey);
    int custBal = (custBalObj instanceof Integer) ? (Integer) custBalObj : TPCC_INITIAL_BALANCE;
    int custYtd = (custYtdObj instanceof Integer) ? (Integer) custYtdObj : 0;

    r.logWrite(txn, custBalKey, custBal - paymentAmount);
    r.logWrite(txn, custYtdKey, custYtd + paymentAmount);

    return true;
}

static void wlTpcc(ConcurrentRunner r, Transaction txn) {
    Random rand = ThreadLocalRandom.current();
    boolean success;

    if (rand.nextBoolean()) {
        success = tpccNewOrder(r, txn);
    } else {
        success = tpccPayment(r, txn);
    }

    if (success) {
        r.logCommit(txn);
    } else {
        r.logRollback(txn);
    }
}