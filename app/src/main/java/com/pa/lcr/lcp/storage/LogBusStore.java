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
 *
 * ⚠️ NE PAS isoler cette table dans un fichier SQLite séparé — piste explorée puis
 * ANNULÉE (12 août 2026) : log_bus_event est lue directement par DeliveryDb
 * (vue v_diagnostic_events, moteur de règles), DeliveryLogStore, SyncWatermarkStore
 * et MainActivity (dialogue "Processus lié", RCA). La déplacer casserait
 * silencieusement toutes ces fonctionnalités existantes. Le vrai problème de
 * contention doit se régler autrement (ex: transactions groupées) — pas en isolant
 * la table.
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
    // ✅ FIX CRITIQUE (12 août 2026, demande Paul — "il y a qq chose qui
    // ralentit le tab", confirmé même log fermé) — trouvé : chaque
    // événement déclenchait sa PROPRE insertion + validation (commit)
    // SQLite séparée, sur le MÊME fichier physique (lcr_delivery.db) que
    // 10 autres magasins critiques (ActiveDeliveryStore, etc.). Avec des
    // dizaines d'événements/seconde pendant une livraison active — peu
    // importe si un tab affiche son log, puisque l'écouteur est
    // enregistré sans condition — chaque commit séparé (coût réel
    // fsync/journal à chaque fois) créait une vraie contention avec les
    // opérations critiques sur ce même fichier. Isoler la table dans un
    // fichier séparé a été essayé puis ANNULÉ (casse plusieurs lecteurs
    // existants — voir note de classe). Corrigé autrement : les
    // événements s'accumulent maintenant dans une file en mémoire,
    // vidée en UNE SEULE transaction toutes les 250ms — même table,
    // mêmes lecteurs, mais un seul commit au lieu de dizaines.
    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> pendingEvents =
        new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.concurrent.atomic.AtomicBoolean flushScheduled =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private static final long FLUSH_INTERVAL_MS = 250;

    public LogBusStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
    }

    public void addEventAsync(int node, String src, String msg) {
        pendingEvents.add(new Object[]{System.currentTimeMillis(), node, src != null ? src : "", msg != null ? msg : ""});
        if (flushScheduled.compareAndSet(false, true)) {
            io.execute(() -> {
                try { Thread.sleep(FLUSH_INTERVAL_MS); } catch (InterruptedException ignored) {}
                flushScheduled.set(false);
                flushPending();
            });
        }
    }

    private void flushPending() {
        java.util.ArrayList<Object[]> batch = new java.util.ArrayList<>();
        Object[] e;
        while ((e = pendingEvents.poll()) != null) batch.add(e);
        if (batch.isEmpty()) return;
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                for (Object[] row : batch) {
                    ContentValues cv = new ContentValues();
                    cv.put("ts", (Long) row[0]);
                    cv.put("node", (Integer) row[1]);
                    cv.put("src", (String) row[2]);
                    cv.put("msg", (String) row[3]);
                    db.insert("log_bus_event", null, cv);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            if (writeCounter.addAndGet(batch.size()) >= PRUNE_CHECK_EVERY_N_WRITES) {
                writeCounter.set(0);
                pruneIfNeeded(db);
            }
        } catch (Exception ex) {
            // Best-effort seulement — des lignes de log perdues ne doivent jamais faire
            // échouer quoi que ce soit d'autre dans l'app.
            Log.w(TAG, "flushPending ERR: " + ex.getMessage());
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
