package com.pa.lcrdemo.auth;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import com.microsoft.identity.client.AcquireTokenParameters;
import com.microsoft.identity.client.AcquireTokenSilentParameters;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.SilentAuthenticationCallback;
import com.microsoft.identity.client.exception.MsalException;
import com.microsoft.identity.client.exception.MsalUiRequiredException;
import com.pa.lcrdemo.R;
import java.util.Arrays;

/**
 * MsalTokenProvider — Fournit des tokens OAuth pour Dataverse.
 *
 * Pattern recommandé Microsoft :
 *  1) acquireTokenSilent d'abord
 *  2) acquireToken interactif si UI requise (premier login ou token expiré)
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/auth/MsalTokenProvider.java
 */
public class MsalTokenProvider {

    private static final String TAG = "MsalTokenProvider";

    // ✅ (fix 31 juillet 2026, demande Paul : "il ne doit JAMAIS concurrencer aucun
    // processus") — verrou global partagé par TOUTE l'app. Chaque `new MsalTokenProvider(...)`
    // crée une instance MSAL distincte (createSingleAccountPublicClientApplication), et en
    // mode Single Account, plusieurs instances actives en même temps peuvent se disputer le
    // même cache de compte/token sous-jacent. Tout appelant (push existant, pull Dataverse,
    // ou futur code) DOIT envelopper son bloc init+acquireToken dans
    // `synchronized (MsalTokenProvider.MSAL_SERIAL_LOCK) { ... }` pour garantir qu'aucune
    // opération MSAL ne tourne jamais en parallèle d'une autre, où que ce soit dans l'app.
    public static final Object MSAL_SERIAL_LOCK = new Object();

    // ✅ Scope Dataverse — accès via l'utilisateur connecté
    public static final String[] SCOPES = new String[]{
        "https://dev-filgo-sonic.crm3.dynamics.com/.default"
    };

    private final Context appContext;
    private ISingleAccountPublicClientApplication msalApp;

    public MsalTokenProvider(Context context) {
        this.appContext = context.getApplicationContext();
    }

    // =========================================================
    // Init — créer l'instance MSAL
    // =========================================================

    public interface InitCallback {
        void onReady();
        void onError(Exception e);
    }

    public void init(@NonNull InitCallback callback) {
        PublicClientApplication.createSingleAccountPublicClientApplication(
            appContext,
            R.raw.auth_config,
            new PublicClientApplication.ISingleAccountApplicationCreatedListener() {
                @Override
                public void onCreated(ISingleAccountPublicClientApplication app) {
                    msalApp = app;
                    Log.i(TAG, "MSAL initialisé");
                    callback.onReady();
                }
                @Override
                public void onError(MsalException e) {
                    Log.e(TAG, "MSAL init ERR: " + e.getMessage());
                    callback.onError(e);
                }
            }
        );
    }

    // =========================================================
    // Acquérir un token — silent d'abord, interactif ensuite
    // =========================================================

    // ✅ Token silent depuis WorkManager (pas d Activity)
    public void acquireTokenSilentFromWorker(@NonNull TokenCallback callback) {
        if (msalApp == null) {
            callback.onError(new IllegalStateException("MSAL non initialisé"));
            return;
        }
        msalApp.getCurrentAccountAsync(
            new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                @Override
                public void onAccountLoaded(IAccount account) {
                    if (account == null) {
                        callback.onError(new RuntimeException("Aucun compte — login interactif requis"));
                        return;
                    }
                    acquireTokenSilent(account, null, callback);
                }
                @Override
                public void onAccountChanged(IAccount prior, IAccount current) {}
                @Override
                public void onError(MsalException e) {
                    callback.onError(e);
                }
            }
        );
    }

    public interface TokenCallback {
        void onSuccess(String accessToken);
        void onError(Exception e);
    }

    public void acquireToken(@NonNull Activity activity,
                             @NonNull TokenCallback callback) {
        if (msalApp == null) {
            callback.onError(new IllegalStateException("MSAL non initialisé — appeler init() d'abord"));
            return;
        }

        msalApp.getCurrentAccountAsync(
            new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                @Override
                public void onAccountLoaded(IAccount account) {
                    if (account == null) {
                        // Premier login — flow interactif
                        Log.i(TAG, "Aucun compte — login interactif");
                        acquireTokenInteractive(activity, callback);
                        return;
                    }
                    // Token silent
                    Log.i(TAG, "Compte trouvé — token silent");
                    acquireTokenSilent(account, activity, callback);
                }
                @Override
                public void onAccountChanged(IAccount prior, IAccount current) {
                    Log.w(TAG, "Compte changé");
                }
                @Override
                public void onError(MsalException e) {
                    Log.e(TAG, "getCurrentAccount ERR: " + e.getMessage());
                    acquireTokenInteractive(activity, callback);
                }
            }
        );
    }

    private void acquireTokenSilent(@NonNull IAccount account,
                                     @NonNull Activity activity,
                                     @NonNull TokenCallback callback) {
        AcquireTokenSilentParameters params = new AcquireTokenSilentParameters.Builder()
            .forAccount(account)
            .fromAuthority(account.getAuthority())
            .withScopes(Arrays.asList(SCOPES))
            .withCallback(new SilentAuthenticationCallback() {
                @Override
                public void onSuccess(IAuthenticationResult result) {
                    Log.i(TAG, "Token silent OK");
                    callback.onSuccess(result.getAccessToken());
                }
                @Override
                public void onError(MsalException e) {
                    if (e instanceof MsalUiRequiredException) {
                        Log.i(TAG, "UI requise — login interactif");
                        acquireTokenInteractive(activity, callback);
                    } else {
                        Log.e(TAG, "Token silent ERR: " + e.getMessage());
                        callback.onError(e);
                    }
                }
            })
            .build();
        msalApp.acquireTokenSilentAsync(params);
    }

    private void acquireTokenInteractive(@NonNull Activity activity,
                                          @NonNull TokenCallback callback) {
        AcquireTokenParameters params = new AcquireTokenParameters.Builder()
            .startAuthorizationFromActivity(activity)
            .withScopes(Arrays.asList(SCOPES))
            .withCallback(new AuthenticationCallback() {
                @Override
                public void onSuccess(IAuthenticationResult result) {
                    Log.i(TAG, "Token interactif OK");
                    callback.onSuccess(result.getAccessToken());
                }
                @Override
                public void onError(MsalException e) {
                    Log.e(TAG, "Token interactif ERR: " + e.getMessage());
                    callback.onError(e);
                }
                @Override
                public void onCancel() {
                    callback.onError(new RuntimeException("Login annulé"));
                }
            })
            .build();
        msalApp.acquireToken(params);
    }
}