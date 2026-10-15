package com.mizzysbistro.pos;

import android.app.Application;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public final class PosStorageUnitTest {
    private static final String TEST_DATABASE = "mizzy_pos_robolectric_test.db";
    private static final String FIRST_SALE =
        "{\"id\":1001,\"time\":\"2026-10-15T10:00:00.000Z\",\"type\":\"Dine in\"," +
        "\"table\":4,\"payment\":\"Cash\",\"total\":100," +
        "\"items\":[{\"name\":\"Test dish\",\"price\":100,\"qty\":1}]}";
    private static final String SECOND_SALE =
        "{\"id\":1002,\"time\":\"2026-10-15T11:00:00.000Z\",\"type\":\"Takeaway\"," +
        "\"table\":null,\"payment\":\"Cash\",\"total\":50," +
        "\"items\":[{\"name\":\"Tea\",\"price\":50,\"qty\":1}]}";
    private static final String VALID_SNAPSHOT = snapshot("[" + FIRST_SALE + "]", 1002);

    private Context context;
    private PosStorage storage;

    private static String snapshot(String salesJson, int nextId) {
        return "{\"products\":[{\"id\":1,\"name\":\"Test dish\",\"cat\":\"Mains\",\"price\":100}]," +
            "\"sales\":" + salesJson + "," +
            "\"stock\":{\"1\":4},\"tickets\":[],\"tables\":{},\"nextId\":" + nextId + "}";
    }

    @Before
    public void setUp() {
        Application application = RuntimeEnvironment.getApplication();
        context = application;
        context.deleteDatabase(TEST_DATABASE);
        storage = new PosStorage(context, TEST_DATABASE);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        if (context != null) context.deleteDatabase(TEST_DATABASE);
    }

    @Test
    public void savesAndReloadsSnapshotAfterDatabaseReopen() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertSaleIds(storage.loadState(), 1001L);

        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void normalSaveDoesNotDeleteHistoricalSales() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertTrue(storage.saveState(snapshot("[" + SECOND_SALE + "]", 1003)));
        assertSaleIds(storage.loadState(), 1002L, 1001L);
    }

    @Test
    public void explicitRestoreReplacesSalesAtomically() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertTrue(storage.restoreState(snapshot("[" + SECOND_SALE + "]", 1003)));
        assertSaleIds(storage.loadState(), 1002L);
    }

    @Test
    public void migratesV1SnapshotWithoutLosingSales() throws JSONException {
        storage.close();
        storage = null;
        context.deleteDatabase(TEST_DATABASE);

        SQLiteDatabase oldDatabase =
            context.openOrCreateDatabase(TEST_DATABASE, Context.MODE_PRIVATE, null);
        oldDatabase.execSQL(
            "CREATE TABLE pos_state (id INTEGER NOT NULL PRIMARY KEY CHECK(id = 1), " +
            "payload TEXT NOT NULL, updated_at INTEGER NOT NULL)"
        );
        ContentValues oldRow = new ContentValues();
        oldRow.put("id", 1);
        oldRow.put("payload", VALID_SNAPSHOT);
        oldRow.put("updated_at", 111L);
        assertTrue(oldDatabase.insert("pos_state", null, oldRow) != -1L);
        oldDatabase.setVersion(1);
        oldDatabase.close();

        storage = new PosStorage(context, TEST_DATABASE);
        assertSaleIds(storage.loadState(), 1001L);

        SQLiteDatabase migrated = storage.getReadableDatabase();
        Cursor base = migrated.rawQuery("SELECT payload FROM pos_state WHERE id = 1", null);
        try {
            assertTrue(base.moveToFirst());
            JSONObject baseState = new JSONObject(base.getString(0));
            assertEquals(0, baseState.getJSONArray("sales").length());
        } finally {
            base.close();
        }
        Cursor saleRows = migrated.rawQuery("SELECT COUNT(*) FROM pos_sales", null);
        try {
            assertTrue(saleRows.moveToFirst());
            assertEquals(1, saleRows.getInt(0));
        } finally {
            saleRows.close();
        }
    }

    @Test
    public void invalidReplacementDoesNotOverwriteLastCommittedSnapshot() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertFalse(storage.saveState("{not valid json"));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void loadStateReturnsExplicitErrorWhenStoredPayloadIsCorrupt() {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        ContentValues corrupt = new ContentValues();
        corrupt.put("payload", "{not valid json");
        assertEquals(1, storage.getWritableDatabase().update(
            "pos_state", corrupt, "id = ?", new String[]{"1"}
        ));

        String result = storage.loadState();
        assertFalse(result.isEmpty());
        assertTrue(result.contains("_posStorageError"));
    }

    @Test
    public void missingSnapshotWithHistoricalSalesIsNotTreatedAsFirstLaunch() {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertEquals(1, storage.getWritableDatabase().delete(
            "pos_state", "id = ?", new String[]{"1"}
        ));

        String result = storage.loadState();
        assertFalse(result.isEmpty());
        assertTrue(result.contains("_posStorageError"));
    }

    @Test
    public void normalSaveCannotRewriteAnExistingSaleId() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        JSONObject changedRoot = new JSONObject(VALID_SNAPSHOT);
        changedRoot.getJSONArray("sales").getJSONObject(0).put("total", 1);
        assertFalse(storage.saveState(changedRoot.toString()));
        JSONObject saved = new JSONObject(storage.loadState());
        assertEquals(100.0, saved.getJSONArray("sales").getJSONObject(0).getDouble("total"), 0.0001);
    }

    @Test
    public void rejectsSaleWhenTotalDoesNotMatchItemSubtotal() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        JSONObject invalidSale = new JSONObject(SECOND_SALE);
        invalidSale.put("total", 51);
        assertFalse(storage.saveState(snapshot("[" + invalidSale + "]", 1003)));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void rejectsNextIdThatWouldReuseAnExistingSaleId() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertFalse(storage.saveState(snapshot("[" + FIRST_SALE + "]", 1001)));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void stockAdjustmentHistoryPersistsWithState() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONArray adjustments = new JSONArray();
        JSONObject event = new JSONObject();
        event.put("id", 1);
        event.put("time", "2026-10-15T10:01:00.000Z");
        event.put("productId", 1);
        event.put("productName", "Test dish");
        event.put("from", 4);
        event.put("to", 3);
        event.put("delta", -1);
        event.put("reason", "Count correction");
        event.put("source", "Manual");
        adjustments.put(event);
        root.put("stockAdjustments", adjustments);
        assertTrue(storage.saveState(root.toString()));
        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);
        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals(1, restored.getJSONArray("stockAdjustments").length());
        assertEquals(-1, restored.getJSONArray("stockAdjustments").getJSONObject(0).getInt("delta"));
    }

    @Test
    public void normalSaveCannotEraseStockAdjustmentHistory() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONArray adjustments = new JSONArray();
        JSONObject event = new JSONObject();
        event.put("id", 1);
        event.put("time", "2026-10-15T10:01:00.000Z");
        event.put("productId", 1);
        event.put("productName", "Test dish");
        event.put("from", 4);
        event.put("to", 3);
        event.put("delta", -1);
        event.put("reason", "Count correction");
        event.put("source", "Manual");
        adjustments.put(event);
        root.put("stockAdjustments", adjustments);
        assertTrue(storage.saveState(root.toString()));

        root.put("stockAdjustments", new JSONArray());
        assertFalse(storage.saveState(root.toString()));
        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals(1, restored.getJSONArray("stockAdjustments").length());
    }

    @Test
    public void rejectsInvalidStockAdjustmentHistory() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONArray adjustments = new JSONArray();
        JSONObject event = new JSONObject();
        event.put("id", 1);
        event.put("time", "2026-10-15T10:01:00.000Z");
        event.put("productId", 1);
        event.put("productName", "Test dish");
        event.put("from", 4);
        event.put("to", 3);
        event.put("delta", 3);
        event.put("reason", "Count correction");
        event.put("source", "Manual");
        adjustments.put(event);
        root.put("stockAdjustments", adjustments);
        assertFalse(storage.saveState(root.toString()));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void rejectsTableOccupancyWithoutMatchingDineInSale() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONObject tables = new JSONObject();
        tables.put("4", 9999);
        root.put("tables", tables);
        assertFalse(storage.saveState(root.toString()));
    }


    @Test
    public void savesAndReloadsMenuStockTicketsAndTableOccupancy() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        root.getJSONObject("stock").put("1", 7);
        JSONObject tables = new JSONObject();
        tables.put("4", 1001);
        root.put("tables", tables);
        JSONArray tickets = new JSONArray();
        JSONObject ticket = new JSONObject();
        ticket.put("id", 1001);
        ticket.put("time", "2026-10-15T10:00:00.000Z");
        ticket.put("type", "Dine in");
        ticket.put("table", 4);
        ticket.put("done", false);
        ticket.put("items", new JSONArray().put(
            new JSONObject().put("name", "Test dish").put("price", 100).put("qty", 1)
        ));
        tickets.put(ticket);
        root.put("tickets", tickets);

        assertTrue(storage.saveState(root.toString()));
        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);

        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals("Test dish", restored.getJSONArray("products").getJSONObject(0).getString("name"));
        assertEquals(7, restored.getJSONObject("stock").getInt("1"));
        assertEquals(1001, restored.getJSONObject("tables").getInt("4"));
        assertEquals(false, restored.getJSONArray("tickets").getJSONObject(0).getBoolean("done"));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void openCartAndCheckoutContextPersistAcrossDatabaseReopen() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONArray openCart = new JSONArray();
        openCart.put(new JSONObject().put("id", 1).put("qty", 2));
        root.put("openCart", openCart);
        root.put("orderType", "Dine in");
        root.put("tableNo", 4);
        root.put("payment", "GCash");
        root.put("customer", "Walk-in");
        assertTrue(storage.saveState(root.toString()));

        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);
        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals(2, restored.getJSONArray("openCart").getJSONObject(0).getInt("qty"));
        assertEquals("Dine in", restored.getString("orderType"));
        assertEquals(4, restored.getInt("tableNo"));
        assertEquals("GCash", restored.getString("payment"));
        assertEquals("Walk-in", restored.getString("customer"));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void invalidOpenCartCannotReplacePreviouslySavedDraft() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        root.put("openCart", new JSONArray().put(new JSONObject().put("id", 1).put("qty", 2)));
        assertTrue(storage.saveState(root.toString()));

        root.put("openCart", new JSONArray().put(new JSONObject().put("id", 9999).put("qty", 1)));
        assertFalse(storage.saveState(root.toString()));
        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals(1, restored.getJSONArray("openCart").length());
        assertEquals(1, restored.getJSONArray("openCart").getJSONObject(0).getInt("id"));
    }

    @Test
    public void openTableOrderPersistsAndOwnsTableOccupancy() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONObject openOrder = new JSONObject();
        openOrder.put("id", 1002);
        openOrder.put("table", 5);
        openOrder.put("createdAt", "2026-10-15T11:00:00.000Z");
        openOrder.put("updatedAt", "2026-10-15T11:05:00.000Z");
        openOrder.put("customer", "");
        JSONArray items = new JSONArray();
        items.put(new JSONObject().put("id", 1).put("name", "Test dish").put("price", 100).put("qty", 2));
        openOrder.put("items", items);
        root.put("openOrders", new JSONArray().put(openOrder));
        root.put("tables", new JSONObject().put("5", 1002));
        root.put("nextId", 1003);
        root.put("activeOpenOrderId", 1002);
        root.put("tableNo", 5);
        root.put("orderType", "Dine in");
        root.put("openCart", items);
        assertTrue(storage.saveState(root.toString()));

        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);
        JSONObject restored = new JSONObject(storage.loadState());
        assertEquals(1002, restored.getJSONArray("openOrders").getJSONObject(0).getInt("id"));
        assertEquals(1002, restored.getJSONObject("tables").getInt("5"));
        assertEquals(2, restored.getJSONArray("openOrders").getJSONObject(0).getJSONArray("items").getJSONObject(0).getInt("qty"));
    }

    @Test
    public void rejectsOpenTableOrderThatDoesNotOwnItsTable() throws JSONException {
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONObject openOrder = new JSONObject();
        openOrder.put("id", 1002);
        openOrder.put("table", 5);
        openOrder.put("createdAt", "2026-10-15T11:00:00.000Z");
        openOrder.put("items", new JSONArray().put(
            new JSONObject().put("id", 1).put("name", "Test dish").put("price", 100).put("qty", 1)
        ));
        root.put("openOrders", new JSONArray().put(openOrder));
        root.put("nextId", 1003);
        root.put("tables", new JSONObject());
        assertFalse(storage.saveState(root.toString()));
    }

    @Test
    public void rejectsSnapshotsMissingRequiredCollections() {
        assertFalse(storage.saveState("{\"products\":[],\"sales\":[]}"));
        assertEquals("", storage.loadState());
    }

    @Test
    public void rejectsOversizedSnapshot() {
        String oversized = "{\"products\":[],\"sales\":[],\"tickets\":[],\"stock\":{}," +
            "\"tables\":{},\"nextId\":1001,\"extra\":\"" +
            repeat('x', 10 * 1024 * 1024) + "\"}";
        assertFalse(storage.saveState(oversized));
        assertEquals("", storage.loadState());
    }

    private static void assertSaleIds(String state, long... expectedIds) throws JSONException {
        JSONObject root = new JSONObject(state);
        JSONArray sales = root.getJSONArray("sales");
        assertEquals(expectedIds.length, sales.length());
        for (int i = 0; i < expectedIds.length; i++) {
            assertEquals(expectedIds[i], sales.getJSONObject(i).getLong("id"));
        }
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(character);
        return builder.toString();
    }
}
