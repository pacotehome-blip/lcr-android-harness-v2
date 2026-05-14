package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TruckProfileStore — gestion des profils camion/citerne.
 *
 * Chaque profil identifie un camion par :
 * - truck_id   : identifiant unique (ex: "CAMION-12")
 * - bt_mac     : adresse MAC Bluetooth du registre
 * - bt_name    : nom BT (ex: "AWD-C9BF87")
 * - lcrnode_dec: node LCP (ex: 250)
 * - serial_id  : #série du registre (ex: "16466294")
 * - default_product: numéro de produit par défaut
 * - compartments: JSON array des compartiments
 *
 * La table truck_drift enregistre toutes les divergences
 * détectées entre le profil attendu et la réalité terrain.
 * Ces divergences sont retournées à FieldService qui gère
 * les notifications à la répartition.
 */
public class TruckProfileStore {

    private final DeliveryDb helper;
    private final ExecutorService io;

    public TruckProfileStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
        this.io = Executors.newSingleThreadExecutor();
    }

    // =========================================================
    // SAVE / UPSERT profile
    // =========================================================
    public JSONObject saveProfile(String truckId, String btMac, String btName,
                                   Integer lcrnode, String serialId,
                                   Integer defaultProduct, JSONArray compartments,
                                   String notes) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            long now = System.currentTimeMillis();

            ContentValues cv = new ContentValues();
            cv.put("truck_id",        truckId);
            cv.put("bt_mac",          btMac != null ? btMac.toUpperCase().trim() : null);
            cv.put("bt_name",         btName);
            cv.put("lcrnode_dec",     lcrnode);
            cv.put("serial_id",       serialId);
            cv.put("default_product", defaultProduct);
            cv.put("compartments",    compartments != null ? compartments.toString() : null);
            cv.put("notes",           notes);
            cv.put("ts_updated_ms",   now);

            // Check if exists
            boolean exists = false;
            try (Cursor c = db.rawQuery(
                    "SELECT truck_id FROM truck_profile WHERE truck_id=?",
                    new String[]{truckId})) {
                exists = c.moveToFirst();
            }

            if (exists) {
                db.update("truck_profile", cv, "truck_id=?", new String[]{truckId});
            } else {
                cv.put("ts_created_ms", now);
                cv.put("active", 0);
                db.insert("truck_profile", null, cv);
            }

            JSONObject result = new JSONObject();
            result.put("truck_id", truckId);
            result.put("saved", 1);
            result.put("ts_ms", now);
            return result;
        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return err;
            } catch (Exception ignored) { return null; }
        }
    }

    // =========================================================
    // GET profile by truck_id
    // =========================================================
    public JSONObject getProfile(String truckId) {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT * FROM truck_profile WHERE truck_id=?",
                    new String[]{truckId})) {
                if (!c.moveToFirst()) return null;
                return cursorToProfile(c);
            }
        } catch (Exception e) { return null; }
    }

    // =========================================================
    // GET active profile
    // =========================================================
    public JSONObject getActiveProfile() {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT * FROM truck_profile WHERE active=1 ORDER BY ts_updated_ms DESC LIMIT 1",
                    null)) {
                if (!c.moveToFirst()) return null;
                return cursorToProfile(c);
            }
        } catch (Exception e) { return null; }
    }

    // =========================================================
    // LIST all profiles
    // =========================================================
    public JSONArray listProfiles() {
        JSONArray arr = new JSONArray();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT * FROM truck_profile ORDER BY active DESC, ts_updated_ms DESC",
                    null)) {
                while (c.moveToNext()) {
                    JSONObject p = cursorToProfile(c);
                    if (p != null) arr.put(p);
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    // =========================================================
    // ACTIVATE profile
    // =========================================================
    public JSONObject activateProfile(String truckId) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            // Désactiver tous les profils
            ContentValues off = new ContentValues();
            off.put("active", 0);
            db.update("truck_profile", off, null, null);

            // Activer le profil cible
            ContentValues on = new ContentValues();
            on.put("active", 1);
            on.put("ts_updated_ms", System.currentTimeMillis());
            int rows = db.update("truck_profile", on, "truck_id=?", new String[]{truckId});

            if (rows == 0) return null;
            return getProfile(truckId);
        } catch (Exception e) { return null; }
    }

    // =========================================================
    // DELETE profile
    // =========================================================
    public boolean deleteProfile(String truckId) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            return db.delete("truck_profile", "truck_id=?", new String[]{truckId}) > 0;
        } catch (Exception e) { return false; }
    }

    // =========================================================
    // VALIDATE — comparer profil vs réalité et détecter drifts
    // =========================================================
    public JSONObject validateAndDetectDrift(
            String truckId,
            String actualBtMac,
            String actualBtName,
            Integer actualNode,
            String actualSerial,
            String deliveryUid) {
        try {
            JSONObject profile = getProfile(truckId);
            if (profile == null) {
                JSONObject r = new JSONObject();
                r.put("truck_id", truckId);
                r.put("profile_found", 0);
                r.put("drifts", new JSONArray());
                r.put("drift_count", 0);
                r.put("notify_required", false);
                return r;
            }

            JSONArray drifts = new JSONArray();
            long now = System.currentTimeMillis();

            // Comparer bt_mac
            String expMac = profile.optString("bt_mac", null);
            if (expMac != null && !expMac.isEmpty() && actualBtMac != null) {
                if (!expMac.equalsIgnoreCase(actualBtMac.trim())) {
                    drifts.put(buildDrift("bt_mac", expMac, actualBtMac));
                    recordDrift(truckId, "bt_mac", expMac, actualBtMac, deliveryUid, now);
                }
            }

            // Comparer bt_name
            String expName = profile.optString("bt_name", null);
            if (expName != null && !expName.isEmpty() && actualBtName != null) {
                if (!expName.equalsIgnoreCase(actualBtName.trim())) {
                    drifts.put(buildDrift("bt_name", expName, actualBtName));
                    recordDrift(truckId, "bt_name", expName, actualBtName, deliveryUid, now);
                }
            }

            // Comparer lcrnode_dec
            int expNode = profile.optInt("lcrnode_dec", -1);
            if (expNode > 0 && actualNode != null && actualNode != expNode) {
                drifts.put(buildDrift("lcrnode_dec",
                        String.valueOf(expNode), String.valueOf(actualNode)));
                recordDrift(truckId, "lcrnode_dec",
                        String.valueOf(expNode), String.valueOf(actualNode), deliveryUid, now);
            }

            // Comparer serial_id
            String expSerial = profile.optString("serial_id", null);
            if (expSerial != null && !expSerial.isEmpty() && actualSerial != null) {
                if (!expSerial.equalsIgnoreCase(actualSerial.trim())) {
                    drifts.put(buildDrift("serial_id", expSerial, actualSerial));
                    recordDrift(truckId, "serial_id", expSerial, actualSerial, deliveryUid, now);
                }
            }

            JSONObject result = new JSONObject();
            result.put("truck_id",       truckId);
            result.put("profile_found",  1);
            result.put("profile",        profile);
            result.put("drifts",         drifts);
            result.put("drift_count",    drifts.length());
            result.put("notify_required", drifts.length() > 0);
            result.put("ts_ms",          now);
            return result;

        } catch (Exception e) {
            try {
                JSONObject err = new JSONObject();
                err.put("error", e.getMessage());
                return err;
            } catch (Exception ignored) { return null; }
        }
    }

    // =========================================================
    // GET drifts (non acknowledged)
    // =========================================================
    public JSONArray getDrifts(String truckId, boolean onlyUnacked) {
        JSONArray arr = new JSONArray();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            String where = truckId != null ? "truck_id=?" : "1=1";
            String extra = onlyUnacked ? " AND acknowledged=0" : "";
            String[] args = truckId != null ? new String[]{truckId} : new String[]{};
            try (Cursor c = db.rawQuery(
                    "SELECT * FROM truck_drift WHERE " + where + extra + " ORDER BY ts_ms DESC LIMIT 100",
                    args)) {
                while (c.moveToNext()) {
                    JSONObject row = new JSONObject();
                    row.put("id",             c.getLong(c.getColumnIndexOrThrow("id")));
                    row.put("truck_id",       c.getString(c.getColumnIndexOrThrow("truck_id")));
                    row.put("field_name",     c.getString(c.getColumnIndexOrThrow("field_name")));
                    row.put("expected_value", c.getString(c.getColumnIndexOrThrow("expected_value")));
                    row.put("actual_value",   c.getString(c.getColumnIndexOrThrow("actual_value")));
                    row.put("delivery_uid",   c.getString(c.getColumnIndexOrThrow("delivery_uid")));
                    row.put("acknowledged",   c.getInt(c.getColumnIndexOrThrow("acknowledged")));
                    row.put("ts_ms",          c.getLong(c.getColumnIndexOrThrow("ts_ms")));
                    arr.put(row);
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    // =========================================================
    // ACKNOWLEDGE drift
    // =========================================================
    public boolean acknowledgeDrift(String truckId) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("acknowledged", 1);
            String where = truckId != null ? "truck_id=?" : "1=1";
            String[] args = truckId != null ? new String[]{truckId} : new String[]{};
            db.update("truck_drift", cv, where, args);
            return true;
        } catch (Exception e) { return false; }
    }

    // =========================================================
    // PURGE old drifts
    // =========================================================
    public void purgeOlderThanDaysAsync(int days) {
        io.execute(() -> {
            try {
                long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
                SQLiteDatabase db = helper.getWritableDatabase();
                db.delete("truck_drift", "ts_ms < ? AND acknowledged=1",
                        new String[]{Long.toString(cutoff)});
            } catch (Exception ignored) {}
        });
    }

    // =========================================================
    // Private helpers
    // =========================================================
    private void recordDrift(String truckId, String fieldName,
                              String expected, String actual,
                              String deliveryUid, long tsMs) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("truck_id",       truckId);
            cv.put("field_name",     fieldName);
            cv.put("expected_value", expected);
            cv.put("actual_value",   actual);
            cv.put("delivery_uid",   deliveryUid);
            cv.put("acknowledged",   0);
            cv.put("ts_ms",          tsMs);
            db.insert("truck_drift", null, cv);
        } catch (Exception ignored) {}
    }

    private JSONObject buildDrift(String field, String expected, String actual) {
        try {
            JSONObject d = new JSONObject();
            d.put("field",          field);
            d.put("expected_value", expected);
            d.put("actual_value",   actual);
            d.put("message",        "Divergence détectée: " + field +
                    " attendu=" + expected + " réel=" + actual);
            return d;
        } catch (Exception e) { return new JSONObject(); }
    }

    private JSONObject cursorToProfile(Cursor c) {
        try {
            JSONObject p = new JSONObject();
            p.put("truck_id",        getString(c, "truck_id"));
            p.put("bt_mac",          getString(c, "bt_mac"));
            p.put("bt_name",         getString(c, "bt_name"));
            p.put("lcrnode_dec",     getInt(c,    "lcrnode_dec"));
            p.put("serial_id",       getString(c, "serial_id"));
            p.put("default_product", getInt(c,    "default_product"));
            p.put("active",          getInt(c,    "active"));
            p.put("notes",           getString(c, "notes"));
            p.put("ts_created_ms",   getLong(c,   "ts_created_ms"));
            p.put("ts_updated_ms",   getLong(c,   "ts_updated_ms"));

            // Parse compartments JSON
            String comp = getString(c, "compartments");
            if (comp != null && !comp.isEmpty()) {
                try { p.put("compartments", new JSONArray(comp)); }
                catch (Exception e) { p.put("compartments", comp); }
            } else {
                p.put("compartments", new JSONArray());
            }
            return p;
        } catch (Exception e) { return null; }
    }

    private String getString(Cursor c, String col) {
        try { int i = c.getColumnIndex(col); return i >= 0 ? c.getString(i) : null; }
        catch (Exception e) { return null; }
    }

    private int getInt(Cursor c, String col) {
        try { int i = c.getColumnIndex(col); return i >= 0 ? c.getInt(i) : 0; }
        catch (Exception e) { return 0; }
    }

    private long getLong(Cursor c, String col) {
        try { int i = c.getColumnIndex(col); return i >= 0 ? c.getLong(i) : 0L; }
        catch (Exception e) { return 0L; }
    }
}