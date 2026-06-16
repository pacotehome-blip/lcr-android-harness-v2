package com.pa.lcrdemo.config;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * LcrConfig — Lecture de la configuration environnement.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/config/LcrConfig.java
 *
 * Priorité de lecture :
 *   1. /sdcard/Android/data/com.pa.lcrdemo/files/lcr_config.properties (éditable sur tablette)
 *   2. assets/lcr_config.properties (embarqué dans l'APK — fallback)
 *
 * Environnements supportés : DEV | QA | PROD
 */
public class LcrConfig {

    private static final String TAG       = "LcrConfig";
    private static final String FILE_NAME = "lcr_config.properties";

    // Cache — chargé une seule fois par session
    private static Properties sProps      = null;
    private static String     sEnv        = null;

    // =========================================================
    // API publique
    // =========================================================

    public static String getDataverseUrl(Context ctx) {
        return get(ctx, "lcr_dataverse_url_" + env(ctx).toLowerCase(),
            "https://dev-filgo-sonic.crm3.dynamics.com");
    }

    public static String getDataverseScope(Context ctx) {
        return get(ctx, "lcr_dataverse_scope_" + env(ctx).toLowerCase(),
            "https://dev-filgo-sonic.crm3.dynamics.com/.default");
    }

    public static String getFsAppId(Context ctx) {
        return get(ctx, "lcr_fs_app_id_" + env(ctx).toLowerCase(),
            "91a8643f-21db-ee11-904c-002248b1ce29");
    }

    public static String getAzureClientId(Context ctx) {
        return get(ctx, "lcr_azure_client_id_" + env(ctx).toLowerCase(),
            "ec9ea9bc-e972-4407-962b-b17ccd050380");
    }

    public static String env(Context ctx) {
        if (sEnv != null) return sEnv;
        sEnv = get(ctx, "lcr_environment", "DEV").toUpperCase();
        Log.i(TAG, "Environnement: " + sEnv);
        return sEnv;
    }

    /** Réinitialise le cache — utile après modification du fichier sur la tablette. */
    public static void reset() {
        sProps = null;
        sEnv   = null;
        Log.i(TAG, "Config réinitialisée");
    }

    /**
     * Détecte et applique l'environnement depuis l'URL Dataverse reçue de FSM.
     * Écrit dans le fichier externe sur la tablette (priorité 1) et réinitialise le cache.
     *
     * Mapping URL → environnement :
     *   dev-filgo-sonic   → DEV
     *   qa-filgo-sonic    → QA
     *   staging-filgo-sonic → STAGING
     *   filgo.crm3        → PROD  (FILGO Production)
     *
     * Un seul APK pour tous les environnements.
     */
    public static void applyFromOrgUrl(Context ctx, String orgUrl) {
        if (orgUrl == null || orgUrl.isEmpty()) return;
        String lower = orgUrl.toLowerCase();

        String env, dvUrl, scope, fsAppId;

        if (lower.contains("dev-filgo-sonic")) {
            env      = "DEV";
            dvUrl    = "https://dev-filgo-sonic.crm3.dynamics.com";
            scope    = dvUrl + "/.default";
            fsAppId  = "91a8643f-21db-ee11-904c-002248b1ce29";
        } else if (lower.contains("qa-filgo-sonic")) {
            env      = "QA";
            dvUrl    = "https://qa-filgo-sonic.crm3.dynamics.com";
            scope    = dvUrl + "/.default";
            fsAppId  = ""; // TODO — App ID FSM QA
        } else if (lower.contains("staging-filgo-sonic")) {
            env      = "STAGING";
            dvUrl    = "https://staging-filgo-sonic.crm3.dynamics.com";
            scope    = dvUrl + "/.default";
            fsAppId  = ""; // TODO — App ID FSM STAGING
        } else if (lower.contains("filgo.crm3")) {
            env      = "PROD";
            dvUrl    = "https://filgo.crm3.dynamics.com";
            scope    = dvUrl + "/.default";
            fsAppId  = ""; // TODO — App ID FSM PROD
        } else {
            Log.w(TAG, "applyFromOrgUrl: URL non reconnue: " + orgUrl + " — env inchangé");
            return;
        }

        // Écrire dans le fichier externe (priorité 1 de load())
        try {
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                java.io.File f = new java.io.File(dir, FILE_NAME);
                Properties p = new Properties();
                // Charger les propriétés existantes pour ne pas écraser les autres clés
                if (f.exists()) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
                        p.load(fis);
                    }
                }
                // Appliquer les valeurs de l'env détecté
                p.setProperty("lcr_environment",                    env);
                p.setProperty("lcr_dataverse_url_" + env.toLowerCase(),   dvUrl);
                p.setProperty("lcr_dataverse_scope_" + env.toLowerCase(), scope);
                p.setProperty("lcr_fs_app_id_" + env.toLowerCase(),       fsAppId);
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
                    p.store(fos, "Auto-généré par LcrConfig.applyFromOrgUrl — " + env);
                }
                Log.i(TAG, "applyFromOrgUrl: env=" + env + " → " + dvUrl);
            }
        } catch (Exception e) {
            Log.e(TAG, "applyFromOrgUrl: échec écriture fichier: " + e.getMessage());
        }

        // Réinitialiser le cache pour que le prochain get() recharge le fichier
        reset();
    }

    /** Nom lisible de l'environnement courant. */
    public static String getEnvironmentName(Context ctx) {
        return env(ctx);
    }

    /** Résumé lisible pour logs/UI. Ex: "[DEV] dev-filgo-sonic.crm3.dynamics.com" */
    public static String getSummary(Context ctx) {
        return "[" + env(ctx) + "] "
            + getDataverseUrl(ctx).replace("https://", "");
    }

    // =========================================================
    // Lecture propriétés
    // =========================================================

    private static String get(Context ctx, String key, String defaultValue) {
        Properties p = load(ctx);
        String val = p.getProperty(key);
        if (val != null && !val.trim().isEmpty()) return val.trim();
        return defaultValue;
    }

    private static Properties load(Context ctx) {
        if (sProps != null) return sProps;

        sProps = new Properties();

        // 1. Fichier éditable sur la tablette
        File externalFile = new File(ctx.getExternalFilesDir(null), FILE_NAME);
        if (externalFile.exists()) {
            try (FileInputStream fis = new FileInputStream(externalFile)) {
                sProps.load(fis);
                Log.i(TAG, "Config chargée depuis fichier externe: " + externalFile.getAbsolutePath());
                return sProps;
            } catch (Exception e) {
                Log.w(TAG, "Échec lecture fichier externe — fallback assets: " + e.getMessage());
                sProps = new Properties();
            }
        }

        // 2. Fallback — assets embarqués dans l'APK
        try (InputStream is = ctx.getAssets().open(FILE_NAME)) {
            sProps.load(is);
            Log.i(TAG, "Config chargée depuis assets");
        } catch (Exception e) {
            Log.e(TAG, "Échec lecture assets — valeurs par défaut utilisées: " + e.getMessage());
        }

        return sProps;
    }
}