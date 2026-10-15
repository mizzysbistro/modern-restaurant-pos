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
        JSONArray items = sale.optJSONArray("items");
        if (saleId < 1 || !(rawTotal instanceof Number) ||
            !Double.isFinite(((Number) rawTotal).doubleValue()) ||
            ((Number) rawTotal).doubleValue() < 0 ||
            soldAt.isEmpty() || soldAt.length() > 100 ||
            payment.isEmpty() || payment.length() > 80 ||
            !("Dine in".equals(type) || "Takeaway".equals(type)) ||
            items == null || items.length() > 500) {
            return false;
        }

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
