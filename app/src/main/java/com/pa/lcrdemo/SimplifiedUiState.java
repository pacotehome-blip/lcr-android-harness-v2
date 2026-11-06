package com.pa.lcrdemo;

/**
 * ✅ AJOUTÉ (6 nov 2026, demande Paul — "la description viendra du deeplink", "on l'affiche
 * seulement, donc s'il est là super sinon on continue") — description du produit FieldService
 * reçue par le deeplink, gardée EN MÉMOIRE seulement : rien n'est écrit en base ni en JSON, et
 * elle n'est pas restaurée à la reprise de l'app. Absente du deeplink = vide = ligne non affichée.
 */
final class SimplifiedUiState {
    private SimplifiedUiState() {}

    private static volatile String woNum = "";
    private static volatile String produitDesc = "";

    /** Appelé par le deeplink. Une description absente (null/vide) efface la précédente. */
    static void setFsProduitDesc(String wo, String desc) {
        woNum = wo != null ? wo.trim() : "";
        produitDesc = desc != null ? desc.trim() : "";
    }

    /**
     * Description à afficher pour le WO courant de l'onglet. Si le deeplink et l'onglet
     * portent chacun un WO et qu'ils diffèrent, rien n'est affiché (autre registre / autre WO).
     */
    static String getFsProduitDesc(String woCourant) {
        String d = produitDesc;
        if (d == null || d.isEmpty()) return "";
        String w = woNum;
        if (w != null && !w.isEmpty() && woCourant != null && !woCourant.isEmpty()
                && !w.equals(woCourant.trim())) {
            return "";
        }
        return d;
    }

    // ------------------------------------------------------------------ échecs de détection (deeplink)
    private static volatile String echecWo = "";
    private static volatile int echecs = 0;

    /** Appelé par DeepLinkHandler quand la détection / connexion du registre échoue pour ce WO. */
    static synchronized void signalerEchecDeepLink(String wo, int poids) {
        String w = wo != null ? wo.trim() : "";
        if (!w.equals(echecWo)) { echecWo = w; echecs = 0; }
        echecs += Math.max(1, poids);
    }

    /** Nouveau deeplink, ou situation rétablie : on repart de zéro. */
    static synchronized void reinitialiserEchecsDeepLink() {
        echecWo = "";
        echecs = 0;
    }

    static int getEchecsDeepLink(String woCourant) {
        String w = echecWo;
        if (echecs <= 0) return 0;
        if (w != null && !w.isEmpty() && woCourant != null && !woCourant.isEmpty() && !w.equals(woCourant.trim())) return 0;
        return echecs;
    }

    // ------------------------------------------------------------------ nom de la tablette
    /** Nom donné à la tablette dans ses réglages (ex. celui de SOTI), sinon modèle de l'appareil. */
    static String nomTablette(android.content.Context ctx) {
        String nom = null;
        try { nom = android.provider.Settings.Global.getString(ctx.getContentResolver(), "device_name"); } catch (Throwable ignored) {}
        if (nom == null || nom.trim().isEmpty()) {
            try { nom = android.provider.Settings.Secure.getString(ctx.getContentResolver(), "bluetooth_name"); } catch (Throwable ignored) {}
        }
        String modele = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        if (nom == null || nom.trim().isEmpty()) return modele;
        return nom.trim() + " (" + modele + ")";
    }
}
