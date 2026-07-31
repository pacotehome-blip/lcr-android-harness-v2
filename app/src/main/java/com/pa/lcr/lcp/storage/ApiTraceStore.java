package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistance async des traces REQ/RESP HTTP de ApiServer, dans la table api_trace.
 *
 * Phase 1c — plan diagnostic intelligent (27 juillet 2026).
 *
 * IMPORTANT : attempt_id est nullable et best-effort seulement (pas de FK vers
 * delivery_attempt). Contrairement à delivery_event (attempt_id NOT NULL + FK stricte,
 * foreign_keys=ON), api_trace accepte des lignes orphelines — c'est le point même de
 * cette table : beaucoup d'appels API arrivent hors du cycle de vie d'un delivery_attempt
 * (ex: api_registerValidate avant qu'une livraison ne soit créée).
 *
 * Écriture toujours sur un thread séparé pour ne jamais ralentir le chemin de réponse HTTP.
 */
public class ApiTraceStore {

    private final DeliveryDb helper;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public ApiTraceStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
    }

    /**
     * @param method       HTTP method (GET/POST/...)
     * @param path         chemin de la requête (sans query string)
     * @param status       code HTTP de la réponse (200, etc.) — peut être null si erreur avant réponse
     * @param durationMs   durée totale du traitement, en millisecondes
     * @param serialId     best-effort, extrait du body JSON ou de la query string (peut être null)
     * @param ticketNo     best-effort (peut être null)
     * @param attemptId    best-effort, peut être null si aucun delivery_attempt résolu
     * @param detailShort  court résumé optionnel (ex: message d'erreur tronqué)
     */
    public void addTraceAsync(String method, String path, Integer status, Long durationMs,
                              String serialId, String ticketNo, Long attemptId, String detailShort) {
        io.execute(() -> addTrace(method, path, status, durationMs, serialId, ticketNo, attemptId, detailShort));
    }

    private void addTrace(String method, String path, Integer status, Long durationMs,
                          String serialId, String ticketNo, Long attemptId, String detailShort) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();

            ContentValues cv = new ContentValues();
            cv.put("ts", System.currentTimeMillis());
            cv.put("method", method);
            cv.put("path", path);
            if (status != null) cv.put("status", status); else cv.putNull("status");
            if (durationMs != null) cv.put("duration_ms", durationMs); else cv.putNull("duration_ms");
            if (serialId != null) cv.put("serial_id", serialId); else cv.putNull("serial_id");
            if (ticketNo != null) cv.put("ticket_no", ticketNo); else cv.putNull("ticket_no");
            if (attemptId != null) cv.put("attempt_id", attemptId); else cv.putNull("attempt_id");
            if (detailShort != null) cv.put("detail_short", trunc(detailShort, 240)); else cv.putNull("detail_short");

            db.insert("api_trace", null, cv);
        } catch (Exception ignored) {
            // Best-effort seulement : une trace API perdue ne doit jamais faire échouer une requête réelle.
        }
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return (s.length() <= max) ? s : s.substring(0, max);
    }
}