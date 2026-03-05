
package com.pa.lcr.lcp.storage;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite DB for delivery traceability (API + UI).
 *
 * Rotation is handled by deleting old rows in delivery_summary (cascade to attempt/event).
 */
public class DeliveryDb extends SQLiteOpenHelper {

    public static final String DB_NAME = "lcr_delivery.db";
    public static final int DB_VERSION = 1;

    public DeliveryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        // Enforce FK constraints (needed for cascade deletes)
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // WAL improves concurrency and reduces IO contention
        db.execSQL("PRAGMA journal_mode=WAL;");
        db.execSQL("PRAGMA foreign_keys=ON;");

        // 1 row per (serial_id, ticket_no): quick index + last known result
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS delivery_summary (" +
                        "serial_id    TEXT NOT NULL," +
                        "ticket_no    TEXT NOT NULL," +
                        "sale_no      TEXT," +
                        "last_state   TEXT NOT NULL," +       // PENDING/RUNNING/DONE/ERROR
                        "last_source  TEXT NOT NULL," +      // API/UI
                        "last_job_id  TEXT," +               // nullable for UI
                        "first_ts     INTEGER NOT NULL," +   // epoch ms
                        "last_ts      INTEGER NOT NULL," +   // epoch ms
                        "result_json  TEXT," +
                        "error_json   TEXT," +
                        "PRIMARY KEY (serial_id, ticket_no)" +
                        ");"
        );

        // Multiple attempts per (serial_id, ticket_no)
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS delivery_attempt (" +
                        "attempt_id   INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "serial_id    TEXT NOT NULL," +
                        "ticket_no    TEXT NOT NULL," +
                        "source       TEXT NOT NULL," +      // API/UI
                        "job_id       TEXT," +               // nullable for UI
                        "start_ts     INTEGER NOT NULL," +   // epoch ms
                        "end_ts       INTEGER," +            // epoch ms, nullable until closed
                        "outcome      TEXT," +               // DONE/ERROR/ABORTED/...
                        "result_json  TEXT," +
                        "error_json   TEXT," +
                        "FOREIGN KEY (serial_id, ticket_no) " +
                        "REFERENCES delivery_summary(serial_id, ticket_no) " +
                        "ON DELETE CASCADE" +
                        ");"
        );

        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_attempt_lookup " +
                        "ON delivery_attempt(serial_id, ticket_no, source, job_id);"
        );

        // Timeline of events per attempt
        db.execSQL(
                "CREATE TABLE IF NOT EXISTS delivery_event (" +
                        "event_id     INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "attempt_id   INTEGER NOT NULL," +
                        "ts           INTEGER NOT NULL," +   // epoch ms
                        "level        TEXT NOT NULL," +      // INFO/WARN/ERROR
                        "type         TEXT NOT NULL," +      // CONNECT/ARMED/CONTINUE/RUN_SEEN/DONE/...
                        "message      TEXT," +
                        "data_json    TEXT," +
                        "FOREIGN KEY (attempt_id) " +
                        "REFERENCES delivery_attempt(attempt_id) " +
                        "ON DELETE CASCADE" +
                        ");"
        );

        db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_event_attempt_ts " +
                        "ON delivery_event(attempt_id, ts);"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1: no migrations
    }
}
