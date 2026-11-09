package com.pa.lcrdemo;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.pa.lcr.lcp.storage.DemandeSupportStore;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ✅ AJOUTÉ (9 nov 2026, demande Paul) — suivi des demandes de support, même écran de support fermé.
 *
 * Tant qu'une demande (table demande_support) attend la réponse du support, on regarde la boîte de réception
 * (SupportGraphSender.chercherTicket) toutes les SUPPORT_TICKET_SONDE_MS, jusqu'à SUPPORT_SUIVI_HEURES heures.
 * Dès que la réponse arrive : la ligne passe à REPONDUE avec son n° de ticket, et les écouteurs sont avertis
 * (la flamme de l'écran simplifié devient bleue, l'écran de support affiche le ticket).
 */
final class SupportSuivi {

    private static final String TAG = "SupportSuivi";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final List<Runnable> ECOUTEURS = new CopyOnWriteArrayList<>();

    private static boolean actif = false;       // une boucle de surveillance est déjà planifiée
    private static boolean enCours = false;     // une vérification est en train de s'exécuter

    private SupportSuivi() {}

    static void ecouter(Runnable r) { if (r != null && !ECOUTEURS.contains(r)) ECOUTEURS.add(r); }
    static void retirer(Runnable r) { ECOUTEURS.remove(r); }

    static void avertir() {
        UI.post(() -> { for (Runnable r : ECOUTEURS) { try { r.run(); } catch (Throwable ignored) {} } });
    }

    /** À appeler au démarrage de l'écran simplifié et après chaque nouvel envoi. Sans effet si déjà en route. */
    static synchronized void demarrer(final Context ctx) {
        if (actif) return;
        actif = true;
        planifier(ctx.getApplicationContext(), 3_000L);
    }

    private static void planifier(final Context app, long delaiMs) {
        UI.postDelayed(() -> verifier(app), delaiMs);
    }

    private static void verifier(final Context app) {
        if (enCours) { planifier(app, SimplifiedConfig.SUPPORT_TICKET_SONDE_MS); return; }
        enCours = true;
        new Thread(() -> {
            final DemandeSupportStore store = new DemandeSupportStore(app);
            final List<DemandeSupportStore.Row> attente;
            try {
                attente = store.enAttente(SimplifiedConfig.SUPPORT_SUIVI_HEURES * 3_600_000L);
            } catch (Exception e) {
                Log.w(TAG, "lecture BD ERR: " + e.getMessage());
                fin(app, false);
                return;
            }
            if (attente.isEmpty()) { fin(app, false); return; }   // rien à surveiller : la boucle s'arrête

            // On interroge la boîte de réception depuis la plus ancienne demande en attente.
            final long depuis = attente.get(0).envoyeLe;
            SupportGraphSender.chercherTicket(app, attente, depuis, new SupportGraphSender.Ticket() {
                @Override public void trouve(String reference, String numero) {
                    try {
                        store.marquerRepondue(reference, numero == null ? "" : numero);
                        Log.i(TAG, "réponse du support pour " + reference + " : " + numero);
                        avertir();
                    } catch (Exception e) {
                        Log.w(TAG, "marquerRepondue ERR: " + e.getMessage());
                    }
                }
                @Override public void termine() { fin(app, true); }
            });
        }, "support-suivi").start();
    }

    private static void fin(Context app, boolean continuer) {
        enCours = false;
        if (continuer) {
            planifier(app, SimplifiedConfig.SUPPORT_TICKET_SONDE_MS);
        } else {
            synchronized (SupportSuivi.class) { actif = false; }
        }
    }
}
