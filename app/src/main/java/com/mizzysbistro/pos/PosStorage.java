package com.mizzysbistro.pos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Local POS persistence. The WebView keeps its legacy snapshot API while
 * completed sales are stored separately in an indexed SQLite table.
 *
 * Every state write and associated sale upsert occurs in one transaction.
 * The schema upgrade migrates v1 sales without intentionally deleting records.
 */
public final class PosStorage extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "mizzy_pos.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_STATE = "pos_state";
    private static final String TABLE_SALES = "pos_sales";
    private static final String LOAD_ERROR = "{\"_posStorageError\":true}";
    private static final int STATE_ROW_ID = 1;
    private static final int MAX_STATE_CHARS = 10 * 1024 * 1024;

    public PosStorage(Context context) {
        this(context, DATABASE_NAME);
    }

    // Package-private database-name override keeps automated tests isolated
    // from the real application's database.
    PosStorage(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createStateTable(db);
        createSalesTable(db);
    }

    private void createStateTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE_STATE + " (" +
            "id INTEGER NOT NULL PRIMARY KEY CHECK(id = " + STATE_ROW_ID + "), " +
            "payload TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL)"
        );
    }

    private void createSalesTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE_SALES + " (" +
            "sale_id INTEGER NOT NULL PRIMARY KEY, " +
            "sold_at TEXT NOT NULL, " +
            "total REAL NOT NULL CHECK(total >= 0), " +
            "payment TEXT NOT NULL, " +
            "sale_type TEXT NOT NULL, " +
            "payload TEXT NOT NULL)"
        );
        db.execSQL("CREATE INDEX idx_pos_sales_sold_at ON " + TABLE_SALES + "(sold_at)");
        db.execSQL("CREATE INDEX idx_pos_sales_payment ON " + TABLE_SALES + "(payment)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion > newVersion) {
            throw new IllegalStateException("Database downgrade is not supported.");
        }
        if (oldVersion < 2) {
            createSalesTable(db);
            migrateV1SalesIntoTable(db);
        }
    }

    /**
     * Migrate the old v1 snapshot inside SQLiteOpenHelper's schema-upgrade
     * transaction. If parsing/upserting fails, throw to roll back the upgrade.
     */
    private void migrateV1SalesIntoTable(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.query(
                TABLE_STATE, new String[]{"payload"}, "id = ?",
                new String[]{String.valueOf(STATE_ROW_ID)}, null, null, null
            );
            if (!cursor.moveToFirst()) return;

            String oldPayload = cursor.getString(0);
            JSONObject root = new JSONObject(oldPayload);
            JSONArray sales = root.optJSONArray("sales");
            if (sales == null) {
                throw new SQLiteException("Existing POS state has no sales collection.");
            }

            for (int i = 0; i < sales.length(); i++) {
                JSONObject sale = sales.optJSONObject(i);
                if (sale == null || !upsertSale(db, sale)) {
                    throw new SQLiteException("Could not migrate an existing POS sale.");
                }
            }

            // Keep menu, inventory, tickets and tables in the snapshot; sales
            // now load from pos_sales so this row no longer grows with history.
            root.put("sales", new JSONArray());
            ContentValues values = new ContentValues();
            values.put("payload", root.toString());
            values.put("updated_at", System.currentTimeMillis());
            int changed = db.update(
                TABLE_STATE, values, "id = ?",
                new String[]{String.valueOf(STATE_ROW_ID)}
            );
            if (changed != 1) {
                throw new SQLiteException("Could not finalize the POS data migration.");
            }
        } catch (JSONException error) {
            throw new SQLiteException("Existing POS data could not be safely migrated.", error);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Returns current POS state with completed sales reconstructed from SQLite. */
    @JavascriptInterface
    public synchronized String loadState() {
        SQLiteDatabase db = null;
        Cursor stateCursor = null;
        Cursor salesCursor = null;
        try {
            db = getReadableDatabase();
            stateCursor = db.query(
                TABLE_STATE, new String[]{"payload"}, "id = ?",
                new String[]{String.valueOf(STATE_ROW_ID)}, null, null, null
            );
            if (!stateCursor.moveToFirst()) return hasStoredSales(db) ? LOAD_ERROR : "";

            JSONObject root = new JSONObject(stateCursor.getString(0));
            JSONArray sales = new JSONArray();
            salesCursor = db.query(
                TABLE_SALES, new String[]{"payload"}, null, null,
                null, null, "sale_id DESC"
            );
            while (salesCursor.moveToNext()) {
                sales.put(new JSONObject(salesCursor.getString(0)));
            }
            root.put("sales", sales);
            return root.toString();
        } catch (RuntimeException | JSONException error) {
            // A read error is not a first-launch state. The UI recognizes this
            // sentinel and refuses writes so defaults cannot replace saved data.
            return LOAD_ERROR;
        } finally {
            if (salesCursor != null) salesCursor.close();
            if (stateCursor != null) stateCursor.close();
        }
    }

    /** True if normalized sale history exists without a readable base snapshot. */
    private boolean hasStoredSales(SQLiteDatabase db) {
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT 1 FROM " + TABLE_SALES + " LIMIT 1", null);
            return cursor.moveToFirst();
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Normal POS save: upsert current sales without deleting any historical row.
     * Returns false when validation or disk persistence fails.
     */
    @JavascriptInterface
    public synchronized boolean saveState(String payload) {
        return persistState(payload, false);
    }

    /**
     * Explicit backup restore: replace sales only after validated user-confirmed
     * restore has selected this operation in the UI.
     */
    @JavascriptInterface
    public synchronized boolean restoreState(String payload) {
        return persistState(payload, true);
    }

    private boolean persistState(String payload, boolean replaceSales) {
        if (!isValidSnapshot(payload)) return false;

        SQLiteDatabase db = null;
        try {
            JSONObject root = new JSONObject(payload);
            JSONArray sales = root.getJSONArray("sales");
            Set<Long> seenSaleIds = new HashSet<>();
            for (int i = 0; i < sales.length(); i++) {
                JSONObject sale = sales.optJSONObject(i);
                if (sale == null) return false;
                long saleId = saleId(sale);
                if (saleId < 1 || !seenSaleIds.add(saleId)) return false;
            }

            // Keep the per-sale rows out of the base snapshot to avoid storing
            // the full sales history twice on every update.
            JSONObject baseState = new JSONObject(root.toString());
            baseState.put("sales", new JSONArray());

            db = getWritableDatabase();
            db.beginTransaction();

            if (!replaceSales &&
                !stockAdjustmentHistoryPreserved(db, root.optJSONArray("stockAdjustments"))) {
                return false;
            }

            ContentValues stateValues = new ContentValues();
            stateValues.put("id", STATE_ROW_ID);
            stateValues.put("payload", baseState.toString());
            stateValues.put("updated_at", System.currentTimeMillis());
            long stateResult = db.insertWithOnConflict(
                TABLE_STATE, null, stateValues, SQLiteDatabase.CONFLICT_REPLACE
            );
            if (stateResult == -1L) return false;

            if (replaceSales) {
                db.delete(TABLE_SALES, null, null);
            }
            for (int i = 0; i < sales.length(); i++) {
                JSONObject sale = sales.getJSONObject(i);
                // A routine save may replay an existing sale, but must never
                // rewrite it when an order ID is accidentally reused.
                if (!replaceSales && !saleMatchesExisting(db, sale)) return false;
                if (!upsertSale(db, sale)) return false;
            }

            db.setTransactionSuccessful();
            return true;
        } catch (RuntimeException | JSONException error) {
            return false;
        } finally {
            if (db != null && db.inTransaction()) db.endTransaction();
        }
    }

    private long saleId(JSONObject sale) {
        Object rawId = sale.opt("id");
        if (!(rawId instanceof Number)) return -1L;
        double value = ((Number) rawId).doubleValue();
        if (!Double.isFinite(value) || value < 1 ||
            value != Math.rint(value) || value > Long.MAX_VALUE) {
            return -1L;
        }
        return (long) value;
    }

    /**
     * Existing completed sale rows are immutable during normal state saves.
     * Explicit restore is the only path that may replace historical records.
     */
    private boolean saleMatchesExisting(SQLiteDatabase db, JSONObject sale) throws JSONException {
        Cursor cursor = null;
        try {
            cursor = db.query(
                TABLE_SALES, new String[]{"payload"}, "sale_id = ?",
                new String[]{String.valueOf(saleId(sale))}, null, null, null
            );
            if (!cursor.moveToFirst()) return true;
            JSONObject existing = new JSONObject(cursor.getString(0));
            return jsonValuesEqual(existing, sale);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Compare parsed JSON recursively; Android's org.json lacks JSONObject.similar(). */
    private boolean jsonValuesEqual(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;

        if (left instanceof JSONObject && right instanceof JSONObject) {
            JSONObject leftObject = (JSONObject) left;
            JSONObject rightObject = (JSONObject) right;
            if (leftObject.length() != rightObject.length()) return false;
            java.util.Iterator<String> keys = leftObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!rightObject.has(key) ||
                    !jsonValuesEqual(leftObject.opt(key), rightObject.opt(key))) {
                    return false;
                }
            }
            return true;
        }

        if (left instanceof JSONArray && right instanceof JSONArray) {
            JSONArray leftArray = (JSONArray) left;
            JSONArray rightArray = (JSONArray) right;
            if (leftArray.length() != rightArray.length()) return false;
            for (int i = 0; i < leftArray.length(); i++) {
                if (!jsonValuesEqual(leftArray.opt(i), rightArray.opt(i))) return false;
            }
            return true;
        }

        if (left instanceof Number && right instanceof Number) {
            double leftNumber = ((Number) left).doubleValue();
            double rightNumber = ((Number) right).doubleValue();
            return Double.isFinite(leftNumber) && Double.compare(leftNumber, rightNumber) == 0;
        }
        return left.equals(right);
    }

    private boolean upsertSale(SQLiteDatabase db, JSONObject sale) {
        long saleId = saleId(sale);
        Object rawTotal = sale.opt("total");
        String soldAt = sale.optString("time", "");
        String payment = sale.optString("payment", "");
        String type = sale.optString("type", "");
        Object rawTable = sale.opt("table");
        JSONArray items = sale.optJSONArray("items");
        if (saleId < 1 || !(rawTotal instanceof Number) ||
            !Double.isFinite(((Number) rawTotal).doubleValue()) ||
            ((Number) rawTotal).doubleValue() < 0 ||
            ((Number) rawTotal).doubleValue() > 1_000_000_000d ||
            soldAt.isEmpty() || soldAt.length() > 100 ||
            payment.trim().isEmpty() || payment.length() > 80 ||
            !("Dine in".equals(type) || "Takeaway".equals(type)) ||
            items == null || items.length() < 1 || items.length() > 500) {
            return false;
        }

        if ("Dine in".equals(type)) {
            if (!(rawTable instanceof Number)) return false;
            double table = ((Number) rawTable).doubleValue();
            if (!Double.isFinite(table) || table != Math.rint(table) || table < 1 || table > 12) {
                return false;
            }
        } else if (rawTable != null && rawTable != JSONObject.NULL) {
            return false;
        }

        double itemsTotal = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) return false;
            String name = item.optString("name", "");
            Object rawPrice = item.opt("price");
            Object rawQuantity = item.opt("qty");
            if (name.trim().isEmpty() || name.length() > 200 ||
                !(rawPrice instanceof Number) || !(rawQuantity instanceof Number)) {
                return false;
            }
            double price = ((Number) rawPrice).doubleValue();
            double quantity = ((Number) rawQuantity).doubleValue();
            if (!Double.isFinite(price) || price < 0 || price > 100_000_000d ||
                !Double.isFinite(quantity) || quantity < 1 ||
                quantity != Math.rint(quantity) || quantity > 100_000) {
                return false;
            }
            itemsTotal += price * quantity;
            if (!Double.isFinite(itemsTotal) || itemsTotal > 1_000_000_000d) return false;
        }
        long totalCents = Math.round(((Number) rawTotal).doubleValue() * 100d);
        long itemsCents = Math.round(itemsTotal * 100d);
        if (totalCents != itemsCents) return false;

        ContentValues values = new ContentValues();
        values.put("sale_id", saleId);
        values.put("sold_at", soldAt);
        values.put("total", ((Number) rawTotal).doubleValue());
        values.put("payment", payment);
        values.put("sale_type", type);
        values.put("payload", sale.toString());
        long result = db.insertWithOnConflict(
            TABLE_SALES, null, values, SQLiteDatabase.CONFLICT_REPLACE
        );
        return result != -1L;
    }

    /**
     * During routine saves, prior stock audit entries must remain unchanged.
     * At the 10,000-entry cap only an oldest prefix may roll off when newer
     * entries are appended. Explicit backup restore is handled separately.
     */
    private boolean stockAdjustmentHistoryPreserved(SQLiteDatabase db, JSONArray incoming)
        throws JSONException {
        Cursor cursor = null;
        try {
            cursor = db.query(
                TABLE_STATE, new String[]{"payload"}, "id = ?",
                new String[]{String.valueOf(STATE_ROW_ID)}, null, null, null
            );
            if (!cursor.moveToFirst()) return true;
            JSONObject oldRoot = new JSONObject(cursor.getString(0));
            JSONArray previous = oldRoot.optJSONArray("stockAdjustments");
            int oldLength = previous == null ? 0 : previous.length();
            int newLength = incoming == null ? 0 : incoming.length();
            if (oldLength == 0) return true;
            if (incoming == null || newLength < oldLength && oldLength < 10000) return false;

            long maxPriorId = 0;
            for (int i = 0; i < oldLength; i++) {
                JSONObject event = previous.optJSONObject(i);
                if (event == null) return false;
                maxPriorId = Math.max(maxPriorId, saleId(event));
            }

            for (int startIndex = 0; startIndex <= oldLength; startIndex++) {
                if (startIndex > 0 && oldLength != 10000) continue;
                int preserved = oldLength - startIndex;
                if (newLength < preserved) continue;
                if (startIndex > 0 && newLength != 10000) continue;
                boolean matches = true;
                for (int i = 0; i < preserved; i++) {
                    if (!jsonValuesEqual(previous.opt(i + startIndex), incoming.opt(i))) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) continue;

                long priorAddedId = maxPriorId;
                for (int i = preserved; i < newLength; i++) {
                    JSONObject event = incoming.optJSONObject(i);
                    if (event == null) { matches = false; break; }
                    long id = saleId(event);
                    if (id <= priorAddedId) { matches = false; break; }
                    priorAddedId = id;
                }
                if (matches && (startIndex == 0 || newLength > preserved)) return true;
            }
            return false;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** Validate optional append-style stock adjustment history; absent is a legacy snapshot. */
    private boolean isValidStockAdjustments(JSONObject root) {
        if (!root.has("stockAdjustments")) return true;
        JSONArray adjustments = root.optJSONArray("stockAdjustments");
        if (adjustments == null || adjustments.length() > 10000) return false;
        Set<Long> seenIds = new HashSet<>();
        for (int i = 0; i < adjustments.length(); i++) {
            JSONObject entry = adjustments.optJSONObject(i);
            if (entry == null) return false;
            long id = saleId(entry);
            Object rawProductId = entry.opt("productId");
            Object rawFrom = entry.opt("from");
            Object rawTo = entry.opt("to");
            Object rawDelta = entry.opt("delta");
            if (id < 1 || !seenIds.add(id) ||
                !(rawProductId instanceof Number) ||
                !(rawFrom instanceof Number) || !(rawTo instanceof Number) ||
                !(rawDelta instanceof Number)) return false;
            double productId = ((Number) rawProductId).doubleValue();
            double from = ((Number) rawFrom).doubleValue();
            double to = ((Number) rawTo).doubleValue();
            double delta = ((Number) rawDelta).doubleValue();
            String time = entry.optString("time", "");
            String name = entry.optString("productName", "");
            String reason = entry.optString("reason", "");
            String source = entry.optString("source", "");
            if (!Double.isFinite(productId) || productId < 1 ||
                productId != Math.rint(productId) || productId > Long.MAX_VALUE ||
                !Double.isFinite(from) || from < 0 || from > 100_000_000d ||
                from != Math.rint(from) ||
                !Double.isFinite(to) || to < 0 || to > 100_000_000d ||
                to != Math.rint(to) ||
                !Double.isFinite(delta) || delta != Math.rint(delta) ||
                delta != to - from ||
                time.isEmpty() || time.length() > 100 ||
                name.trim().isEmpty() || name.length() > 200 ||
                reason.trim().isEmpty() || reason.length() > 160 ||
                !("Manual".equals(source) || "Sale".equals(source))) {
                return false;
            }
        }
        return true;
    }

    private boolean isValidSnapshot(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > MAX_STATE_CHARS) {
            return false;
        }
        try {
            JSONObject root = new JSONObject(payload);
            JSONArray products = root.optJSONArray("products");
            JSONArray sales = root.optJSONArray("sales");
            JSONArray tickets = root.optJSONArray("tickets");
            JSONObject stock = root.optJSONObject("stock");
            JSONObject tables = root.optJSONObject("tables");
            Object nextId = root.opt("nextId");
            if (products == null || sales == null || tickets == null ||
                stock == null || tables == null || !(nextId instanceof Number)) {
                return false;
            }
            if (!isValidStockAdjustments(root)) return false;
            double nextIdValue = ((Number) nextId).doubleValue();
            if (!Double.isFinite(nextIdValue) || nextIdValue < 1 ||
                nextIdValue != Math.rint(nextIdValue) || nextIdValue > Long.MAX_VALUE) {
                return false;
            }
            long nextIdLong = (long) nextIdValue;
            Set<Long> seenSaleIds = new HashSet<>();
            for (int i = 0; i < sales.length(); i++) {
                JSONObject sale = sales.optJSONObject(i);
                if (sale == null) return false;
                long id = saleId(sale);
                if (id < 1 || id >= nextIdLong || !seenSaleIds.add(id)) return false;
            }
            return true;
        } catch (JSONException error) {
            return false;
        }
    }
}
