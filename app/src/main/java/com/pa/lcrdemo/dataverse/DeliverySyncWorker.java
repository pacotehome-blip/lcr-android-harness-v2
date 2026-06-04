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

        for (DeliveryResultQueueDb.QueueItem item : pending) {
            try {
                org.json.JSONObject j = new org.json.JSONObject(item.payloadJson);
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

        return hadFailure ? Result.retry() : Result.success();
    }
}