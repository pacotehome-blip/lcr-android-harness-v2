package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * MediaProfileStore
 * - Persiste le média (transport) actif sélectionné dans l'APK.
 * - Partie 1: USB + Bluetooth (paired-only). Wi‑Fi plus tard.
 */
public final class MediaProfileStore {

    public static final String TYPE_USB  = "USB";
    public static final String TYPE_BT   = "BT";
    public static final String TYPE_WIFI = "WIFI";

    private final DeliveryDb dbh;

    public MediaProfileStore(Context ctx) {
        this.dbh = new DeliveryDb(ctx.getApplicationContext());
    }

    public static final class ActiveMedia {
        public final long mediaId;
        public final String type;
        public final String displayName;
        public final String status;
        public final String lastError;

        public ActiveMedia(long mediaId, String type, String displayName, String status, String lastError) {
            this.mediaId = mediaId;
            this.type = type;
            this.displayName = displayName;
            this.status = status;
            this.lastError = lastError;
        }
    }

    public synchronized ActiveMedia getActive() {
        SQLiteDatabase db = dbh.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT media_id, media_type, display_name, status, last_error FROM media_profile WHERE is_active=1 LIMIT 1",
                null)) {
            if (c.moveToFirst()) {
                return new ActiveMedia(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4)
                );
            }
        } catch (Exception ignored) {}
        return null;
    }

    public synchronized void setActiveStatus(String status, String lastError) {
        SQLiteDatabase db = dbh.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("status", status);
        cv.put("last_error", lastError);
        cv.put("last_seen_ts", System.currentTimeMillis());
        db.update("media_profile", cv, "is_active=1", null);
    }

    public synchronized void setActiveBt(String btName, String btMac, String btUuid) {
        long now = System.currentTimeMillis();
        long id = upsertBtProfile(btName, btMac, btUuid, now);
        setActiveInternal(id, now);
    }

    public synchronized void setActiveUsb(Integer vid, Integer pid, String deviceName, int baud) {
        long now = System.currentTimeMillis();
        long id = upsertUsbProfile(vid, pid, deviceName, baud, now);
        setActiveInternal(id, now);
    }

    // -------------------------
    // Internals
    // -------------------------

    private long upsertUsbProfile(Integer vid, Integer pid, String deviceName, int baud, long now) {
        SQLiteDatabase db = dbh.getWritableDatabase();
        Long existingId = null;
        try (Cursor c = db.rawQuery(
                "SELECT media_id FROM media_profile WHERE media_type=? AND usb_vid=? AND usb_pid=? LIMIT 1",
                new String[]{TYPE_USB, String.valueOf(vid == null ? 0 : vid), String.valueOf(pid == null ? 0 : pid)})) {
            if (c.moveToFirst()) existingId = c.getLong(0);
        } catch (Exception ignored) {}

        ContentValues cv = new ContentValues();
        cv.put("media_type", TYPE_USB);
        cv.put("display_name", (deviceName != null && !deviceName.trim().isEmpty()) ? deviceName : "USB");
        cv.put("enabled", 1);
        cv.put("created_ts", now);
        cv.put("last_seen_ts", now);
        cv.put("usb_vid", vid);
        cv.put("usb_pid", pid);
        cv.put("usb_device_name", deviceName);
        cv.put("usb_permission", 1);
        cv.put("serial_baud", baud);
        cv.put("serial_data_bits", 8);
        cv.put("serial_stop_bits", 1);
        cv.put("serial_parity", "NONE");
        cv.put("serial_flow_control", "NONE");

        if (existingId != null) {
            cv.remove("created_ts");
            db.update("media_profile", cv, "media_id=?", new String[]{String.valueOf(existingId)});
            return existingId;
        }
        return db.insert("media_profile", null, cv);
    }

    private long upsertBtProfile(String btName, String btMac, String btUuid, long now) {
        SQLiteDatabase db = dbh.getWritableDatabase();
        Long existingId = null;
        try (Cursor c = db.rawQuery(
                "SELECT media_id FROM media_profile WHERE media_type=? AND bt_mac=? LIMIT 1",
                new String[]{TYPE_BT, btMac == null ? "" : btMac})) {
            if (c.moveToFirst()) existingId = c.getLong(0);
        } catch (Exception ignored) {}

        ContentValues cv = new ContentValues();
        cv.put("media_type", TYPE_BT);
        cv.put("display_name", (btName != null && !btName.trim().isEmpty()) ? btName : "Bluetooth");
        cv.put("enabled", 1);
        cv.put("created_ts", now);
        cv.put("last_seen_ts", now);
        cv.put("bt_name", btName);
        cv.put("bt_mac", btMac);
        cv.put("bt_uuid", btUuid);
        cv.put("bt_socket_state", "DISCONNECTED");

        if (existingId != null) {
            cv.remove("created_ts");
            db.update("media_profile", cv, "media_id=?", new String[]{String.valueOf(existingId)});
            return existingId;
        }
        return db.insert("media_profile", null, cv);
    }

    private void setActiveInternal(long id, long now) {
        SQLiteDatabase db = dbh.getWritableDatabase();
        ContentValues clear = new ContentValues();
        clear.put("is_active", 0);
        db.update("media_profile", clear, "is_active=1", null);

        ContentValues cv = new ContentValues();
        cv.put("is_active", 1);
        cv.put("last_seen_ts", now);
        db.update("media_profile", cv, "media_id=?", new String[]{String.valueOf(id)});
    }
}
