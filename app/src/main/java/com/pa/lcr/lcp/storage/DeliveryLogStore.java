package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
    public static final String SOURCE_UI = "UI";

    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
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

        // ✅ FIX (6 août 2026, demande Paul — "on devrait considérer la charge
        // imposée sur la tablette et la quantité limite de SQLite... un
        // entretien systématique pour conserver les 7 derniers jours") —
        // trouvé : ce purge ne touchait QUE delivery_summary, en supposant
        // (à tort) une cascade FK vers les autres tables. log_bus_event
        // (et plusieurs autres) n'ont AUCUNE relation de clé étrangère avec
        // delivery_summary — elles grossissaient donc indéfiniment, jamais
        // nettoyées. Preuve concrète : 1780 lignes dans log_bus_event en
        // seulement 27 minutes d'un seul test — sur 7 jours réels, ça peut
        // facilement atteindre des centaines de milliers de lignes, chacune
        // interrogée à chaque affichage de l'onglet Support. Purge directe
        // ajoutée pour chaque table indépendante par sa propre colonne de
        // temps.
        try { db.delete("log_bus_event", "ts < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        try { db.delete("api_trace", "ts < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        try { db.delete("diagnostic_match_history", "ts < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        try { db.delete("media_event", "ts < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        try { db.delete("truck_drift", "ts_ms < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        try { db.delete("bt_signal", "ts_ms < ?", new String[]{Long.toString(cutoff)}); } catch (Exception ignored) {}
        // ✅ incident_history est un historique de diagnostic délibérément gardé
        // plus longtemps (utile pour repérer des patterns récurrents) — purge
        // à 30 jours plutôt que 7, pas la même politique que les logs bruts.
        try {
            long cutoffIncidents = System.currentTimeMillis() - (30L * 24L * 60L * 60L * 1000L);
            db.delete("incident_history", "created_ts < ?", new String[]{Long.toString(cutoffIncidents)});
        } catch (Exception ignored) {}

        try {
            android.util.Log.i("DeliveryLogStore", "purgeOlderThanDays(" + days + "): entretien terminé");
        } catch (Exception ignored) {}
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
        cv.put("sale_no", saleNo);
        cv.put("last_state", lastState);
        cv.put("last_source", source);
        cv.put("last_job_id", jobId);
        cv.put("first_ts", firstTs);
        cv.put("last_ts", now);
        cv.put("result_json", resultJson);
        cv.put("error_json", errorJson);

        int rows = db.update(
                "delivery_summary",
                cv,
                "serial_id=? AND ticket_no=?",
                new String[]{serialId, ticketNo}
        );
        if (rows <= 0) {
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

    // v2: update time columns in delivery_summary
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

    // v4: structured fields helper
    private static final class StructuredFields {
        final String eventLevel;
        final String eventCode;
        final String eventWhere;
        final String detailShort;

        StructuredFields(String eventLevel, String eventCode, String eventWhere, String detailShort) {
            this.eventLevel = eventLevel;
            this.eventCode = eventCode;
            this.eventWhere = eventWhere;
            this.detailShort = detailShort;
        }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, max);
    }

    /**
     * Best-effort extraction of structured fields from a JSON string.
     * Supports:
     * - ApiResult JSON: {code,msg,err,data:{level,where,detail,...}}
     * - Data JSON: {level,where,detail,...}
     */
    private static StructuredFields extractStructuredFromJson(String dataJson) {
        String evLevel = null;
        String evCode = null;
        String evWhere = null;
        String detail = null;

        String s = trimOrNull(dataJson);
        if (s == null) return new StructuredFields(null, null, null, null);
        if (!s.startsWith("{") || !s.endsWith("}")) return new StructuredFields(null, null, null, trunc(s, 240));

        try {
            JSONObject root = new JSONObject(s);

            Object dataObj = root.opt("data");
            if (dataObj instanceof JSONObject) {
                JSONObject d = (JSONObject) dataObj;
                evLevel = trimOrNull(d.optString("level", null));
                evWhere = trimOrNull(d.optString("where", null));
                detail = trimOrNull(d.optString("detail", null));
            } else {
                evLevel = trimOrNull(root.optString("level", null));
                evWhere = trimOrNull(root.optString("where", null));
                detail = trimOrNull(root.optString("detail", null));
            }

            Object errObj = root.opt("err");
            if (errObj != null && errObj != JSONObject.NULL) {
                evCode = trimOrNull(String.valueOf(errObj));
                if ("null".equalsIgnoreCase(evCode)) evCode = null;
            }

            if (evCode == null) evCode = trimOrNull(root.optString("event_code", null));
            if (evCode == null) evCode = trimOrNull(root.optString("code", null));

        } catch (JSONException ignored) {
            detail = trunc(s, 240);
        }

        return new StructuredFields(evLevel, evCode, evWhere, trunc(detail, 240));
    }

    public void addEventAsync(long attemptId, String level, String type, String message, String dataJson) {
        io.execute(() -> addEvent(attemptId, level, type, message, dataJson, null, null, null, null));
    }

    /**
     * v4: structured event fields (optional).
     */
    public void addEventAsync(long attemptId, String level, String type, String message, String dataJson,
                              String eventLevel, String eventCode, String eventWhere, String detailShort) {
        final String el = eventLevel;
        final String ec = eventCode;
        final String ew = eventWhere;
        final String ds = detailShort;
        io.execute(() -> addEvent(attemptId, level, type, message, dataJson, el, ec, ew, ds));
    }

    public void addEvent(long attemptId, String level, String type, String message, String dataJson) {
        addEvent(attemptId, level, type, message, dataJson, null, null, null, null);
    }

    public void addEvent(long attemptId, String level, String type, String message, String dataJson,
                         String eventLevel, String eventCode, String eventWhere, String detailShort) {

        long now = System.currentTimeMillis();
        SQLiteDatabase db = helper.getWritableDatabase();

        StructuredFields sf = null;
        if (eventLevel == null || eventCode == null || eventWhere == null || detailShort == null) {
            sf = extractStructuredFromJson(dataJson);
        }
        String evLevel = (eventLevel != null) ? trimOrNull(eventLevel) : (sf != null ? sf.eventLevel : null);
        String evCode = (eventCode != null) ? trimOrNull(eventCode) : (sf != null ? sf.eventCode : null);
        String evWhere = (eventWhere != null) ? trimOrNull(eventWhere) : (sf != null ? sf.eventWhere : null);
        String det = (detailShort != null) ? trunc(detailShort, 240) : (sf != null ? sf.detailShort : null);

        ContentValues cv = new ContentValues();
        cv.put("attempt_id", attemptId);
        cv.put("ts", now);
        cv.put("level", level);
        cv.put("type", type);
        cv.put("message", message);
        cv.put("data_json", dataJson);

        cv.put("event_level", evLevel);
        cv.put("event_code", evCode);
        cv.put("event_where", evWhere);
        cv.put("detail_short", det);

        db.insert("delivery_event", null, cv);
    }

    // =========================================================
    // Backup helpers (WAL-safe single-file backups)
    // =========================================================

    public void checkpointWalBestEffort() {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            // ✅ FIX : PRAGMA wal_checkpoint(FULL) retourne un résultat (busy/log/
            // checkpointed) — execSQL() refuse d'exécuter toute instruction qui
            // produit un résultat ("Queries can be performed using SQLiteDatabase
            // query or rawQuery methods only"). Il faut rawQuery() ici, à l'inverse
            // de la convention habituelle pour un PRAGMA.
            try (android.database.Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL);", null)) {
                if (c != null && c.moveToFirst()) {
                    android.util.Log.i("DeliveryLogStore",
                        "WAL checkpoint: busy=" + c.getInt(0)
                        + " log=" + c.getInt(1) + " checkpointed=" + c.getInt(2));
                }
            }
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

    /**
     * ✅ FIX Android 9/10: avoid MediaStore.Downloads (can crash on API 28).
     * - API 29+: use MediaStore.Files + RELATIVE_PATH=Downloads
     * - API 28- : write directly to /Download (requires legacy storage permission)
     */
    public boolean backupDbToDownloads(Context ctx, String fileName) throws Exception {
        File dbFile = ctx.getDatabasePath(DeliveryDb.DB_NAME);
        if (dbFile == null || !dbFile.exists()) {
            throw new Exception("DB file not found: " + DeliveryDb.DB_NAME);
        }

        checkpointWalBestEffort();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-sqlite3");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
            if (uri == null) throw new Exception("MediaStore insert failed");

            try (InputStream in = new FileInputStream(dbFile);
                 OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("openOutputStream failed");
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            return true;
        }

        // API 28-
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new Exception("Downloads dir not found");
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new Exception("Cannot create Downloads dir");
        }
        File outFile = new File(downloads, fileName);
        try (InputStream in = new FileInputStream(dbFile);
             FileOutputStream out = new FileOutputStream(outFile, false)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
        }
        return true;
    }

    /**
     * ✅ FIX Android 9/10: avoid MediaStore.Downloads (can crash on API 28).
     * - API 29+: use MediaStore.Files + RELATIVE_PATH=Downloads
     * - API 28- : write directly to /Download (requires legacy storage permission)
     */
    public boolean dumpJsonToDownloads(Context ctx, String fileName) throws Exception {
        byte[] bytes = buildDumpJson().getBytes(StandardCharsets.UTF_8);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = ctx.getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
            if (uri == null) throw new Exception("MediaStore insert failed");

            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new Exception("openOutputStream failed");
                out.write(bytes);
                out.flush();
            }

            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, done, null, null);
            return true;
        }

        // API 28-
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads == null) throw new Exception("Downloads dir not found");
        if (!downloads.exists() && !downloads.mkdirs()) {
            throw new Exception("Cannot create Downloads dir");
        }
        File outFile = new File(downloads, fileName);
        try (FileOutputStream fos = new FileOutputStream(outFile, false)) {
            fos.write(bytes);
            fos.flush();
        }
        return true;
    }

    // ✅ (4 août 2026, demande Paul) — même contenu que dumpJsonToDownloads(),
    // mais retourné en mémoire pour être servi directement en réponse HTTP
    // (téléchargement JSON pour le support via /v1/db/dump/download), sans
    // passer par le système de fichiers Downloads. Les deux méthodes partagent
    // maintenant la même construction pour ne jamais diverger.
    public String buildDumpJson() {
        SQLiteDatabase db = helper.getReadableDatabase();
        StringBuilder sb = new StringBuilder(1024 * 256);
        sb.append("{\"delivery_summary\":");
        sb.append(queryTableAsJsonArray(db, "delivery_summary"));
        sb.append(",\"delivery_attempt\":");
        sb.append(queryTableAsJsonArray(db, "delivery_attempt"));
        sb.append(",\"delivery_event\":");
        sb.append(queryTableAsJsonArray(db, "delivery_event"));
        sb.append("}");
        return sb.toString();
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
        } catch (Exception ignored) {
        }
        sb.append("]");
        return sb.toString();
    }

    private static Object getCursorValue(Cursor c, int i) {
        switch (c.getType(i)) {
            case Cursor.FIELD_TYPE_NULL:
                return null;
            case Cursor.FIELD_TYPE_INTEGER:
                return c.getLong(i);
            case Cursor.FIELD_TYPE_FLOAT:
                return c.getDouble(i);
            case Cursor.FIELD_TYPE_STRING:
                return c.getString(i);
            case Cursor.FIELD_TYPE_BLOB:
                return c.getBlob(i);
            default:
                return null;
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
    // READ helper: last RESULT for a serial_id (delivery_summary)
    // =========================================================

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
