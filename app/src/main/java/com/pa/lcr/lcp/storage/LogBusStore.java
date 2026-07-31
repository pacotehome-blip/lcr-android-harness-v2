package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persistance async de TOUS les événements LogBus (UI/API/IO_TX/IO_RX) — demande Paul,
 * 31 juillet 2026 : "tout persister" pour que le log de l'onglet devienne une vraie
 * source de diagnostic (RCA après coup), pas seulement un panneau UI éphémère perdu au
 * redémarrage.
 *
 * Écriture toujours sur un thread séparé pour ne jamais ralentir l'émission LogBus
 * (potentiellement très fréquente en TX/RX continu).
 *
 * Rotation par ÂGE (7 jours) — politique choisie avec Paul le 31 juillet 2026 : volume
 * modéré (~20-30 livraisons/jour/camion), donc une fenêtre calendaire prévisible ("toujours
 * les 7 derniers jours") est plus utile pour le RCA qu'une limite par comptage, qui aurait
 * représenté une durée variable selon l'activité. MAX_ROWS_SAFETY_NET reste en garde-fou
 * seulement (cas anormal : volume explosif qui remplirait le disque avant 7 jours).
 */
public class LogBusStore {

    private static final String TAG = "LogBusStore";
    private static final int RETENTION_DAYS = 7;
    private static final long RETENTION_MS = RETENTION_DAYS * 24L * 60 * 60 * 1000;
    private static final long MAX_ROWS_SAFETY_NET = 2_000_000; // garde-fou seulement — ne devrait jamais être atteint en usage normal
    private static final int PRUNE_CHECK_EVERY_N_WRITES = 500;

    private final DeliveryDb helper;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger writeCounter = new AtomicInteger(0);

    public LogBusStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
    }

    public void addEventAsync(int node, String src, String msg) {
        io.execute(() -> addEvent(node, src, msg));
    }

    private void addEvent(int node, String src, String msg) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("ts", System.currentTimeMillis());
            cv.put("node", node);
            cv.put("src", src != null ? src : "");
            cv.put("msg", msg != null ? msg : "");
            db.insert("log_bus_event", null, cv);

            if (writeCounter.incrementAndGet() % PRUNE_CHECK_EVERY_N_WRITES == 0) {
                pruneIfNeeded(db);
            }
        } catch (Exception e) {
            // Best-effort seulement — une ligne de log perdue ne doit jamais faire
            // échouer quoi que ce soit d'autre dans l'app.
            Log.w(TAG, "addEvent ERR: " + e.getMessage());
        }
    }

    private void pruneIfNeeded(SQLiteDatabase db) {
        try {
            // 1. Rotation principale : purge tout ce qui a plus de RETENTION_DAYS jours
            long cutoffTs = System.currentTimeMillis() - RETENTION_MS;
            int deletedByAge = db.delete("log_bus_event", "ts < ?", new String[]{String.valueOf(cutoffTs)});
            if (deletedByAge > 0) {
                Log.i(TAG, "pruneIfNeeded: " + deletedByAge + " ligne(s) purgée(s) (plus de "
                        + RETENTION_DAYS + " jours)");
            }

            // 2. Garde-fou seulement : si le volume explose avant même d'atteindre RETENTION_DAYS jours
            // (cas anormal — ex: bug qui spamme LogBus), purge aussi par comptage.
            long count;
            try (android.database.Cursor c = db.rawQuery("SELECT COUNT(*) FROM log_bus_event", null)) {
                count = c.moveToFirst() ? c.getLong(0) : 0;
            }
            if (count > MAX_ROWS_SAFETY_NET) {
                long excess = count - MAX_ROWS_SAFETY_NET;
                db.execSQL("DELETE FROM log_bus_event WHERE id IN (" +
                        "SELECT id FROM log_bus_event ORDER BY id ASC LIMIT " + excess + ")");
                Log.w(TAG, "pruneIfNeeded: garde-fou déclenché — " + excess
                        + " ligne(s) purgée(s) (volume anormalement élevé avant " + RETENTION_DAYS + " jours)");
            }
        } catch (Exception e) {
            Log.w(TAG, "pruneIfNeeded ERR: " + e.getMessage());
        }
    }
}
