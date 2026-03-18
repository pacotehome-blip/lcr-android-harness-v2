
package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Store for delivery traceability.
 * - API side: jobId is set
 * - UI side: jobId is null
 *
 * Business key: (serial_id, ticket_no)
 */
public class DeliveryLogStore {

    public static final String SOURCE_API = "API";
    public static final String SOURCE_UI  = "UI";

    public static final String LEVEL_INFO  = "INFO";
    public static final String LEVEL_WARN  = "WARN";
    public static final String LEVEL_ERROR = "ERROR";

    private final DeliveryDb helper;
    private final Executor io;

    public DeliveryLogStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
        this.io = Executors.newSingleThreadExecutor();
    }

    // ----------------------------
    // Purge / rotation
    // ----------------------------
    public void purgeOlderThanDaysAsync(int days) {
        io.execute(() -> purgeOlderThanDays(days));
    }

    public void purgeOlderThanDays(int days) {
        long cutoff = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L);
        SQLiteDatabase db = helper.getWritableDatabase();
        // Cascades to attempts/events
        db.delete("delivery_summary", "last_ts < ?", new String[]{Long.toString(cutoff)});
    }

    // ----------------------------
    // Summary upsert
    // ----------------------------
    public void upsertSummaryAsync(
            String serialId,
            String ticketNo,
            String saleNo,
            String lastState,
            String source,
            String jobId,
            String resultJson,
            String errorJson
    ) {
        io.execute(() -> upsertSummary(serialId, ticketNo, saleNo, lastState, source, jobId, resultJson, errorJson));
    }

    /**
     * ✅ FIX: Do NOT use CONFLICT_REPLACE on delivery_summary.
     * REPLACE in SQLite is implemented as DELETE + INSERT, which triggers FK ON DELETE CASCADE
     * and wipes delivery_attempt / delivery_event rows.
     *
     * Strategy:
     *  1) Keep first_ts stable by reading existing row if present
     *  2) UPDATE existing row
     *  3) If no row updated => INSERT
     */
    public void upsertSummary(
            String serialId,
            String ticketNo,
            String saleNo,
            String lastState,
            String source,
            String jobId,
            String resultJson,
            String errorJson
    ) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();

        // Keep first_ts stable if row already exists
        long firstTs = now;
        try (Cursor c = db.rawQuery(
                "SELECT first_ts FROM delivery_summary WHERE serial_id=? AND ticket_no=?",
                new String[]{serialId, ticketNo}
        )) {
            if (c.moveToFirst()) {
                firstTs = c.getLong(0);
            }
        }

        ContentValues cv = new ContentValues();
        // NOTE: do NOT update PK columns in update payload; keep them for insert only.
        cv.put("sale_no", saleNo);
        cv.put("last_state", lastState);
        cv.put("last_source", source);
        cv.put("last_job_id", jobId);
        cv.put("first_ts", firstTs);
        cv.put("last_ts", now);
        cv.put("result_json", resultJson);
        cv.put("error_json", errorJson);

        // 1) UPDATE first (no DELETE => no cascade wipe)
        int rows = db.update(
                "delivery_summary",
                cv,
                "serial_id=? AND ticket_no=?",
                new String[]{serialId, ticketNo}
        );

        if (rows <= 0) {
            // 2) INSERT if missing
            ContentValues ins = new ContentValues();
            ins.put("serial_id", serialId);
            ins.put("ticket_no", ticketNo);
            ins.put("sale_no", saleNo);
            ins.put("last_state", lastState);
            ins.put("last_source", source);
            ins.put("last_job_id", jobId);
            ins.put("first_ts", firstTs);
            ins.put("last_ts", now);
            ins.put("result_json", resultJson);
            ins.put("error_json", errorJson);
            db.insert("delivery_summary", null, ins);
        }
    }

    // ✅ NEW (v2): update time columns in delivery_summary
    public void updateSummaryTimesAsync(
            String serialId,
            String ticketNo,
            Long startMs,
            Long endMs,
            String startUtc,
            String endUtc,
            Long durationMs
    ) {
        io.execute(() -> updateSummaryTimes(serialId, ticketNo, startMs, endMs, startUtc, endUtc, durationMs));
    }

    public void updateSummaryTimes(
            String serialId,
            String ticketNo,
            Long startMs,
            Long endMs,
            String startUtc,
            String endUtc,
            Long durationMs
    ) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (startMs != null) cv.put("start_ms", startMs);
        if (endMs != null) cv.put("end_ms", endMs);
        if (startUtc != null) cv.put("start_utc", startUtc);
        if (endUtc != null) cv.put("end_utc", endUtc);
        if (durationMs != null) cv.put("duration_ms", durationMs);
        if (cv.size() == 0) return;
        db.update("delivery_summary", cv, "serial_id=? AND ticket_no=?", new String[]{serialId, ticketNo});
    }

    // ----------------------------
    // Attempts
    // ----------------------------
    public interface AttemptIdCallback {
        void onAttemptId(long attemptId);
    }

    public void openAttemptAsync(String serialId, String ticketNo, String source, String jobId, AttemptIdCallback cb) {
        io.execute(() -> {
            long id = openAttempt(serialId, ticketNo, source, jobId);
            if (cb != null) cb.onAttemptId(id);
        });
    }

    public long openAttempt(String serialId, String ticketNo, String source, String jobId) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("serial_id", serialId);
        cv.put("ticket_no", ticketNo);
        cv.put("source", source);
        cv.put("job_id", jobId);
        cv.put("start_ts", now);
        return db.insert("delivery_attempt", null, cv);
    }

    public void closeAttemptAsync(long attemptId, String outcome, String resultJson, String errorJson) {
        io.execute(() -> closeAttempt(attemptId, outcome, resultJson, errorJson));
    }

    public void closeAttempt(long attemptId, String outcome, String resultJson, String errorJson) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("end_ts", now);
        cv.put("outcome", outcome);
        cv.put("result_json", resultJson);
        cv.put("error_json", errorJson);
        db.update("delivery_attempt", cv, "attempt_id=?", new String[]{Long.toString(attemptId)});
    }

    // ----------------------------
    // Events
    // ----------------------------
    public void addEventAsync(long attemptId, String level, String type, String message, String dataJson) {
        io.execute(() -> addEvent(attemptId, level, type, message, dataJson));
    }

    public void addEvent(long attemptId, String level, String type, String message, String dataJson) {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("attempt_id", attemptId);
        cv.put("ts", now);
        cv.put("level", level);
        cv.put("type", type);
        cv.put("message", message);
        cv.put("data_json", dataJson);
        db.insert("delivery_event", null, cv);
    }

    // =========================================================
    // Backup helpers (WAL-safe single-file backups)
    // =========================================================
    public void checkpointWalBestEffort() {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.execSQL("PRAGMA wal_checkpoint(FULL);");
        } catch (Throwable t) {
            android.util.Log.w("DeliveryLogStore", "WAL checkpoint failed (backup may be incomplete)", t);
        }
    }

    // =========================================================
    // UI: Backup DB to Downloads
    // API: Dump JSON to Downloads
    // =========================================================
    public interface BackupCallback {
        void onDone(boolean ok, String fileName, String detail);
    }

    public void backupDbToDownloadsAsync(Context ctx, String fileName, BackupCallback cb) {
        io.execute(() -> {
            boolean ok;
            String detail = "";
            try {
                ok = backupDbToDownloads(ctx, fileName);
            } catch (Exception e) {
                ok = false;
                detail = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
            }
            if (cb != null) cb.onDone(ok, fileName, detail);
        });
    }

    public boolean backupDbToDownloads(Context ctx, String fileName) throws Exception {
        java.io.File dbFile = ctx.getDatabasePath(DeliveryDb.DB_NAME);
        if (dbFile == null || !dbFile.exists()) {
            throw new Exception("DB file not found: " + DeliveryDb.DB_NAME);
        }

        // WAL-safe single-file backup
        checkpointWalBestEffort();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/x-sqlite3");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("MediaStore insert failed");

        try (InputStream in = new java.io.FileInputStream(dbFile);
             OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {

            if (out == null) throw new Exception("openOutputStream failed");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
        }

        return true;
    }

    public boolean dumpJsonToDownloads(Context ctx, String fileName) throws Exception {
        SQLiteDatabase db = helper.getReadableDatabase();

        StringBuilder sb = new StringBuilder(1024 * 256);
        sb.append("{\"delivery_summary\":");
        sb.append(queryTableAsJsonArray(db, "delivery_summary"));
        sb.append(",\"delivery_attempt\":");
        sb.append(queryTableAsJsonArray(db, "delivery_attempt"));
        sb.append(",\"delivery_event\":");
        sb.append(queryTableAsJsonArray(db, "delivery_event"));
        sb.append("}");

        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Downloads.IS_PENDING, 1);
        }

        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("MediaStore insert failed");

        try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("openOutputStream failed");
            out.write(bytes);
            out.flush();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
        }

        return true;
    }

    private static String queryTableAsJsonArray(SQLiteDatabase db, String table) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        try (Cursor c = db.rawQuery("SELECT * FROM " + table, null)) {
            String[] cols = c.getColumnNames();
            boolean firstRow = true;
            while (c.moveToNext()) {
                if (!firstRow) sb.append(",");
                firstRow = false;
                sb.append("{");
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(cols[i]).append("\":");
                    Object v = getCursorValue(c, i);
                    sb.append(toJsonLiteral(v));
                }
                sb.append("}");
            }
        } catch (Exception ignored) {}
        sb.append("]");
        return sb.toString();
    }

    private static Object getCursorValue(Cursor c, int i) {
        switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_NULL: return null;
            case Cursor.FIELD_TYPE_INTEGER: return c.getLong(i);
            case Cursor.FIELD_TYPE_FLOAT: return c.getDouble(i);
            case Cursor.FIELD_TYPE_STRING: return c.getString(i);
            case Cursor.FIELD_TYPE_BLOB: return c.getBlob(i);
            default: return null;
        }
    }

    private static String toJsonLiteral(Object v) {
        if (v == null) return "null";
        if (v instanceof Number) return String.valueOf(v);
        if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            StringBuilder sb = new StringBuilder();
            sb.append("\"0x");
            for (byte x : b) sb.append(String.format("%02X", x));
            sb.append("\"");
            return sb.toString();
        }
        String s = String.valueOf(v)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
        return "\"" + s + "\"";
    }

    // =========================================================
    // ✅ READ helper: last RESULT for a serial_id (delivery_summary)
    // =========================================================

    /** Lightweight row holder for latest RESULT lookup. */
    public static final class LatestResultRow {
        public final String ticketNo;
        public final String resultJson;
        public final long lastTs;

        public LatestResultRow(String ticketNo, String resultJson, long lastTs) {
            this.ticketNo = ticketNo;
            this.resultJson = resultJson;
            this.lastTs = lastTs;
        }
    }

    /**
     * Return latest non-empty delivery_summary.result_json for the given serial_id.
     * Ordered by last_ts DESC.
     * @return LatestResultRow or null if not found.
     */
    public LatestResultRow getLatestResultBySerial(String serialId) {
        if (serialId == null || serialId.trim().isEmpty()) return null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT ticket_no, result_json, last_ts " +
                            "FROM delivery_summary " +
                            "WHERE serial_id=? AND result_json IS NOT NULL AND result_json<>'' " +
                            "ORDER BY last_ts DESC LIMIT 1",
                    new String[]{serialId.trim()})) {

                if (c.moveToFirst()) {
                    String ticketNo = c.isNull(0) ? null : c.getString(0);
                    String resultJson = c.isNull(1) ? null : c.getString(1);
                    long lastTs = c.isNull(2) ? 0L : c.getLong(2);

                    if (resultJson != null && !resultJson.trim().isEmpty()) {
                        return new LatestResultRow(ticketNo, resultJson, lastTs);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
