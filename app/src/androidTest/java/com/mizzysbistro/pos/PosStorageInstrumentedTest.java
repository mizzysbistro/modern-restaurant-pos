package com.mizzysbistro.pos;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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
    private static final String VALID_SNAPSHOT =
        "{\"products\":[{\"id\":1,\"name\":\"Test dish\",\"price\":100}]," +
        "\"sales\":[{\"id\":1001,\"total\":100}]," +
        "\"stock\":{\"1\":4},\"tickets\":[],\"tables\":{},\"nextId\":1002}";

    private Context context;
    private PosStorage storage;

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
    public void savesAndReloadsSnapshotAfterDatabaseReopen() {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertEquals(VALID_SNAPSHOT, storage.loadState());

        storage.close();
        storage = new PosStorage(context, TEST_DATABASE);
        assertEquals(VALID_SNAPSHOT, storage.loadState());
    }

    @Test
    public void invalidReplacementDoesNotOverwriteLastCommittedSnapshot() {
        assertTrue(storage.saveState(VALID_SNAPSHOT));
        assertFalse(storage.saveState("{not valid json"));
        assertEquals(VALID_SNAPSHOT, storage.loadState());
    }

    @Test
    public void rejectsSnapshotsMissingRequiredCollections() {
        assertFalse(storage.saveState("{\"products\":[],\"sales\":[]}"));
        assertEquals("", storage.loadState());
    }

    @Test
    public void rejectsOversizedSnapshot() {
        storage.close();
        context.deleteDatabase(TEST_DATABASE);
        storage = new PosStorage(context, TEST_DATABASE, 512);
        String oversized = "{\"products\":[],\"sales\":[],\"tickets\":[],\"stock\":{}," +
            "\"tables\":{},\"nextId\":1001,\"extra\":\"" +
            repeat('x', 1024) + "\"}";
        assertFalse(storage.saveState(oversized));
        assertEquals("", storage.loadState());
    }

    private static String repeat(char character, int count) {
        StringBuilder builder = new StringBuilder(count);
        for (int i = 0; i < count; i++) builder.append(character);
        return builder.toString();
    }
}
