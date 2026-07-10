
// AuctionMark OLTP Benchmark (based on AuctionMark: OLTP Benchmark for Shared-Nothing DBMS)
//
// Access pattern characteristics:
//   - Seller-to-item ratio follows Zipfian (skew=1.2): a small number of "power sellers"
//     own a disproportionately large share of items, creating natural contention hotspots.
//   - Item access is temporally skewed (skew=1.5): items receive more activity as the
//     auction approaches its closing time. Items closer to closing are selected far more
//     frequently than items with distant end times.
//   - Buyer selection remains uniform across users.
//
// Constants:
//   AM_NUM_USERS = 1000, AM_NUM_ITEMS = 5000
//   Item statuses: 0=OPEN, 1=WAITING_FOR_PURCHASE, 2=CLOSED


// Initializes the AuctionMark dataset on the provided key-value store.
// Sellers are assigned to items via Zipfian distribution.
// Item end times are staggered (1 min to 24 hours) to enable temporal skew.
static void initAuctionMarkData(KVStore store) {
    java.util.Random rand = new java.util.Random(42);
    long now = System.currentTimeMillis();

    for (int uid = 1; uid <= AM_NUM_USERS; uid++) {
        String keyPrefix = "u_" + uid + "_";
        store.insert(keyPrefix + "rating", 0);
        store.insert(keyPrefix + "balance", 0.0);
        store.insert(keyPrefix + "created", now);
        store.insert(keyPrefix + "r_id", rand.nextInt(62) + 1);
        for (int i = 0; i < 8; i++) {
            store.insert(keyPrefix + "sattr" + i, "attr_val_" + rand.nextInt(100));
        }
    }

    for (int iid = 1; iid <= AM_NUM_ITEMS; iid++) {
        int seller = zipfianSample(rand, AM_NUM_USERS); // Zipfian: few sellers own most items
        long endTime = now + 60_000L + (long)(rand.nextDouble() * 86340000L); // staggered end times

        String itemKey = "i_" + iid + "_" + seller;
        double price = rand.nextDouble() * 100 + 1;
        store.insert(itemKey + "_c_id", rand.nextInt(100) + 1);
        store.insert(itemKey + "_name", "Item " + iid);
        store.insert(itemKey + "_desc", "Description for " + iid);
        store.insert(itemKey + "_price", price);
        store.insert(itemKey + "_curr_price", price);
        store.insert(itemKey + "_num_bids", 0);
        store.insert(itemKey + "_status", 0);
        store.insert(itemKey + "_start", now);
        store.insert(itemKey + "_end", endTime);
    }
}

// 3.1 New User (Frequency: 5%) - Creates a new USER record.
static boolean amNewUser(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int u_id = rand.nextInt(1000000) + 1000;
    int u_r_id = rand.nextInt(62) + 1;
    String keyPrefix = "u_" + u_id + "_";

    cc.write(tx, keyPrefix + "rating", 0, store);
    cc.write(tx, keyPrefix + "balance", 0.0, store);
    cc.write(tx, keyPrefix + "created", System.currentTimeMillis(), store);
    cc.write(tx, keyPrefix + "r_id", u_r_id, store);
    for (int i = 0; i < 8; i++) {
        cc.write(tx, keyPrefix + "sattr" + i, "attr_val_" + rand.nextInt(100), store);
    }
    return true;
}

// 3.2 New Item (Frequency: 10%) - Insert a new ITEM record for a user.
// Seller selected via Zipfian distribution.
static boolean amNewItem(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int u_id = zipfianSample(rand, AM_NUM_USERS); // Zipfian seller
    int i_id = rand.nextInt(1000000) + 1;
    int c_id = rand.nextInt(100) + 1;
    double initial_price = rand.nextDouble() * 100 + 1;

    String itemKey = "i_" + i_id + "_" + u_id;
    cc.write(tx, itemKey + "_c_id", c_id, store);
    cc.write(tx, itemKey + "_name", "Item " + i_id, store);
    cc.write(tx, itemKey + "_desc", "Description for " + i_id, store);
    cc.write(tx, itemKey + "_price", initial_price, store);
    cc.write(tx, itemKey + "_curr_price", initial_price, store);
    cc.write(tx, itemKey + "_num_bids", 0, store);
    cc.write(tx, itemKey + "_status", 0, store);
    cc.write(tx, itemKey + "_start", System.currentTimeMillis(), store);
    cc.write(tx, itemKey + "_end", System.currentTimeMillis() + 86400000L, store);

    String userBalKey = "u_" + u_id + "_balance";
    Object balObj = cc.read(tx, userBalKey, store);
    double bal = (balObj instanceof Double) ? (Double) balObj : 0.0;
    cc.write(tx, userBalKey, bal - 1.0, store);
    return true;
}

// 3.3 New Bid (Frequency: 18%) - A buyer enters a new bid for an open item.
// Item selected with temporal skew (items near closing get more bids).
static boolean amNewBid(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    // i_id and u_id selected via temporal skew + Zipfian seller from precomputed tables
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    int buyer_id = rand.nextInt(AM_NUM_USERS) + 1;
    if (u_id == buyer_id) return false;

    String itemKey = "i_" + i_id + "_" + u_id;
    Object statusObj = cc.read(tx, itemKey + "_status", store);
    int status = (statusObj instanceof Integer) ? (Integer) statusObj : -1;
    if (status != 0) return false;

    double bid = rand.nextDouble() * 50 + 10;
    double maxBid = bid + 10.0;

    String itemMaxBidKey = "imb_" + i_id + "_" + u_id;
    Object currentMaxBidObj = cc.read(tx, itemMaxBidKey + "_max_bid", store);
    double currentMaxBid = (currentMaxBidObj instanceof Double) ? (Double) currentMaxBidObj : 0.0;
    if (bid <= currentMaxBid) return false;

    Object numBidsObj = cc.read(tx, itemKey + "_num_bids", store);
    int numBids = (numBidsObj instanceof Integer) ? (Integer) numBidsObj : 0;
    cc.write(tx, itemKey + "_num_bids", numBids + 1, store);

    int ib_id = numBids + 1;
    String bidKey = "ib_" + i_id + "_" + u_id + "_" + ib_id;
    cc.write(tx, bidKey + "_buyer", buyer_id, store);
    cc.write(tx, bidKey + "_bid", bid, store);
    cc.write(tx, bidKey + "_max", maxBid, store);
    cc.write(tx, bidKey + "_date", System.currentTimeMillis(), store);

    cc.write(tx, itemMaxBidKey + "_ib_id", ib_id, store);
    cc.write(tx, itemMaxBidKey + "_max_bid", maxBid, store);
    return true;
}

// 3.4 New Comment (Frequency: 2%) - A buyer posts a question about a seller's item.
// Item selected with temporal skew.
static boolean amNewComment(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    int buyer_id = rand.nextInt(AM_NUM_USERS) + 1;

    String commentCounterKey = "cnt_ic_" + i_id + "_" + u_id;
    Object cntObj = cc.read(tx, commentCounterKey, store);
    int ic_id = (cntObj instanceof Integer) ? (Integer) cntObj + 1 : 1;
    cc.write(tx, commentCounterKey, ic_id, store);

    String commentKey = "ic_" + i_id + "_" + u_id + "_" + ic_id;
    cc.write(tx, commentKey + "_buyer", buyer_id, store);
    cc.write(tx, commentKey + "_question", "Question about item?", store);
    cc.write(tx, commentKey + "_date", System.currentTimeMillis(), store);
    return true;
}

// 3.5 New Comment Response (Frequency: 1%) - The seller responds to an open question.
// Item selected with temporal skew.
static boolean amNewCommentResponse(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    int ic_id = rand.nextInt(5) + 1;

    String commentKey = "ic_" + i_id + "_" + u_id + "_" + ic_id;
    if (cc.read(tx, commentKey + "_question", store) == null) return false;
    cc.write(tx, commentKey + "_response", "This is the response.", store);
    return true;
}

// 3.6 New Purchase (Frequency: 2%) - The buyer purchases a previously won auction item.
// Item selected with temporal skew.
static boolean amNewPurchase(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);

    String itemKey = "i_" + i_id + "_" + u_id;
    Object statusObj = cc.read(tx, itemKey + "_status", store);
    int status = (statusObj instanceof Integer) ? (Integer) statusObj : -1;
    if (status != 1) return false;

    String itemMaxBidKey = "imb_" + i_id + "_" + u_id;
    Object winBidIdObj = cc.read(tx, itemMaxBidKey + "_ib_id", store);
    int ib_id = (winBidIdObj instanceof Integer) ? (Integer) winBidIdObj : -1;

    String bidKey = "ib_" + i_id + "_" + u_id + "_" + ib_id;
    cc.read(tx, bidKey + "_buyer", store);

    int ip_id = rand.nextInt(10000);
    String purchaseKey = "ip_" + ip_id;
    cc.write(tx, purchaseKey + "_ib_id", ib_id, store);
    cc.write(tx, purchaseKey + "_i_id", i_id, store);
    cc.write(tx, purchaseKey + "_u_id", u_id, store);
    cc.write(tx, purchaseKey + "_date", System.currentTimeMillis(), store);

    cc.write(tx, itemKey + "_status", 2, store);
    return true;
}

// 3.7 New Feedback (Frequency: 3%) - The buyer adds seller feedback for a won auction.
// Item selected with temporal skew.
static boolean amNewFeedback(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    int buyer_id = rand.nextInt(AM_NUM_USERS) + 1;
    int rating = rand.nextInt(3) - 1; // -1, 0, or 1

    int if_id = rand.nextInt(10000);
    String feedbackKey = "if_" + i_id + "_" + u_id + "_" + if_id;
    cc.write(tx, feedbackKey + "_buyer", buyer_id, store);
    cc.write(tx, feedbackKey + "_rating", rating, store);
    cc.write(tx, feedbackKey + "_comment", "Great seller", store);
    cc.write(tx, feedbackKey + "_date", System.currentTimeMillis(), store);

    String userKey = "u_" + u_id;
    Object curRatingObj = cc.read(tx, userKey + "_rating", store);
    int curRating = (curRatingObj instanceof Integer) ? (Integer) curRatingObj : 0;
    cc.write(tx, userKey + "_rating", curRating + rating, store);
    return true;
}

// 3.8 Get Item (Frequency: 40%) - Read-only: get item information.
// Item selected with temporal skew.
static boolean amGetItem(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    String itemKey = "i_" + i_id + "_" + u_id;

    cc.read(tx, itemKey + "_name", store);
    cc.read(tx, itemKey + "_price", store);
    cc.read(tx, itemKey + "_desc", store);
    cc.read(tx, itemKey + "_end", store);
    return true;
}

// 3.9 Update Item (Frequency: 2%) - Modify item description if still open.
// Item selected with temporal skew.
static boolean amUpdateItem(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int i_id = temporalItemSelect(rand);
    int u_id = sellerForItem(i_id);
    String itemKey = "i_" + i_id + "_" + u_id;

    Object statusObj = cc.read(tx, itemKey + "_status", store);
    int status = (statusObj instanceof Integer) ? (Integer) statusObj : -1;
    if (status == 0) {
        cc.write(tx, itemKey + "_desc", "Updated Description " + System.currentTimeMillis(), store);
        return true;
    }
    return false;
}

// 3.10 Check Winning Bids (Periodic) - Checks for ended auctions.
// Item selection biased toward soon-closing items via temporal skew.
static java.util.List<String> amCheckWinningBids(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.List<String> dueItems = new java.util.ArrayList<>();
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    long now = System.currentTimeMillis();

    for (int i = 0; i < 10; i++) {
        int i_id = temporalItemSelect(rand);
        int u_id = sellerForItem(i_id);
        String itemKey = "i_" + i_id + "_" + u_id;

        Object endObj = cc.read(tx, itemKey + "_end", store);
        long endTime = (endObj instanceof Long) ? (Long) endObj : Long.MAX_VALUE;

        Object statusObj = cc.read(tx, itemKey + "_status", store);
        int status = (statusObj instanceof Integer) ? (Integer) statusObj : -1;

        if (status == 0 && endTime < now) {
            dueItems.add(itemKey);
        }
    }
    return dueItems;
}

// 3.11 Post Auction (Triggered by 3.10) - Post-processing of closed auction items.
static boolean amPostAuction(ConcurrencyControl cc, Transaction tx, KVStore store, java.util.List<String> dueItems) {
    if (dueItems == null || dueItems.isEmpty()) return true;

    for (String itemKey : dueItems) {
        String[] parts = itemKey.split("_");
        int i_id = Integer.parseInt(parts[1]);
        int u_id = Integer.parseInt(parts[2]);

        String maxBidKey = "imb_" + i_id + "_" + u_id + "_ib_id";
        Object bidIdObj = cc.read(tx, maxBidKey, store);

        if (bidIdObj == null) {
            cc.write(tx, itemKey + "_status", 2, store);
        } else {
            cc.write(tx, itemKey + "_status", 1, store);
            int buyer_id = 999;
            String uiKey = "ui_" + buyer_id + "_" + i_id;
            cc.write(tx, uiKey + "_created", System.currentTimeMillis(), store);
        }
    }
    return true;
}

// 3.12 Get Comment (Frequency: 2%) - Read-only: get all item comments of a seller.
// Seller selected via Zipfian; items via temporal skew.
static boolean amGetComment(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int u_id = zipfianSample(rand, AM_NUM_USERS);

    for (int k = 1; k <= 3; k++) {
        int i_id = temporalItemSelect(rand);
        String commentKey = "ic_" + i_id + "_" + u_id + "_" + k;
        cc.read(tx, commentKey + "_question", store);
    }
    return true;
}

// 3.13 Get User Info (Frequency: 10%) - Read-only: get user items and feedback.
// User selected via Zipfian; items via temporal skew.
static boolean amGetUserInfo(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int u_id = zipfianSample(rand, AM_NUM_USERS);
    String userKey = "u_" + u_id;

    cc.read(tx, userKey + "_balance", store);
    cc.read(tx, userKey + "_rating", store);

    for (int k = 0; k < 5; k++) {
        int i_id = temporalItemSelect(rand);
        cc.read(tx, "i_" + i_id + "_" + u_id + "_status", store);
    }
    return true;
}

// 3.14 Get Watched Items (Frequency: 5%) - Read-only: gets all watched items of a buyer.
// Items selected with temporal skew (users watch items near closing).
static boolean amGetWatchedItems(ConcurrencyControl cc, Transaction tx, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    int u_id = rand.nextInt(AM_NUM_USERS) + 1;

    for (int k = 0; k < 3; k++) {
        int i_id = temporalItemSelect(rand);
        int seller_id = sellerForItem(i_id);
        cc.read(tx, "i_" + i_id + "_" + seller_id + "_curr_price", store);
    }
    return true;
}

// AuctionMark workload driver: begins a transaction, selects a transaction type
// based on the defined frequency distribution, then commits or rolls back.
static void wlAuctionMark(ConcurrencyControl cc, KVStore store) {
    java.util.Random rand = java.util.concurrent.ThreadLocalRandom.current();
    boolean success;

    Transaction txn = cc.begin(store);

    int roll = rand.nextInt(100) + 1;

    if (roll <= FREQ_NEW_USER) {
        success = amNewUser(cc, txn, store);
    } else if (roll <= FREQ_NEW_USER + FREQ_NEW_ITEM) {
        success = amNewItem(cc, txn, store);
    } else if (roll <= 15 + FREQ_NEW_BID) {
        success = amNewBid(cc, txn, store);
    } else if (roll <= 33 + FREQ_NEW_COMMENT) {
        success = amNewComment(cc, txn, store);
    } else if (roll <= 35 + FREQ_NEW_COMMENT_RESPONSE) {
        success = amNewCommentResponse(cc, txn, store);
    } else if (roll <= 36 + FREQ_NEW_PURCHASE) {
        success = amNewPurchase(cc, txn, store);
    } else if (roll <= 38 + FREQ_NEW_FEEDBACK) {
        success = amNewFeedback(cc, txn, store);
    } else if (roll <= 41 + FREQ_GET_ITEM) {
        success = amGetItem(cc, txn, store);
    } else if (roll <= 81 + FREQ_UPDATE_ITEM) {
        success = amUpdateItem(cc, txn, store);
    } else if (roll <= 83 + FREQ_GET_COMMENT) {
        success = amGetComment(cc, txn, store);
    } else if (roll <= 85 + FREQ_GET_USER_INFO) {
        success = amGetUserInfo(cc, txn, store);
    } else {
        success = amGetWatchedItems(cc, txn, store);
    }

    // Periodic maintenance: check for ended auctions (rare trigger)
    if (rand.nextInt(1000) == 0) {
        java.util.List<String> due = amCheckWinningBids(cc, txn, store);
        amPostAuction(cc, txn, store, due);
    }

    if (success) {
        cc.commit(txn, store);
    } else {
        cc.rollback(txn, store);
    }
}
