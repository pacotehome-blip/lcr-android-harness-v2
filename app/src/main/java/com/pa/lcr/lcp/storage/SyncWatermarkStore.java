package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * Lecture/écriture du dernier id local synchronisé, par table — préparation de la sync
 * périodique vers la BD support centrale (demande Paul, 31 juillet 2026).
 *
 * Usage prévu par le futur sync worker :
 *   long lastId = watermark.get("log_bus_event");
 *   // pousser vers Dataverse toutes les lignes avec id > lastId
 *   watermark.set("log_bus_event", nouveauMaxId);
 */
public class SyncWatermarkStore {

    private static final String TAG = "SyncWatermarkStore";

    private final DeliveryDb helper;

    public SyncWatermarkStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
    }

    /** @return le dernier id synchronisé pour cette table, ou 0 si jamais synchronisée. */
    public long get(String tableName) {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT last_synced_id FROM sync_watermark WHERE table_name = ?",
                    new String[]{tableName})) {
                if (c.moveToFirst()) return c.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "get(" + tableName + ") ERR: " + e.getMessage());
        }
        return 0L;
    }

    /** Marque lastSyncedId comme dernier id synchronisé pour cette table (upsert). */
    public void set(String tableName, long lastSyncedId) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("table_name", tableName);
            cv.put("last_synced_id", lastSyncedId);
            cv.put("updated_ts", System.currentTimeMillis());
            // INSERT OR REPLACE : table_name est PRIMARY KEY, donc ceci upserte proprement
            db.insertWithOnConflict("sync_watermark", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            Log.w(TAG, "set(" + tableName + ", " + lastSyncedId + ") ERR: " + e.getMessage());
        }
    }
}