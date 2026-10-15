package com.mizzysbistro.pos;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class PosStorageInstrumentedTest {
    private static final String TEST_DATABASE = "mizzy_pos_storage_test.db";
    private static final String SALE =
        "{\"id\":1001,\"time\":\"2026-10-15T10:00:00.000Z\",\"type\":\"Dine in\"," +
        "\"table\":4,\"payment\":\"Cash\",\"total\":100," +
        "\"items\":[{\"name\":\"Test dish\",\"price\":100,\"qty\":1}]}";
    private static final String VALID_SNAPSHOT = snapshot(SALE, 1002);

    private Context context;
    private PosStorage storage;

    private static String snapshot(String saleJson, int nextId) {
        return "{\"products\":[{\"id\":1,\"name\":\"Test dish\",\"cat\":\"Mains\",\"price\":100}]," +
            "\"sales\":[" + saleJson + "],\"stock\":{\"1\":4},\"tickets\":[],\"tables\":{}," +
            "\"nextId\":" + nextId + "}";
    }

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(TEST_DATABASE);
        storage = new PosStorage(context, TEST_DATABASE);
    }

    @After
    public void tearDown() {
        if (storage != null) storage.close();
        context.deleteDatabase(TEST_DATABASE);
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
    public void invalidReplacementDoesNotOverwriteLastCommittedSnapshot() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertFalse(storage.saveState("{not valid json"));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void restoreExplicitlyReplacesSales() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        String secondSale =
            "{\"id\":1002,\"time\":\"2026-10-15T11:00:00.000Z\",\"type\":\"Takeaway\"," +
            "\"table\":null,\"payment\":\"Cash\",\"total\":50," +
            "\"items\":[{\"name\":\"Tea\",\"price\":50,\"qty\":1}]}";
        assertTrue(storage.restoreState(snapshot(secondSale, 1003)));
        assertSaleIds(storage.loadState(), 1002L);
    }

    @Test
    public void loadFailureIsExplicitAndDoesNotLookLikeFirstLaunch() {
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
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void rejectsSalesWithInvalidTimestamps() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        JSONObject root = new JSONObject(VALID_SNAPSHOT);
        JSONObject invalidSale = new JSONObject(SALE);
        invalidSale.put("id", 1002);
        invalidSale.put("time", "not-a-date");
        invalidSale.put("type", "Takeaway");
        invalidSale.put("table", JSONObject.NULL);
        root.getJSONArray("sales").put(invalidSale);
        root.put("nextId", 1003);
        assertFalse(storage.saveState(root.toString()));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void rejectsMalformedKitchenTicketsWithoutOverwritingSavedSales() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        for (int variant = 0; variant < 6; variant++) {
            JSONObject root = new JSONObject(VALID_SNAPSHOT);
            JSONObject ticket = new JSONObject();
            ticket.put("id", 1001);
            ticket.put("time", "2026-10-15T10:00:00.000Z");
            ticket.put("type", "Dine in");
            ticket.put("table", 4);
            ticket.put("done", false);
            JSONObject item = new JSONObject().put("name", "Test dish").put("price", 100).put("qty", 1);
            ticket.put("items", new JSONArray().put(item));
            JSONArray tickets = new JSONArray();
            tickets.put(ticket);
            if (variant == 0) ticket.put("done", "false");
            if (variant == 1) ticket.put("time", "not-a-date");
            if (variant == 2) ticket.put("table", 13);
            if (variant == 3) item.put("qty", 0);
            if (variant == 4) tickets.put(new JSONObject(ticket.toString()));
            if (variant == 5) ticket.put("id", 1002);
            root.put("tickets", tickets);
            assertFalse("Invalid kitchen ticket variant " + variant,
                storage.saveState(root.toString()));
            assertSaleIds(storage.loadState(), 1001L);
        }
    }

    @Test
    public void rejectsSaleWhenTotalDoesNotMatchItemSubtotal() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        String invalidSale =
            "{\"id\":1002,\"time\":\"2026-10-15T11:00:00.000Z\",\"type\":\"Takeaway\"," +
            "\"table\":null,\"payment\":\"Cash\",\"total\":51," +
            "\"items\":[{\"name\":\"Tea\",\"price\":50,\"qty\":1}]}";
        assertFalse(storage.saveState(snapshot(invalidSale, 1003)));
        assertSaleIds(storage.loadState(), 1001L);
    }

    @Test
    public void rejectsNextIdThatWouldReuseAnExistingSaleId() throws JSONException {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertFalse(storage.saveState(snapshot(SALE, 1001)));
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
    public void saleAdjustmentHistoryPersistsWithState() throws JSONException {
        JSONObject root=new JSONObject(VALID_SNAPSHOT);
        JSONObject event=new JSONObject().put("id",1).put("time","2026-10-15T10:01:00.000Z")
            .put("saleId",1001).put("action","REFUND").put("amountCents",2500)
            .put("reason","Customer request").put("staff","Mizzy");
        root.put("saleAdjustments",new JSONArray().put(event));
        assertTrue(storage.saveState(root.toString()));
        storage.close();storage=new PosStorage(context,TEST_DATABASE);
        assertEquals(2500,new JSONObject(storage.loadState()).getJSONArray("saleAdjustments").getJSONObject(0).getInt("amountCents"));
    }

    @Test
    public void rejectsRefundAmountsBeyondSaleTotal() throws JSONException {
        JSONObject root=new JSONObject(VALID_SNAPSHOT);
        JSONObject event=new JSONObject().put("id",1).put("time","2026-10-15T10:01:00.000Z")
            .put("saleId",1001).put("action","REFUND").put("amountCents",10001)
            .put("reason","Customer request").put("staff","Mizzy");
        root.put("saleAdjustments",new JSONArray().put(event));
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
        JSONArray sales = new JSONObject(state).getJSONArray("sales");
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
