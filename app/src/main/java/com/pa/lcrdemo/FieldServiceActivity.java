package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// ───────────────────────────────────────────────────────────────────────────
// Toute modification de ce fichier doit être testée sur :
//   · Android 9  (API 28) — Samsung SM-T397U         · ADB 192.168.134.105:5555
//   · Android 15 (API 35) — Samsung R52X508K2DR     · ADB 192.168.134.126:5555
//
// Règles obligatoires :
//   1. Détecter la version à l'exécution via Build.VERSION.SDK_INT
//   2. Appliquer le comportement EXPLICITEMENT par version — pas de spéculation
//   3. Ne jamais utiliser d'API introduite après API 28 sans guard de version
//   4. registerReceiver()  : RECEIVER_NOT_EXPORTED ou RECEIVER_EXPORTED sur API 34+
//   5. PendingIntent       : FLAG_IMMUTABLE sur API 31+ · FLAG_MUTABLE + guard sur API 34+
//   6. startForeground()   : type obligatoire sur API 34+ — doit matcher le manifest
//
// Constantes utiles :
//   Build.VERSION_CODES.P                 = 28  (Android 9)
//   Build.VERSION_CODES.Q                 = 29  (Android 10)
//   Build.VERSION_CODES.S                 = 31  (Android 12)
//   Build.VERSION_CODES.TIRAMISU          = 33  (Android 13)
//   Build.VERSION_CODES.UPSIDE_DOWN_CAKE  = 34  (Android 14)
//   Build.VERSION_CODES.VANILLA_ICE_CREAM = 35  (Android 15)
// ═══════════════════════════════════════════════════════════════════════════

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.util.Log;

/**
 * FieldServiceActivity — Container WebView pour Field Service Mobile
 *
 * Modifications vs version originale :
 *   1. Démarre LcrHttpService au onCreate (s'il n'est pas déjà actif)
 *   2. Attend le broadcast READY avant de charger Field Service
 *      → garantit que le serveur HTTP :8765 est disponible quand
 *        lcr_bridge.js fait son premier LCR.api.ping()
 *   3. Désenregistre le receiver au onDestroy
 *
 * Le service reste actif même si cette Activity est détruite.
 *
 * Chemin : app/src/main/java/com/pa/lcr/FieldServiceActivity.java
 */
public class FieldServiceActivity extends Activity {

    private static final String TAG = "FieldServiceActivity";

    // ✅ URL Field Service Mobile DEV-FILGO-SONIC
    private static final String FIELD_SERVICE_URL =
        "https://dev-filgo-sonic.crm3.dynamics.com/main.aspx?appid=91a8643f-21db-ee11-904c-002248b1ce29";

    private WebView webView;
    private BroadcastReceiver readyReceiver;
    private boolean serviceReady = false;

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupWebView();

        // Enregistrer le receiver AVANT de démarrer le service
        // pour ne pas manquer le broadcast si le service démarre très vite
        registerReadyReceiver();

        // Démarrer le foreground service (sans effet si déjà actif)
        LcrBootReceiver.startService(this);

        // Si le service était déjà actif avant cette Activity,
        // le broadcast ne sera pas re-émis — on tente un ping direct
        checkIfAlreadyReady();
    }

    @Override
    protected void onDestroy() {
        unregisterReadyReceiver();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── WebView setup ──────────────────────────────────────────────────────

    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        // Injecter le bridge LCR — accessible via window.LCR dans Field Service
        webView.addJavascriptInterface(new LcrBridge(this), "LCR");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        // Afficher un écran d'attente minimal pendant le démarrage du service
        webView.loadData(
            "<html><body style='display:flex;align-items:center;justify-content:center;" +
            "height:100vh;margin:0;font-family:sans-serif;color:#5F5E5A;background:#F4F3EF;'>" +
            "<div style='text-align:center'>" +
            "<div style='font-size:18px;font-weight:500;color:#185FA5;margin-bottom:8px'>Filgo LCR</div>" +
            "<div style='font-size:14px'>Démarrage du serveur HTTP...</div>" +
            "</div></body></html>",
            "text/html", "utf-8"
        );
    }

    // ── Service readiness ──────────────────────────────────────────────────

    /**
     * Vérifie si l'APK répond déjà (cas où le service était actif avant
     * que cette Activity soit créée et n'émettra plus de broadcast READY).
     */
    private void checkIfAlreadyReady() {
        new Thread(() -> {
            try {
                // Charger lcr_local.crt pour valider le certificat auto-signé
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                java.io.InputStream caInput = getResources().openRawResource(R.raw.lcr_local);
                java.security.cert.Certificate ca = cf.generateCertificate(caInput);
                caInput.close();
                java.security.KeyStore ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
                ks.load(null, null);
                ks.setCertificateEntry("lcr_local", ca);
                javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
                sslCtx.init(null, tmf.getTrustManagers(), null);

                java.net.URL url = new java.net.URL("https://127.0.0.1:8765/v1/ping");
                javax.net.ssl.HttpsURLConnection conn =
                    (javax.net.ssl.HttpsURLConnection) url.openConnection();
                conn.setSSLSocketFactory(sslCtx.getSocketFactory());
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code == 200) {
                    Log.i(TAG, "APK déjà prêt — chargement immédiat Field Service");
                    runOnUiThread(this::loadFieldService);
                }
            } catch (Exception e) {
                // APK pas encore prêt — on attend le broadcast READY
                Log.d(TAG, "APK pas encore prêt, on attend le broadcast: " + e.getMessage());
            }
        }).start();
    }

    private void registerReadyReceiver() {
        readyReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(TAG, "Broadcast READY reçu — chargement Field Service");
                loadFieldService();
            }
        };
        IntentFilter filter = new IntentFilter(LcrHttpService.BROADCAST_READY);
        // Android 9-13 : sans flag · Android 14+ : RECEIVER_NOT_EXPORTED
        // BROADCAST_READY est un broadcast interne à l'APK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(readyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(readyReceiver, filter);
        }
    }

    private void unregisterReadyReceiver() {
        if (readyReceiver != null) {
            try { unregisterReceiver(readyReceiver); } catch (Exception ignored) {}
            readyReceiver = null;
        }
    }

    private void loadFieldService() {
        if (serviceReady) return; // Guard — charger une seule fois
        serviceReady = true;
        if (webView != null) {
         String url = getIntent().getStringExtra("url");
        if (url == null || url.trim().isEmpty()) url = FIELD_SERVICE_URL;
        webView.loadUrl(url);
        }
    }
}
