package com.pa.lcrdemo.dataverse;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.pa.lcrdemo.auth.MsalTokenProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeliverySyncWorker — WorkManager worker qui vide la queue offline.
 * Déclenché automatiquement quand le réseau revient.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/DeliverySyncWorker.java
 */
public class DeliverySyncWorker extends Worker {

    private static final String TAG = "DeliverySyncWorker";

    public DeliverySyncWorker(@NonNull Context context,
                               @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        DeliveryResultQueueDb db = new DeliveryResultQueueDb(ctx);
        try {
            return doWorkInternal(ctx, db);
        } finally {
            // ✅ FIX : db (SQLiteOpenHelper sur delivery_sync.db) n'était jamais
            // fermée, peu importe le chemin de sortie (queue vide, token absent,
            // succès, échec) — d'où les "SQLiteConnection leaked" sur
            // delivery_sync_db observés dans les logs.
            try { db.close(); } catch (Exception ignored) {}
        }
    }

    private Result doWorkInternal(Context ctx, DeliveryResultQueueDb db) {
        java.util.List<DeliveryResultQueueDb.QueueItem> pending = db.listPending(20);
        if (pending.isEmpty()) {
            Log.i(TAG, "Queue vide — rien à envoyer");
            return Result.success();
        }

        Log.i(TAG, "Queue: " + pending.size() + " livraisons à envoyer");

        // ✅ Acquérir token MSAL — bloquant car on est dans un Worker thread
        MsalTokenProvider tokenProvider = new MsalTokenProvider(ctx);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> tokenRef = new AtomicReference<>();
        AtomicReference<Exception> errRef = new AtomicReference<>();

        tokenProvider.init(new MsalTokenProvider.InitCallback() {
            @Override
            public void onReady() {
                // Worker n'a pas d'Activity — utiliser token silent uniquement
                tokenProvider.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                    @Override
                    public void onSuccess(String token) {
                        tokenRef.set(token);
                        latch.countDown();
                    }
                    @Override
                    public void onError(Exception e) {
                        errRef.set(e);
                        latch.countDown();
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                errRef.set(e);
                latch.countDown();
            }
        });

        try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException e) { return Result.retry(); }

        if (tokenRef.get() == null) {
            Log.w(TAG, "Token non disponible: " + (errRef.get() != null ? errRef.get().getMessage() : "timeout"));
            return Result.retry();
        }

        String token = tokenRef.get();
        boolean hadFailure = false;

        // ✅ FIX : traiter jusqu'à 20 items en boucle, chacun pouvant prendre jusqu'à
        // ~20s (10s connexion + 10s lecture) dans le pire cas, donnait un pire cas
        // théorique de 6-7 minutes pour un seul déclenchement du Worker — un vrai
        // risque de dépasser la fenêtre d'exécution que le système alloue à un
        // JobService, contribuant à l'ANR "executing service SystemJobService".
        // Budget de temps global : arrêter proprement et laisser WorkManager
        // reprogrammer le reste au prochain cycle plutôt que de forcer les 20 items.
        long startMs = System.currentTimeMillis();
        long budgetMs = 60_000; // 60s max pour cette exécution du Worker
        boolean timeBudgetExceeded = false;

        for (DeliveryResultQueueDb.QueueItem item : pending) {
            if (System.currentTimeMillis() - startMs > budgetMs) {
                Log.w(TAG, "Budget de temps dépassé (" + budgetMs + "ms) — arrêt, reste reprogrammé");
                timeBudgetExceeded = true;
                break;
            }
            try {
                org.json.JSONObject j = new org.json.JSONObject(item.payloadJson);

                // ✅ FIX : les retries du PATCH final consolidé (retournerAuWorkOrder,
                // échoué hors ligne) sont marqués "consolidated":true — il faut les
                // traiter avec patchSummaryConsolidated() (fusion+ETag), pas
                // patchSummary() (la version simple qui écraserait l'historique
                // qu'on vient justement de protéger). On relit les livraisons
                // locales fraîches au moment du retry, pas celles capturées au
                // moment de l'échec (qui pourraient être obsolètes).
                if (j.optBoolean("consolidated", false)) {
                    String woNum = j.optString("woNum", "");
                    String workOrderId = j.optString("workOrderId", "");
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(ctx);
                    org.json.JSONArray livraisons = new org.json.JSONArray();
                    try {
                        java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> rows =
                            lcrDb.getAllForWo(woNum);
                        for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : rows) {
                            if (r.netL > 0 || "ANNULATION".equals(r.type)) {
                                org.json.JSONObject entry = new org.json.JSONObject();
                                entry.put("ticket_no", r.ticketNo != null ? r.ticketNo : "");
                                entry.put("net_l",     r.netL);
                                entry.put("gross_l",   r.grossL);
                                entry.put("type",      r.type != null ? r.type : "");
                                entry.put("end_utc",   r.endUtc != null ? r.endUtc : "");
                                livraisons.put(entry);
                            }
                        }
                    } finally {
                        try { lcrDb.close(); } catch (Exception ignored) {}
                    }
                    if (livraisons.length() > 0 && !workOrderId.isEmpty()) {
                        WorkOrderUpdater.patchSummaryConsolidated(token, workOrderId, woNum, livraisons);
                    }
                    db.markSent(item.id);
                    Log.i(TAG, "Envoyé (consolidated retry): " + item.deliveryUid);
                    continue;
                }

                WorkOrderUpdater.patchSummary(
                    token,
                    j.optString("workOrderId", ""),
                    String.valueOf(j.optDouble("netTotal", 0)),
                    String.valueOf(j.optDouble("grossTotal", 0)),
                    j.optString("ticketNo", ""),
                    j.optString("woNum", ""),
                    j.optString("deliveryUid", "")
                );
                db.markSent(item.id);
                Log.i(TAG, "Envoyé: " + item.deliveryUid);
            } catch (Exception e) {
                Log.e(TAG, "Erreur envoi " + item.deliveryUid + ": " + e.getMessage());
                db.markPendingError(item.id, item.retryCount + 1, e.getMessage());
                hadFailure = true;
            }
        }

        return (hadFailure || timeBudgetExceeded) ? Result.retry() : Result.success();
    }
}