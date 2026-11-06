package com.pa.lcrdemo;

/**
 * ✅ AJOUTÉ (6 nov 2026, demande Paul) — réglages de l'écran simplifié chauffeur et de la
 * demande de support (trombone). Tout ce qui est à ajuster sans toucher à la logique est ici.
 */
final class SimplifiedConfig {
    private SimplifiedConfig() {}

    /**
     * Mot de passe FIXE de l'accès Admin (demande Paul : "pour le moment un mot de passe fixe").
     * Barrière d'usage seulement : une valeur dans l'APK reste lisible par décompilation.
     * ⚠️ À CHANGER avant le déploiement — valeur provisoire choisie par Claude, pas par Paul.
     */
    static final String ADMIN_PASSWORD = "Filgo2026";

    /**
     * Nom du paramètre du deeplink qui porte la description du produit FieldService
     * (affichage seulement, non conservé). Nom provisoire : à convenir avec le développeur
     * FieldService — si le nom change, il suffit de le changer ici.
     */
    static final String DEEPLINK_PARAM_PRODUIT_DESC = "produitdesc";

    /** Courriel du support (pré-adressé). Vide = aucun destinataire pré-rempli. À renseigner. */
    static final String SUPPORT_EMAIL = "";

    /** Téléphone du support (bouton « Appeler le support »). Vide = bouton masqué. À renseigner. */
    static final String SUPPORT_PHONE = "";

    /** Durée du journal joint à la demande de support, en minutes (valeur proposée : 30). */
    static final int SUPPORT_JOURNAL_MINUTES = 30;

    /** Durée maximale du message vocal, en secondes (valeur proposée : 60). */
    static final int AUDIO_MAX_SECONDS = 60;

    /** Son de l'alerte (bruit de clé qui tapote doucement une vitre, généré par l'app — aucun fichier audio). */
    public static final boolean SON_ALERTE_ACTIF = true;
    /** Premier son à l'apparition de la bulle, puis un rappel toutes les N ms tant que le problème dure. */
    public static final long SON_ALERTE_RAPPEL_MS = 60_000L;
    /** Volume du son (0.0 – 1.0), appliqué en plus du volume « notifications » de la tablette. */
    public static final float SON_ALERTE_VOLUME = 0.7f;

    static final String OUTLOOK_PACKAGE = "com.microsoft.office.outlook";
    static final String TEAMS_PACKAGE   = "com.microsoft.teams";
}
