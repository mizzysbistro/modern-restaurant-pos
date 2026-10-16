package com.mizzysbistro.pos;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.webkit.JavascriptInterface;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Durable local POS state. The single-row snapshot is an incremental bridge
 * from the existing WebView UI to SQLite; it is replaced atomically in a
 * transaction. A later schema migration can normalize orders and sales into
 * relational tables without discarding this snapshot.
 */
public final class PosStorage extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "mizzy_pos.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_STATE = "pos_state";
    private static final int STATE_ROW_ID = 1;
    private static final int MAX_STATE_CHARS = 10 * 1024 * 1024;
    private final int maxStateChars;

    public PosStorage(Context context) {
        this(context, DATABASE_NAME, MAX_STATE_CHARS);
    }

    // Package-private database-name override keeps instrumentation tests isolated
    // from the real application's POS database.
    PosStorage(Context context, String databaseName) {
        this(context, databaseName, MAX_STATE_CHARS);
    }

    // A smaller package-private limit lets instrumentation test rejection behavior
    // without allocating a full 10 MiB payload in the emulator.
    PosStorage(Context context, String databaseName, int maxStateChars) {
        super(context.getApplicationContext(), databaseName, null, DATABASE_VERSION);
        if (maxStateChars <= 0) {
            throw new IllegalArgumentException("Maximum state size must be positive.");
        }
        this.maxStateChars = maxStateChars;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE_STATE + " (" +
            "id INTEGER NOT NULL PRIMARY KEY CHECK(id = " + STATE_ROW_ID + "), " +
            "payload TEXT NOT NULL, " +
            "updated_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Deliberately do not drop or replace stored POS data here. Future
        // schema changes must add an explicit, non-destructive migration.
        if (oldVersion > newVersion) {
            throw new IllegalStateException("Database downgrade is not supported.");
        }
    }

    /** Returns the last committed snapshot, or an empty string on first launch. */
    @JavascriptInterface
    public synchronized String loadState() {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = getReadableDatabase();
            cursor = db.query(
                TABLE_STATE,
                new String[]{"payload"},
                "id = ?",
                new String[]{String.valueOf(STATE_ROW_ID)},
                null, null, null
            );
            return cursor.moveToFirst() ? cursor.getString(0) : "";
        } catch (RuntimeException error) {
            // Do not expose local database internals to the JavaScript UI.
            return "";
        } finally {
            if (cursor != null) cursor.close();
        }
    }


    /**
     * Creates a salted, iterated PIN verifier. Only the verifier is returned to
     * JavaScript; the plaintext PIN is never persisted by the native layer.
     * Format: algorithm:salt:derived-key (Base64 without line breaks).
     */
    @JavascriptInterface
    public synchronized String hashPin(String pin) {
        if (pin == null || !pin.matches("[0-9]{4,8}")) return "";
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String algorithm = "PBKDF2WithHmacSHA256";
        byte[] derived;
        try {
            derived = derivePin(pin, salt, algorithm);
        } catch (Exception unsupported) {
            algorithm = "PBKDF2WithHmacSHA1";
            try {
                derived = derivePin(pin, salt, algorithm);
            } catch (Exception error) {
                return "";
            }
        }
        return (algorithm.equals("PBKDF2WithHmacSHA256") ? "p2h256" : "p2h1") + ":" +
            Base64.encodeToString(salt, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(derived, Base64.NO_WRAP);
    }

    /** Verifies a PIN in constant-time against a salted native verifier. */
    @JavascriptInterface
    public synchronized boolean verifyPin(String pin, String encodedVerifier) {
        if (pin == null || !pin.matches("[0-9]{4,8}") || encodedVerifier == null) return false;
        String[] parts = encodedVerifier.split(":", 3);
        if (parts.length != 3) return false;
        final String algorithm;
        if ("p2h256".equals(parts[0])) {
            algorithm = "PBKDF2WithHmacSHA256";
        } else if ("p2h1".equals(parts[0])) {
            algorithm = "PBKDF2WithHmacSHA1";
        } else {
            return false;
        }
        try {
            byte[] salt = Base64.decode(parts[1], Base64.NO_WRAP);
            byte[] expected = Base64.decode(parts[2], Base64.NO_WRAP);
            byte[] actual = derivePin(pin, salt, algorithm);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception error) {
            return false;
        }
    }

    private static byte[] derivePin(String pin, byte[] salt, String algorithm) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120000, 256);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(algorithm);
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Validates and atomically persists the full POS snapshot.
     * Returns false when validation or disk persistence fails.
     */
    @JavascriptInterface
    public synchronized boolean saveState(String payload) {
        if (!isValidSnapshot(payload)) return false;

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            ContentValues values = new ContentValues();
            values.put("id", STATE_ROW_ID);
            values.put("payload", payload);
            values.put("updated_at", System.currentTimeMillis());
            long result = db.insertWithOnConflict(
                TABLE_STATE, null, values, SQLiteDatabase.CONFLICT_REPLACE
            );
            if (result == -1L) return false;
            db.setTransactionSuccessful();
            return true;
        } catch (RuntimeException error) {
            return false;
        } finally {
            if (db != null && db.inTransaction()) db.endTransaction();
        }
    }

    private boolean isValidSnapshot(String payload) {
        if (payload == null || payload.isEmpty() || payload.length() > maxStateChars) {
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
            return products != null
                && sales != null
                && tickets != null
                && stock != null
                && tables != null
                && nextId instanceof Number;
        } catch (JSONException error) {
            return false;
        }
    }
}
