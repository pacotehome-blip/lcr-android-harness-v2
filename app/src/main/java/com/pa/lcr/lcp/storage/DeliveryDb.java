
package com.pa.lcr.lcp.storage;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

/**
 * SQLite DB for delivery traceability (API + UI).
 *
 * Rotation is handled by deleting old rows in delivery_summary (cascade to attempt/event).
 */
public class DeliveryDb extends SQLiteOpenHelper {

    public static final String DB_NAME = "lcr_delivery.db";
    // v1: base tables
    // v2: add time columns to delivery_summary + index
    public static final int DB_VERSION = 2;

    private static final String TAG = "DeliveryDb";

    public DeliveryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);

        // Enforce FK constraints (needed for cascade deletes)
        db.setForeignKeyConstraintsEnabled(true);

        // ✅ IMPORTANT (Android 9 safe): journal_mode via rawQuery (PRAGMA returns a result)
        try (Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", null)) {
            // Optional: read mode returned
            // if (c.moveToFirst()) Log.d(TAG, "journal_mode=" + c.getString(0));
        } catch (Exception e) {
            // Best-effort fallback: if WAL fails on some devices, DB still works in default mode
            Log.w(TAG, "WAL not enabled (fallback to default journal mode)", e);
        }

        // Optional (redundant with setForeignKeyConstraintsEnabled but harmless)
        try {
            db.execSQL("PRAGMA foreign_keys=ON;");
        } catch (Exception e) {
            Log.w(TAG, "PRAGMA foreign_keys=ON failed (FK may still be enabled)", e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // ❌ IMPORTANT: do NOT set PRAGMA journal_mode here (Android 9 crash)
        // Tables
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_summary (" +
                "serial_id TEXT NOT NULL," +
                "ticket_no TEXT NOT NULL," +
                "sale_no TEXT," +
                "last_state TEXT NOT NULL," +
                "last_source TEXT NOT NULL," +
                "last_job_id TEXT," +
                "first_ts INTEGER NOT NULL," +
                "last_ts INTEGER NOT NULL," +
                "result_json TEXT," +
                "error_json TEXT," +
                // v2: time columns
                "start_ms INTEGER," +
                "end_ms INTEGER," +
                "start_utc TEXT," +
                "end_utc TEXT," +
                "duration_ms INTEGER," +
                "PRIMARY KEY (serial_id, ticket_no)" +
            ");"
        );

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_attempt (" +
                "attempt_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "serial_id TEXT NOT NULL," +
                "ticket_no TEXT NOT NULL," +
                "source TEXT NOT NULL," +
                "job_id TEXT," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER," +
                "outcome TEXT," +
                "result_json TEXT," +
                "error_json TEXT," +
                "FOREIGN KEY (serial_id, ticket_no) " +
                    "REFERENCES delivery_summary(serial_id, ticket_no) " +
                    "ON DELETE CASCADE" +
            ");"
        );

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_attempt_lookup " +
            "ON delivery_attempt(serial_id, ticket_no, source, job_id);"
        );

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS delivery_event (" +
                "event_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "attempt_id INTEGER NOT NULL," +
                "ts INTEGER NOT NULL," +
                "level TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "message TEXT," +
                "data_json TEXT," +
                "FOREIGN KEY (attempt_id) " +
                    "REFERENCES delivery_attempt(attempt_id) " +
                    "ON DELETE CASCADE" +
            ");"
        );

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_event_attempt_ts " +
            "ON delivery_event(attempt_id, ts);"
        );

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumnIfMissing(db, "delivery_summary", "start_ms", "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "end_ms", "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "start_utc", "TEXT");
            addColumnIfMissing(db, "delivery_summary", "end_utc", "TEXT");
            addColumnIfMissing(db, "delivery_summary", "duration_ms", "INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
        }
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String col, String type) {
        boolean exists = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                String n = c.getString(nameIdx);
                if (col.equalsIgnoreCase(n)) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
        }
    }
}
