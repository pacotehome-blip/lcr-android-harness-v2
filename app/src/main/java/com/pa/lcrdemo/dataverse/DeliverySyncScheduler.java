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
    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "pourquoi j'ai eu du lag
    // pendant le running_flowing... on avait réglé ça une fois pour
    // toute") — trouvé (log réel confirmé) : triggerNow() utilisait
    // enqueue() SIMPLE, sans déduplication — quand plusieurs mécanismes
    // (récupération running_flowing, finalisation orpheline,
    // retournerAuWorkOrder) déclenchaient tous une synchronisation
    // presque simultanément pour le MÊME événement, WorkManager créait
    // AUTANT de travaux séparés, chacun refaisant sa propre
    // authentification MSAL complète (confirmé : 4 cycles MSAL distincts
    // en moins d'une seconde dans un vrai log) — la vraie cause du lag
    // du tick juste après. Même patron déjà établi pour
    // schedulePeriodic() ci-dessous, appliqué ici aussi.
    private static final String TRIGGER_NOW_NAME = "lcr-delivery-sync-triggernow";

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

        WorkManager.getInstance(context).enqueueUniqueWork(
            TRIGGER_NOW_NAME,
            androidx.work.ExistingWorkPolicy.KEEP,
            req
        );
    }
}