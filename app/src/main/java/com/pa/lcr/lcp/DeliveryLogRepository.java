
package com.pa.lcr.lcp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

public final class DeliveryLogRepository {

    private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L; // 7 jours

    private final DeliveryLogDbHelper dbh;

    public DeliveryLogRepository(Context appContext) {
        this.dbh = new DeliveryLogDbHelper(appContext);
    }

    /** Purge toutes les entrées plus vieilles que 7 jours. */
    public void purgeOld(long nowMs) {
        long cutoff = nowMs - RETENTION_MS;
        SQLiteDatabase db = dbh.getWritableDatabase();
        // on purge sur updated_at_ms (plus robuste); end_ms peut être NULL
        db.delete(DeliveryLogDbHelper.T_DELIVERY, "updated_at_ms < ?", new String[]{ String.valueOf(cutoff) });
    }

    /** Upsert un RESULT final (DONE). */
    public void upsertDone(String jobId, JSONObject result, long nowMs) {
        if (jobId == null) jobId = "";
        if (result == null) result = new JSONObject();

        ContentValues cv = new ContentValues();
        cv.put("job_id", jobId);

        cv.put("numero_livraison", optString(result, "numero_livraison"));
        cv.put("ticket_no", optString(result, "ticket_no"));
        cv.put("serial_id", optString(result, "serial_id"));
        cv.put("compartment", optNullableString(result, "compartment"));
        cv.put("product_number", optInt(result, "product_number"));

        cv.put("delivery_uid", optString(result, "delivery_uid"));

        cv.put("start_ms", optLong(result, "start_ms"));
        cv.put("end_ms", optLong(result, "end_ms"));

        cv.put("gross_delta", optLong(result, "gross_delta"));
        cv.put("net_delta", optLong(result, "net_delta"));

        cv.put("gross_total", optLong(result, "gross_total"));
        cv.put("net_total", optLong(result, "net_total"));

        cv.put("inventory_written", optNullableString(result, "inventory_written"));
        cv.put("host_printed", optBoolInt(result, "host_printed"));

        cv.put("gross_delta_l", optDouble(result, "gross_delta_l"));
        cv.put("net_delta_l", optDouble(result, "net_delta_l"));
        cv.put("gross_total_l", optDouble(result, "gross_total_l"));
        cv.put("net_total_l", optDouble(result, "net_total_l"));

        cv.put("result_json", result.toString());

        // created_at_ms: si existe deja, on le conserve; sinon now
        SQLiteDatabase db = dbh.getWritableDatabase();
        Long existingCreated = getCreatedAt(db, jobId);
        cv.put("created_at_ms", existingCreated != null ? existingCreated : nowMs);
        cv.put("updated_at_ms", nowMs);

        db.insertWithOnConflict(DeliveryLogDbHelper.T_DELIVERY, null, cv, SQLiteDatabase.CONFLICT_REPLACE);

        // rotation 7 jours
        purgeOld(nowMs);
    }

    /** Lecture brute du RESULT JSON via jobId (fallback). */
    public JSONObject getResultByJobId(String jobId) {
        SQLiteDatabase db = dbh.getReadableDatabase();
        Cursor c = db.query(
                DeliveryLogDbHelper.T_DELIVERY,
                new String[]{"result_json"},
                "job_id = ?",
                new String[]{ jobId },
                null, null, null
        );
        try {
            if (!c.moveToFirst()) return null;
            String raw = c.getString(0);
            if (raw == null || raw.isEmpty()) return null;
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        } finally {
            try { c.close(); } catch (Exception ignore) {}
        }
    }

    private static Long getCreatedAt(SQLiteDatabase db, String jobId) {
        Cursor c = db.query(
                DeliveryLogDbHelper.T_DELIVERY,
                new String[]{"created_at_ms"},
                "job_id = ?",
                new String[]{ jobId },
                null, null, null
        );
        try {
            if (!c.moveToFirst()) return null;
            return c.getLong(0);
        } catch (Exception e) {
            return null;
        } finally {
            try { c.close(); } catch (Exception ignore) {}
        }
    }

    private static String optString(JSONObject o, String k) {
        try { return o.optString(k, ""); } catch (Exception e) { return ""; }
    }

    private static String optNullableString(JSONObject o, String k) {
        try {
            Object v = o.opt(k);
            if (v == null || v == JSONObject.NULL) return null;
            return String.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static int optInt(JSONObject o, String k) {
        try { return o.optInt(k, 0); } catch (Exception e) { return 0; }
    }

    private static long optLong(JSONObject o, String k) {
        try { return o.optLong(k, 0L); } catch (Exception e) { return 0L; }
    }

    private static double optDouble(JSONObject o, String k) {
        try { return o.optDouble(k, 0.0); } catch (Exception e) { return 0.0; }
    }

    private static int optBoolInt(JSONObject o, String k) {
        try { return o.optBoolean(k, false) ? 1 : 0; } catch (Exception e) { return 0; }
    }
}
