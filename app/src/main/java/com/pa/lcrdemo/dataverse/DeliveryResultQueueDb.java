package com.pa.lcrdemo.dataverse;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

/**
 * DeliveryResultQueueDb — Queue SQLite offline pour les livraisons.
 * Retry automatique via WorkManager quand réseau disponible.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/DeliveryResultQueueDb.java
 */
public class DeliveryResultQueueDb extends SQLiteOpenHelper {

    private static final String DB_NAME    = "delivery_sync.db";
    private static final int    DB_VERSION = 1;
    public  static final String TABLE      = "delivery_queue";

    public DeliveryResultQueueDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE " + TABLE + " (" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "delivery_uid TEXT NOT NULL," +
            "payload_json TEXT NOT NULL," +
            "status TEXT NOT NULL DEFAULT 'PENDING'," +
            "retry_count INTEGER NOT NULL DEFAULT 0," +
            "last_error TEXT," +
            "created_at INTEGER NOT NULL," +
            "updated_at INTEGER NOT NULL" +
            ")"
        );
        db.execSQL("CREATE UNIQUE INDEX idx_delivery_uid ON " + TABLE + "(delivery_uid)");
        db.execSQL("CREATE INDEX idx_status ON " + TABLE + "(status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    public long upsertPending(String deliveryUid, String payloadJson) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();
        Cursor c = db.query(TABLE, new String[]{"id"}, "delivery_uid=?",
            new String[]{deliveryUid}, null, null, null);
        try {
            ContentValues cv = new ContentValues();
            cv.put("delivery_uid", deliveryUid);
            cv.put("payload_json", payloadJson);
            cv.put("status",       "PENDING");
            cv.put("updated_at",   now);
            if (c.moveToFirst()) {
                long id = c.getLong(0);
                db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
                return id;
            } else {
                cv.put("retry_count", 0);
                cv.put("created_at",  now);
                return db.insert(TABLE, null, cv);
            }
        } finally {
            c.close();
        }
    }

    public List<QueueItem> listPending(int limit) {
        SQLiteDatabase db = getReadableDatabase();
        List<QueueItem> out = new ArrayList<>();
        Cursor c = db.query(TABLE,
            new String[]{"id", "delivery_uid", "payload_json", "retry_count"},
            "status=?", new String[]{"PENDING"},
            null, null, "created_at ASC", String.valueOf(limit));
        try {
            while (c.moveToNext()) {
                QueueItem qi = new QueueItem();
                qi.id          = c.getLong(0);
                qi.deliveryUid = c.getString(1);
                qi.payloadJson = c.getString(2);
                qi.retryCount  = c.getInt(3);
                out.add(qi);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public void markSent(long id) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status",     "SENT");
        cv.put("updated_at", System.currentTimeMillis());
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public void markPendingError(long id, int retryCount, String error) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status",      "PENDING");
        cv.put("retry_count", retryCount);
        cv.put("last_error",  error);
        cv.put("updated_at",  System.currentTimeMillis());
        db.update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    public static class QueueItem {
        public long   id;
        public String deliveryUid;
        public String payloadJson;
        public int    retryCount;
    }
}