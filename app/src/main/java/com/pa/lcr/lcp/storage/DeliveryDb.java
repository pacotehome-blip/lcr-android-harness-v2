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
    // v3: add media_profile/media_event
    // v4: add structured error columns to delivery_event (event_level/event_code/event_where/detail_short)
    // v5: add truck_profile + truck_drift tables
    // v6: add active_delivery table (livraison courante persistée)
    // v7: add produit/preset/status to active_delivery
    // v8: add bt_signal table (perdue lors du revert à 3f79a08)
    // v9: add register_products table (descriptions produits par registre LCR-II)
    public static final int DB_VERSION = 9;

    private static final String TAG = "DeliveryDb";

    public DeliveryDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", null)) {
            // Optional: read mode returned
        } catch (Exception e) {
            Log.w(TAG, "WAL not enabled (fallback to default journal mode)", e);
        }
        try {
            db.execSQL("PRAGMA foreign_keys=ON;");
        } catch (Exception e) {
            Log.w(TAG, "PRAGMA foreign_keys=ON failed (FK may still be enabled)", e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createDeliveryTables(db);
        createMediaTables(db);
        createTruckTables(db);
        createActiveDeliveryTable(db);
        createBtSignalTable(db);
        createRegisterProductsTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2 columns
        if (oldVersion < 2) {
            addColumnIfMissing(db, "delivery_summary", "start_ms",    "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "end_ms",      "INTEGER");
            addColumnIfMissing(db, "delivery_summary", "start_utc",   "TEXT");
            addColumnIfMissing(db, "delivery_summary", "end_utc",     "TEXT");
            addColumnIfMissing(db, "delivery_summary", "duration_ms", "INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
        }
        // v3 tables
        if (oldVersion < 3) {
            createMediaTables(db);
        }
        // v4 columns
        if (oldVersion < 4) {
            addColumnIfMissing(db, "delivery_event", "event_level",  "TEXT");
            addColumnIfMissing(db, "delivery_event", "event_code",   "TEXT");
            addColumnIfMissing(db, "delivery_event", "event_where",  "TEXT");
            addColumnIfMissing(db, "delivery_event", "detail_short", "TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_level_ts ON delivery_event(event_level, ts);");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_code_ts ON delivery_event(event_code, ts);");
        }
        // v5 tables
        if (oldVersion < 5) {
            createTruckTables(db);
        }
        // v6: active_delivery
        if (oldVersion < 6) {
            createActiveDeliveryTable(db);
        }
        // v7: produit/preset/status dans active_delivery
        if (oldVersion < 7) {
            addColumnIfMissing(db, "active_delivery", "produit",  "INTEGER");
            addColumnIfMissing(db, "active_delivery", "preset",   "REAL");
            addColumnIfMissing(db, "active_delivery", "status",   "TEXT");
            addColumnIfMissing(db, "active_delivery", "wo_id_guid", "TEXT");
        }
        // v8: bt_signal table
        if (oldVersion < 8) {
            createBtSignalTable(db);
        }
        // v9: register_products table
        if (oldVersion < 9) {
            createRegisterProductsTable(db);
        }
    }

    // =========================================================
    // Table creation helpers
    // =========================================================
    private static void createDeliveryTables(SQLiteDatabase db) {
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
            "event_level TEXT," +
            "event_code TEXT," +
            "event_where TEXT," +
            "detail_short TEXT," +
            "FOREIGN KEY (attempt_id) " +
            "REFERENCES delivery_attempt(attempt_id) " +
            "ON DELETE CASCADE" +
            ");"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_event_attempt_ts " +
            "ON delivery_event(attempt_id, ts);"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_level_ts ON delivery_event(event_level, ts);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_event_code_ts ON delivery_event(event_code, ts);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_summary_time ON delivery_summary(start_ms, end_ms);");
    }

    private static void createMediaTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_profile (" +
            "media_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "media_type TEXT NOT NULL," +
            "display_name TEXT," +
            "enabled INTEGER NOT NULL DEFAULT 1," +
            "is_active INTEGER NOT NULL DEFAULT 0," +
            "status TEXT NOT NULL DEFAULT 'DISCONNECTED'," +
            "last_error TEXT," +
            "created_ts INTEGER NOT NULL," +
            "last_seen_ts INTEGER," +
            "last_ok_ts INTEGER," +
            "usb_vid INTEGER," +
            "usb_pid INTEGER," +
            "usb_device_name TEXT," +
            "usb_permission INTEGER," +
            "serial_baud INTEGER," +
            "serial_data_bits INTEGER," +
            "serial_stop_bits INTEGER," +
            "serial_parity TEXT," +
            "serial_flow_control TEXT," +
            "bt_name TEXT," +
            "bt_mac TEXT," +
            "bt_uuid TEXT," +
            "bt_bond_state TEXT," +
            "bt_socket_state TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_active ON media_profile(is_active);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_type ON media_profile(media_type);");
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media_event (" +
            "event_id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "ts INTEGER NOT NULL," +
            "media_id INTEGER," +
            "media_type TEXT," +
            "level TEXT NOT NULL," +
            "code TEXT," +
            "message TEXT," +
            "data_json TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_event_ts ON media_event(ts);");
    }

    // =========================================================
    // Truck profile tables (v5)
    // =========================================================
    private static void createTruckTables(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS truck_profile (" +
            "truck_id TEXT PRIMARY KEY," +
            "bt_mac TEXT," +
            "bt_name TEXT," +
            "lcrnode_dec INTEGER," +
            "serial_id TEXT," +
            "default_product INTEGER," +
            "compartments TEXT," +
            "notes TEXT," +
            "active INTEGER NOT NULL DEFAULT 0," +
            "ts_created_ms INTEGER NOT NULL," +
            "ts_updated_ms INTEGER NOT NULL" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_truck_active ON truck_profile(active);");
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS truck_drift (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "truck_id TEXT NOT NULL," +
            "field_name TEXT NOT NULL," +
            "expected_value TEXT," +
            "actual_value TEXT," +
            "delivery_uid TEXT," +
            "acknowledged INTEGER NOT NULL DEFAULT 0," +
            "ts_ms INTEGER NOT NULL" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_drift_truck ON truck_drift(truck_id, ts_ms);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_drift_ack ON truck_drift(acknowledged);");
    }

    // =========================================================
    // Active delivery table (v6)
    // Une seule ligne (id=1) — livraison courante en cours.
    // Effacée à onDeliveryEnded. Permet de reprendre le poll
    // si l'APK est relancé pendant une livraison active.
    // =========================================================
    private static void createActiveDeliveryTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS active_delivery (" +
            "id INTEGER PRIMARY KEY CHECK (id = 1)," +
            "wo_num TEXT," +
            "wo_id_guid TEXT," +
            "job_id TEXT," +
            "mac TEXT," +
            "node INTEGER," +
            "serial_id TEXT," +
            "produit INTEGER," +
            "preset REAL," +
            "status TEXT," +
            "ts_started_ms INTEGER" +
            ");"
        );
    }

    // =========================================================
    // BT Signal table (v8) — historique signal BT par transport
    // =========================================================
    private static void createBtSignalTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS bt_signal (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "transport_key TEXT NOT NULL," +
            "ts_ms INTEGER NOT NULL," +
            "delivery_active INTEGER NOT NULL DEFAULT 0," +
            "io_samples INTEGER," +
            "io_score TEXT," +
            "rssi INTEGER," +
            "notes TEXT" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_bt_signal_ts ON bt_signal(transport_key, ts_ms);");
    }

    // =========================================================
    // Register products table (v9)
    // Descriptions des produits lues depuis le registre LCR-II.
    // PK (serial_id, note_idx) — UPSERT via INSERT OR REPLACE.
    // sync_status : PENDING → à synchroniser vers Dataverse
    //               SYNCED  → déjà synchronisé
    // =========================================================
    private static void createRegisterProductsTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS register_products (" +
            "serial_id   TEXT    NOT NULL," +
            "note_idx    INTEGER NOT NULL," +
            "description TEXT    NOT NULL DEFAULT ''," +
            "updated_at  INTEGER NOT NULL DEFAULT 0," +
            "sync_status TEXT    NOT NULL DEFAULT 'PENDING'," +
            "PRIMARY KEY (serial_id, note_idx)" +
            ");"
        );
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_register_products_sync " +
            "ON register_products(sync_status);"
        );
    }

    // =========================================================
    // Column helper
    // =========================================================
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
