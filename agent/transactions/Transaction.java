


// Initializes the dataset on the provided key-value store.
static void initTpccData(KVStore store) {
    java.util.Random rand = new java.util.Random(42);  // Fixed seed for reproducibility

    // Initialize warehouses
    for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
        store.insert("w_" + wid + "_ytd", TPCC_INITIAL_YTD);

        // Initialize districts per warehouse
        for (int did = 1; did <= TPCC_DISTRICTS_PER_WH; did++) {
            store.insert("d_" + wid + "_" + did + "_ytd", TPCC_INITIAL_YTD);
            store.insert("d_" + wid + "_" + did + "_next_oid", 1);

            // Initialize customers per district
            for (int cid = 1; cid <= TPCC_CUSTOMERS_PER_DIST; cid++) {
                store.insert("c_" + wid + "_" + did + "_" + cid + "_bal", TPCC_INITIAL_BALANCE);
                store.insert("c_" + wid + "_" + did + "_" + cid + "_ytd", TPCC_INITIAL_YTD);
            }
        }
    }

    // Initialize items (global, not per-warehouse)
    for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
        store.insert("item_" + iid + "_price", rand.nextInt(9901) + 100); // 100-10000
    }

    // Initialize stock per warehouse per item
    for (int wid = 1; wid <= TPCC_NUM_WAREHOUSES; wid++) {
        for (int iid = 1; iid <= TPCC_NUM_ITEMS; iid++) {
            store.insert("s_" + wid + "_" + iid + "_qty", TPCC_INITIAL_STOCK);
        }
    }
}

// Issues a new order transaction for TPC-C using the ConcurrencyControl interface.
static boolean tpccNewOrder(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();

    int wid = rand.nextInt(TPCC_NUM_WAREHOUSES) + 1;
    int did = rand.nextInt(TPCC_DISTRICTS_PER_WH) + 1;
    int cid = rand.nextInt(TPCC_CUSTOMERS_PER_DIST) + 1;

    // Read and update district's next order ID
    String nextOidKey = "d_" + wid + "_" + did + "_next_oid";
    Object nextOidObj = cc.read(tx, nextOidKey, store);
    int nextOid = (nextOidObj instanceof Integer) ? (Integer) nextOidObj : 1;
    cc.write(tx, nextOidKey, nextOid + 1, store);
    int oid = nextOid;

    // Read customer balance (not used further)
    String custBalKey = "c_" + wid + "_" + did + "_" + cid + "_bal";
    cc.read(tx, custBalKey, store);

    // Generate order lines (5-15 items per order)
    int numItems = rand.nextInt(11) + 5;
    int totalAmount = 0;

    for (int olNum = 1; olNum <= numItems; olNum++) {
        int iid = rand.nextInt(TPCC_NUM_ITEMS) + 1;

        // Read item price
        String itemPriceKey = "item_" + iid + "_price";
        Object priceObj = cc.read(tx, itemPriceKey, store);
        int price = (priceObj instanceof Integer) ? (Integer) priceObj : 100;

        // Read and decrement stock
        String stockKey = "s_" + wid + "_" + iid + "_qty";
        Object stockQtyObj = cc.read(tx, stockKey, store);
        int stockQty = (stockQtyObj instanceof Integer) ? (Integer) stockQtyObj : TPCC_INITIAL_STOCK;

        int olQty = rand.nextInt(10) + 1;

        int newQty = stockQty - olQty;
        if (newQty < 10) {
            newQty += 91;  // Restock
        }
        cc.write(tx, stockKey, newQty, store);

        totalAmount += price * olQty;
    }

    // Write order record
    String orderKey = "o_" + wid + "_" + did + "_" + oid + "_total";
    cc.write(tx, orderKey, totalAmount, store);

    return true;
}

// Issues a payment transaction for TPC-C using the ConcurrencyControl interface.
static boolean tpccPayment(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();

    int wid = rand.nextInt(TPCC_NUM_WAREHOUSES) + 1;
    int did = rand.nextInt(TPCC_DISTRICTS_PER_WH) + 1;
    int cid = rand.nextInt(TPCC_CUSTOMERS_PER_DIST) + 1;

    int paymentAmount = rand.nextInt(499901) + 100; // 100-500000

    // Update warehouse YTD
    String whYtdKey = "w_" + wid + "_ytd";
    Object whYtdObj = cc.read(tx, whYtdKey, store);
    int whYtd = (whYtdObj instanceof Integer) ? (Integer) whYtdObj : 0;
    cc.write(tx, whYtdKey, whYtd + paymentAmount, store);

    // Update district YTD
    String distYtdKey = "d_" + wid + "_" + did + "_ytd";
    Object distYtdObj = cc.read(tx, distYtdKey, store);
    int distYtd = (distYtdObj instanceof Integer) ? (Integer) distYtdObj : 0;
    cc.write(tx, distYtdKey, distYtd + paymentAmount, store);

    // Update customer balance and YTD payment
    String custBalKey = "c_" + wid + "_" + did + "_" + cid + "_bal";
    String custYtdKey = "c_" + wid + "_" + did + "_" + cid + "_ytd";

    Object custBalObj = cc.read(tx, custBalKey, store);
    Object custYtdObj = cc.read(tx, custYtdKey, store);
    int custBal = (custBalObj instanceof Integer) ? (Integer) custBalObj : TPCC_INITIAL_BALANCE;
    int custYtd = (custYtdObj instanceof Integer) ? (Integer) custYtdObj : 0;

    cc.write(tx, custBalKey, custBal - paymentAmount, store);
    cc.write(tx, custYtdKey, custYtd + paymentAmount, store);

    return true;
}

// A workload thread for TPC-C: begins a transaction, executes new order or payment, then commits or rolls back.
static void wlTpcc(ConcurrencyControl cc, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    boolean success;

    Transaction txn = cc.begin(store);

    if (rand.nextBoolean()) {
        success = tpccNewOrder(cc, txn, store);
    } else {
        success = tpccPayment(cc, txn, store);
    }

    if (success) {
        cc.commit(txn, store);
    } else {
        cc.rollback(txn, store);
    }
}

