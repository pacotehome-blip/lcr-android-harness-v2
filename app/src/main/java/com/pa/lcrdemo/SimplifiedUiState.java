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
}
