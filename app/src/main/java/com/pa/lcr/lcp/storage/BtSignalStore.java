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
 * BtSignalStore — persistance des mesures de signal BT
 * Sources : SCAN_BT (RSSI via discovery) et IO_SAMPLE (qualité IO temps réel)
 */
public class BtSignalStore {

    // Sources de mesure
    public static final String SOURCE_SCAN_BT      = "SCAN_BT";
    public static final String SOURCE_IO_SAMPLE    = "IO_SAMPLE";
    public static final String SOURCE_IO_DISCONNECT = "IO_DISCONNECT";

    // Seuils RSSI (dBm)
    public static final int RSSI_EXCELLENT = -60;
    public static final int RSSI_BON       = -70;
    public static final int RSSI_MOYEN     = -80;
    public static final int RSSI_FAIBLE    = -90;

    // Seuils IO erreurs (%)
    public static final double IO_EXCELLENT_ERR = 0.0;
    public static final double IO_BON_ERR       = 2.0;
    public static final double IO_MOYEN_ERR     = 5.0;
    public static final double IO_FAIBLE_ERR    = 10.0;

    // Seuils IO latence (ms)
    public static final int IO_EXCELLENT_LAT = 200;
    public static final int IO_BON_LAT       = 500;
    public static final int IO_MOYEN_LAT     = 1000;

    private final DeliveryDb helper;
    private final ExecutorService io;

    public BtSignalStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
        this.io = Executors.newSingleThreadExecutor();
    }

    // =========================================================
    // Score RSSI
    // =========================================================
    public static String rssiQuality(int rssi) {
        if (rssi >= RSSI_EXCELLENT) return "EXCELLENT";
        if (rssi >= RSSI_BON)       return "BON";
        if (rssi >= RSSI_MOYEN)     return "MOYEN";
        if (rssi >= RSSI_FAIBLE)    return "FAIBLE";
        return "MAUVAIS";
    }

    // =========================================================
    // Score IO
    // =========================================================
    public static String ioQuality(int errors, int timeouts, int samples, int latencyAvgMs) {
        if (samples <= 0) return "INCONNU";
        double errRate = (double)(errors + timeouts) / samples * 100.0;
        if (errRate <= IO_EXCELLENT_ERR && latencyAvgMs < IO_EXCELLENT_LAT) return "EXCELLENT";
        if (errRate <= IO_BON_ERR       && latencyAvgMs < IO_BON_LAT)       return "BON";
        if (errRate <= IO_MOYEN_ERR     && latencyAvgMs < IO_MOYEN_LAT)     return "MOYEN";
        if (errRate <= IO_FAIBLE_ERR)                                        return "FAIBLE";
        return "MAUVAIS";
    }

    // =========================================================
    // INSERT — scan RSSI
    // =========================================================
    public void insertScanAsync(String mac, String transportKey,
                                int rssi, boolean deliveryActive) {
        io.execute(() -> insertScan(mac, transportKey, rssi, deliveryActive));
    }

    public void insertScan(String mac, String transportKey,
                           int rssi, boolean deliveryActive) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("mac", mac);
            cv.put("transport_key", transportKey);
            cv.put("rssi", rssi);
            cv.put("rssi_quality", rssiQuality(rssi));
            cv.put("source", SOURCE_SCAN_BT);
            cv.put("delivery_active", deliveryActive ? 1 : 0);
            cv.put("ts_ms", System.currentTimeMillis());
            db.insert("bt_signal", null, cv);
        } catch (Exception ignored) {}
    }

    // =========================================================
    // INSERT — échantillon IO
    // =========================================================
    public void insertIoSampleAsync(String mac, String transportKey,
                                    int errors, int timeouts,
                                    int samples, int latencyAvgMs,
                                    boolean deliveryActive, String source) {
        io.execute(() -> insertIoSample(mac, transportKey,
                errors, timeouts, samples, latencyAvgMs, deliveryActive, source));
    }

    public void insertIoSample(String mac, String transportKey,
                               int errors, int timeouts,
                               int samples, int latencyAvgMs,
                               boolean deliveryActive, String source) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("mac", mac);
            cv.put("transport_key", transportKey);
            cv.put("io_score", ioQuality(errors, timeouts, samples, latencyAvgMs));
            cv.put("io_errors", errors);
            cv.put("io_timeouts", timeouts);
            cv.put("io_latency_avg_ms", latencyAvgMs);
            cv.put("io_samples", samples);
            cv.put("source", source != null ? source : SOURCE_IO_SAMPLE);
            cv.put("delivery_active", deliveryActive ? 1 : 0);
            cv.put("ts_ms", System.currentTimeMillis());
            db.insert("bt_signal", null, cv);
        } catch (Exception ignored) {}
    }

    // =========================================================
    // QUERY — dernière mesure connue par MAC
    // =========================================================
    public JSONObject getLatestByMac(String mac) {
        try {
            SQLiteDatabase db = helper.getReadableDatabase();

            // Dernière mesure RSSI
            JSONObject rssiRow = null;
            try (Cursor c = db.rawQuery(
                    "SELECT rssi, rssi_quality, ts_ms FROM bt_signal " +
                    "WHERE mac=? AND source=? ORDER BY ts_ms DESC LIMIT 1",
                    new String[]{mac, SOURCE_SCAN_BT})) {
                if (c.moveToFirst()) {
                    rssiRow = new JSONObject();
                    rssiRow.put("rssi", c.getInt(0));
                    rssiRow.put("rssi_quality", c.getString(1));
                    rssiRow.put("last_scan_ms", c.getLong(2));
                }
            }

            // Dernière mesure IO
            JSONObject ioRow = null;
            try (Cursor c = db.rawQuery(
                    "SELECT io_score, io_errors, io_timeouts, io_latency_avg_ms, " +
                    "io_samples, ts_ms, source FROM bt_signal " +
                    "WHERE mac=? AND source IN (?,?) ORDER BY ts_ms DESC LIMIT 1",
                    new String[]{mac, SOURCE_IO_SAMPLE, SOURCE_IO_DISCONNECT})) {
                if (c.moveToFirst()) {
                    ioRow = new JSONObject();
                    ioRow.put("io_score", c.getString(0));
                    ioRow.put("io_errors", c.getInt(1));
                    ioRow.put("io_timeouts", c.getInt(2));
                    ioRow.put("io_latency_avg_ms", c.getInt(3));
                    ioRow.put("io_samples", c.getInt(4));
                    ioRow.put("last_io_sample_ms", c.getLong(5));
                    ioRow.put("io_source", c.getString(6));
                }
            }

            if (rssiRow == null && ioRow == null) return null;

            JSONObject result = new JSONObject();
            result.put("mac", mac);
            result.put("transport_key", "BT:" + mac.toUpperCase());

            if (rssiRow != null) {
                result.put("rssi", rssiRow.optInt("rssi", 0));
                result.put("rssi_quality", rssiRow.optString("rssi_quality", "INCONNU"));
                result.put("last_scan_ms", rssiRow.optLong("last_scan_ms", 0));
            } else {
                result.put("rssi", JSONObject.NULL);
                result.put("rssi_quality", "INCONNU");
                result.put("last_scan_ms", JSONObject.NULL);
            }

            if (ioRow != null) {
                result.put("io_score", ioRow.optString("io_score", "INCONNU"));
                result.put("io_errors", ioRow.optInt("io_errors", 0));
                result.put("io_timeouts", ioRow.optInt("io_timeouts", 0));
                result.put("io_latency_avg_ms", ioRow.optInt("io_latency_avg_ms", 0));
                result.put("io_samples", ioRow.optInt("io_samples", 0));
                result.put("last_io_sample_ms", ioRow.optLong("last_io_sample_ms", 0));
            } else {
                result.put("io_score", "INCONNU");
                result.put("io_errors", 0);
                result.put("io_timeouts", 0);
                result.put("io_latency_avg_ms", 0);
                result.put("io_samples", 0);
                result.put("last_io_sample_ms", JSONObject.NULL);
            }

            return result;

        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================
    // QUERY — toutes les MACs connues (résumé)
    // =========================================================
    public JSONArray getAllLatest() {
        JSONArray arr = new JSONArray();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT DISTINCT mac FROM bt_signal ORDER BY mac", null)) {
                while (c.moveToNext()) {
                    String mac = c.getString(0);
                    if (mac == null || mac.trim().isEmpty()) continue;
                    JSONObject row = getLatestByMac(mac);
                    if (row != null) arr.put(row);
                }
            }
        } catch (Exception ignored) {}
        return arr;
    }

    // =========================================================
    // PURGE — données plus vieilles que N jours
    // =========================================================
    public void purgeOlderThanDaysAsync(int days) {
        io.execute(() -> purgeOlderThanDays(days));
    }

    public void purgeOlderThanDays(int days) {
        try {
            long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
            SQLiteDatabase db = helper.getWritableDatabase();
            db.delete("bt_signal", "ts_ms < ?", new String[]{Long.toString(cutoff)});
        } catch (Exception ignored) {}
    }
}