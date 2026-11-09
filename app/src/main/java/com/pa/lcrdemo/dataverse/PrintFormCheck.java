package com.pa.lcrdemo.dataverse;

import android.content.Context;
import android.util.Log;

import com.pa.lcrdemo.auth.MsalTokenProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Route GET /v1/print/form-check?code=X&version=Y : le service d'impression demande si le formulaire du deeplink
 * est a jour (9 nov 2026). Le service n'attend que 3 s : au-dela de {@link #DELAI_REPONSE_MS} on repond
 * MAJ_EN_COURS et la verification continue en arriere-plan (la mise a jour est poussee au service des qu'elle
 * est prete). Statuts : A_JOUR | MAJ_APPLIQUEE | MAJ_EN_COURS | INDISPONIBLE. Jamais d'exception.
 */
public final class PrintFormCheck {

    private static final String TAG = "PrintFormCheck";
    public static final int DELAI_REPONSE_MS = 2500;
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();

    private PrintFormCheck() { }

    public static String verifier(final Context ctx, final String code, final String versionService) {
        if (code == null || code.trim().isEmpty()) return "INDISPONIBLE";
        Future<String> f = EXEC.submit(() -> {
            String token = jeton(ctx);
            if (token == null) return "INDISPONIBLE";
            return PrintDataverseSync.verifierFormulaire(ctx, token, code.trim(), versionService);
        });
        try {
            return f.get(DELAI_REPONSE_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return "MAJ_EN_COURS"; // la tache continue et poussera la mise a jour au service
        } catch (Exception e) {
            Log.w(TAG, "form-check ERR : " + e);
            return "INDISPONIBLE";
        }
    }

    /** Jeton Dataverse en silence (sans activite). null si indisponible. Respecte MSAL_SERIAL_LOCK. */
    private static String jeton(Context ctx) {
        final AtomicReference<String> tok = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean libere = new AtomicBoolean(false);
        boolean pris = false;
        try {
            pris = MsalTokenProvider.MSAL_SERIAL_LOCK.tryAcquire(1500, TimeUnit.MILLISECONDS);
            if (!pris) return null;
            final MsalTokenProvider tp = new MsalTokenProvider(ctx);
            tp.init(new MsalTokenProvider.InitCallback() {
                @Override public void onReady() {
                    tp.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                        @Override public void onSuccess(String accessToken) {
                            tok.set(accessToken);
                            if (libere.compareAndSet(false, true)) MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                            latch.countDown();
                        }
                        @Override public void onError(Exception e) {
                            if (libere.compareAndSet(false, true)) MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                            latch.countDown();
                        }
                    });
                }
                @Override public void onError(Exception e) {
                    if (libere.compareAndSet(false, true)) MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                    latch.countDown();
                }
            });
            latch.await(2000, TimeUnit.MILLISECONDS);
            return tok.get();
        } catch (Exception e) {
            if (pris && libere.compareAndSet(false, true)) MsalTokenProvider.MSAL_SERIAL_LOCK.release();
            return null;
        }
    }
}
