package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KnownTcpDeviceStore — équivalent "appareils appairés" pour raw TCP (N-Port).
 *
 * Il n'existe aucun appairage niveau OS pour un N-Port comme pour le Bluetooth :
 * cette table est la mémoire locale de l'APK. Chaque connexion TCP réussie
 * (manuelle ou via scan subnet) est enregistrée ici, pour que le chauffeur
 * puisse ensuite choisir dans une liste plutôt que retaper l'IP à chaque fois.
 */
public class KnownTcpDeviceStore {

    private final DeliveryDb helper;
    private final ExecutorService io;

    public KnownTcpDeviceStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
        this.io = Executors.newSingleThreadExecutor();
    }

    /** Enregistre/actualise un N-Port connu, suite à une connexion réussie. */
    public void upsertSeen(String ip, int port, String label, String serialId, Integer lcrNode) {
        io.execute(() -> {
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                long now = System.currentTimeMillis();

                ContentValues cv = new ContentValues();
                cv.put("ip", ip);
                cv.put("port", port);
                cv.put("label", label != null ? label : "");
                if (serialId != null) cv.put("serial_id", serialId);
                if (lcrNode != null)  cv.put("lcr_node", lcrNode);
                cv.put("last_ok_ms", now);

                boolean exists;
                try (Cursor c = db.rawQuery(
                        "SELECT ip FROM known_tcp_device WHERE ip=? AND port=?",
                        new String[]{ip, String.valueOf(port)})) {
                    exists = c.moveToFirst();
                }

                if (exists) {
                    db.update("known_tcp_device", cv, "ip=? AND port=?",
                            new String[]{ip, String.valueOf(port)});
                } else {
                    cv.put("created_ms", now);
                    db.insert("known_tcp_device", null, cv);
                }
            } catch (Exception ignored) {}
        });
    }

    /** Liste des N-Port connus, triés du plus récemment vu au plus ancien. */
    public JSONArray listKnown() {
        JSONArray arr = new JSONArray();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT ip, port, label, serial_id, lcr_node, last_ok_ms " +
                    "FROM known_tcp_device ORDER BY last_ok_ms DESC LIMIT 20", null)) {
                while (c.moveToNext()) {
                    JSONObject o = new JSONObject();
                    o.put("ip", c.getString(0));
                    o.put("port", c.getInt(1));
                    o.put("label", c.getString(2));
                    o.put("serial_id", c.isNull(3) ? null : c.getString(3));
                    o.put("lcr_node", c.isNull(4) ? null : c.getInt(4));
                    o.put("last_ok_ms", c.getLong(5));
                    arr.put(o);
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    /** Supprime un N-Port de la liste mémorisée (ex: décommissionné). */
    public void forget(String ip, int port) {
        io.execute(() -> {
            try {
                SQLiteDatabase db = helper.getWritableDatabase();
                db.delete("known_tcp_device", "ip=? AND port=?",
                        new String[]{ip, String.valueOf(port)});
            } catch (Exception ignored) {}
        });
    }
}