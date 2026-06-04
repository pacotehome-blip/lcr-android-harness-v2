package com.pa.lcrdemo.dataverse;

import android.content.Context;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import java.util.concurrent.TimeUnit;

/**
 * DeliverySyncScheduler — Planifie le WorkManager pour vider la queue offline.
 *
 * - schedulePeriodic() : toutes les 15 minutes si réseau disponible
 * - triggerNow()       : déclencher immédiatement après une livraison
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/DeliverySyncScheduler.java
 */
public class DeliverySyncScheduler {

    private static final String PERIODIC_NAME = "lcr-delivery-sync-periodic";

    // ✅ Planifier un sync périodique (toutes les 15 min, réseau requis)
    public static void schedulePeriodic(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
            DeliverySyncWorker.class,
            15, TimeUnit.MINUTES
        )
        .setConstraints(constraints)
        .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            req
        );
    }

    // ✅ Déclencher immédiatement (appelé après chaque livraison)
    public static void triggerNow(Context context) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(DeliverySyncWorker.class)
            .setConstraints(constraints)
            .build();

        WorkManager.getInstance(context).enqueue(req);
    }
}