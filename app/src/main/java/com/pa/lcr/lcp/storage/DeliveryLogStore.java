
package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

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
        cv.put("serial_id", serialId);
        cv.put("ticket_no", ticketNo);
        cv.put("sale_no", saleNo);
        cv.put("last_state", lastState);
        cv.put("last_source", source);
        cv.put("last_job_id", jobId);
        cv.put("first_ts", firstTs);
        cv.put("last_ts", now);
        cv.put("result_json", resultJson);
        cv.put("error_json", errorJson);

        db.insertWithOnConflict("delivery_summary", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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
}
