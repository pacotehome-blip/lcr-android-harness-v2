package com.pa.lcrdemo;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * FieldServiceActivity — Container WebView pour Field Service Mobile
 *
 * Lance Field Service dans une WebView avec le bridge LCR injecté.
 * window.LCR.xxx() accessible depuis les JavaScript web resources de Field Service.
 *
 * Chemin: app/src/main/java/com/pa/lcrdemo/FieldServiceActivity.java
 *
 * Dans AndroidManifest.xml — ajouter :
 * <activity android:name=".FieldServiceActivity"
 *           android:label="Field Service"
 *           android:exported="true" />
 *
 * URL Field Service: https://<org>.crm.dynamics.com/main.aspx
 */
public class FieldServiceActivity extends Activity {

    // ⚠️ Remplacez par votre URL Field Service réelle
    private static final String FIELD_SERVICE_URL =
        "https://votre-org.crm.dynamics.com/main.aspx";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        webView.addJavascriptInterface(new LcrBridge(), "LCR");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Laisser la WebView gérer la navigation Field Service
                return false;
            }
        });

        webView.loadUrl(FIELD_SERVICE_URL);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
