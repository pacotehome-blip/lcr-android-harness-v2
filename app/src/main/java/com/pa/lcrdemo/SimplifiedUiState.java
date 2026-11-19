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

    // ------------------------------------------------------------------ produit demandé (deeplink) vs produit du registre
    // ✅ AJOUTÉ (17 nov 2026, demande Paul — "dans l'écran simplifié je veux voir le produit demandé par le deeplink vs
    // le produit présent dans le registre avec un signe = ou erreur si aucune concordance entre eux") — le paramètre
    // « produit » du deeplink (n° de slot du registre, ou nom) est gardé EN MÉMOIRE seulement, comme la description.
    private static volatile String produitWo = "";
    private static volatile String produitDemande = "";

    /** Appelé par le deeplink. Un produit absent (null/vide) efface le précédent. */
    static void setProduitDemande(String wo, String produit) {
        produitWo = wo != null ? wo.trim() : "";
        produitDemande = produit != null ? produit.trim() : "";
    }

    static String getProduitDemande(String woCourant) {
        String d = produitDemande;
        if (d == null || d.isEmpty()) return "";
        String w = produitWo;
        if (w != null && !w.isEmpty() && woCourant != null && !woCourant.isEmpty() && !w.equals(woCourant.trim())) return "";
        return d;
    }

    static final int PRODUIT_RIEN = 0;       // rien à comparer (pas de produit dans le deeplink) → rien d'affiché
    static final int PRODUIT_CONCORDE = 1;   // au moins un critère concorde → « = »
    static final int PRODUIT_DIFFERENT = 2;  // aucun critère ne concorde → erreur
    static final int PRODUIT_ATTENTE = 3;    // produit du registre pas encore connu → rien d'affiché

    static String norm(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return n.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** Description seule d'un libellé « 1 - PROPANE (prod1) [Aucun] » → « PROPANE ». */
    private static String descriptionDuLibelle(String label) {
        if (label == null) return "";
        String d = label.trim().replaceFirst("^\\d+\\s*[-–—:]?\\s*", "");
        int cut = d.length();
        for (String sep : new String[]{" (", " [", " — "}) {
            int i = d.indexOf(sep);
            if (i >= 0 && i < cut) cut = i;
        }
        return d.substring(0, cut).trim();
    }

    /**
     * Compare le produit demandé par le deeplink (n° de slot ou nom, + description FieldService) au libellé du produit
     * sélectionné/présent dans le registre. « Concorde » si AU MOINS UN critère disponible concorde : même n° de slot,
     * nom du deeplink contenu dans le libellé du registre, ou description FieldService ≈ description du registre.
     */
    static int comparerProduit(String woCourant, String labelRegistre) {
        String dem = getProduitDemande(woCourant);
        String fs = getFsProduitDesc(woCourant);
        if (dem.isEmpty() && fs.isEmpty()) return PRODUIT_RIEN;
        String reg = labelRegistre == null ? "" : labelRegistre.trim();
        if (reg.isEmpty() || reg.equals("—")) return PRODUIT_ATTENTE;

        int demIdx = -1; String demTxt = "";
        try { demIdx = Integer.parseInt(dem.trim()); } catch (Exception e) { demTxt = dem; }
        int regIdx = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*(\\d+)(?!\\d)").matcher(reg);
        if (m.find()) { try { regIdx = Integer.parseInt(m.group(1)); } catch (Exception ignored) {} }
        String regN = norm(reg);
        String regDescN = norm(descriptionDuLibelle(reg));

        if (demIdx >= 0 && regIdx == demIdx) return PRODUIT_CONCORDE;
        if (!demTxt.isEmpty()) {
            String d = norm(demTxt);
            if (!d.isEmpty() && regN.contains(d)) return PRODUIT_CONCORDE;
        }
        if (!fs.isEmpty()) {
            String f = norm(fs);
            if (!f.isEmpty() && (regN.contains(f) || (!regDescN.isEmpty() && f.contains(regDescN)))) return PRODUIT_CONCORDE;
        }
        return PRODUIT_DIFFERENT;
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
