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
