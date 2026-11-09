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
    static final String SUPPORT_EMAIL = "support.ti@filgo.ca";

    /** Conversation Teams « Support Camion » (id du fil, tiré du lien de la réunion). Vide = pas de message Teams. */
    static final String SUPPORT_TEAMS_CHAT_ID = "19:meeting_MDk0NTc0YzAtODA4YS00N2FjLWFmMmYtYjQzYjRiMmUzOWZl@thread.v2";

    /** Modèle du lien SOTI MobiControl vers la tablette, joint au courriel et au message Teams « Support Camion ».
     *  {ID} est remplacé par l'ID de la tablette (= nom d'appareil = nom du profil SOTI). Vide = pas de lien, ID seulement.
     *  À renseigner : copier l'adresse d'une tablette ouverte dans la console SOTI et remplacer son ID par {ID}. */
    static final String SOTI_LIEN_MODELE =
            "https://a0026528.mobicontrol.cloud/MobiControl/WebConsole/home/dashboard/devices"
            + "?search=Text%20AlmostEqual%20%22{ID}%22&subGroups=false&count=250";

    /**
     * Extraction du n° de ticket dans la réponse automatique du support (objet + début du corps).
     * Le 1er groupe capturé est le n°. PROVISOIRE : à ajuster avec un vrai exemple de réponse.
     */
    static final String SUPPORT_TICKET_REGEX =
            "(?i)(?:ticket|billet|incident|case|INC|REQ|TKT)\\s*(?:n[°o]\\s*)?[#:\\-]?\\s*([A-Z]*-?\\d{4,10})";

    /** Intervalle entre deux vérifications de la réponse du support, et nombre maximal d'essais (20 × 15 s = 5 min). */
    static final long SUPPORT_TICKET_SONDE_MS = 15_000L;
    static final int SUPPORT_TICKET_ESSAIS_MAX = 20;

    /** Taille max des pièces jointes du courriel Graph (octets, avant base64). Au-delà : le journal est tronqué. */
    static final int SUPPORT_MAX_PJ_OCTETS = 2_500_000;

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

    /** Application SOTI MobiControl (agent Android Enterprise) — lien « SOTI » de la demande de support. */
    public static final String SOTI_PACKAGE = "net.soti.mobicontrol.androidwork";
    /** Repli facultatif si l'agent n'est pas installé (ex. portail web SOTI de Filgo). Vide = pas de repli. */
    public static final String SOTI_URL = "";

    /** Nombre d'échecs de détection du registre (après un deeplink) avant d'afficher la trombone. */
    public static final int DEEPLINK_ECHECS_AVANT_ALERTE = 2;

    /** Service d'impression (autre APK, sur la même tablette). */
    public static final String PRINT_PACKAGE = "com.filgo.printspooler";
    /**
     * Classe du service HTTP d'impression, pour le démarrer SANS quitter Filgo-Registre
     * (ex. "com.filgo.printspooler.PrintHttpService" — À CONFIRMER, le service doit être exporté).
     * Vide = on ouvre l'application d'impression à la place.
     */
    public static final String PRINT_SERVICE_CLASS = "";

    static final String OUTLOOK_PACKAGE = "com.microsoft.office.outlook";
    static final String TEAMS_PACKAGE   = "com.microsoft.teams";
}
