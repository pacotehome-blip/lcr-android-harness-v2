package com.pa.lcr.lcp.storage;

// ═══════════════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// Toute modification doit être testée sur Android 9 et Android 15
// ═══════════════════════════════════════════════════════════════════════

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite local APK — miroir de la table Dataverse filgo_delivery_status.
 *
 * Maître des données terrain (registre LCR).
 * Synchronisé vers Dataverse via MSAL quand réseau disponible.
 *
 * v1: filgo_delivery_status + filgo_note_template
 *
 * TODO — Fonctionnalité future: Backup/Restore tablette
 * =====================================================
 * Permettre le téléchargement (export) de la base filgo_delivery_status.db
 * depuis la tablette vers un serveur ou un fichier local, et le re-téléversement
 * (import) sur une nouvelle tablette en cas de remplacement.
 * Cela permettrait de repartir de zéro avec une nouvelle tablette en reprenant
 * l'historique complet des livraisons sans perte de données.
 *
 * Approche suggérée:
 * - Export: sérialiser toutes les entrées de filgo_lcr_delivery_statuses en JSON
 *   et uploader vers Dataverse ou un endpoint dédié
 * - Import: au démarrage APK sur nouvelle tablette, détecter si DB vide + token
 *   Dataverse disponible → proposer de télécharger l'historique depuis Dataverse
 * - Prérequis: endpoint API REST ou utiliser directement
 *   filgo_lcr_delivery_statuses comme source de vérité distante
 */
public class LcrDeliveryStatusDb extends SQLiteOpenHelper {

    public static final String DB_NAME    = "filgo_delivery_status.db";
    public static final int    DB_VERSION = 5; // v4: job_id — ancre stable pour recuperer une livraison encore en cours; v5: stopid / productid (GUID Field Service du deep link)

    private static final String TAG = "LcrDeliveryStatusDb";

    // =========================================================
    // Tables
    // =========================================================
    public static final String TABLE_DELIVERY = "filgo_delivery_status";
    public static final String TABLE_NOTE     = "filgo_note_template";

    // =========================================================
    // Colonnes — filgo_delivery_status
    // =========================================================
    public static final String COL_ID                  = "id";                  // PK locale autoincrement
    public static final String COL_DATAVERSE_ID        = "dataverse_id";        // GUID Dataverse si déjà créé

    // Identification
    public static final String COL_WO_NUM              = "wo_num";
    public static final String COL_WO_ID_GUID          = "wo_id_guid";
    // GUID Field Service reçus par le deep link — noms locaux = filgo_stopid / filgo_productid sans préfixe
    public static final String COL_STOPID              = "stopid";
    public static final String COL_PRODUCTID           = "productid";
    public static final String COL_TOURNEE_ID          = "tournee_id";
    public static final String COL_TRANSACTION_NO      = "transaction_no";
    public static final String COL_STOP_SEQUENCE       = "stop_sequence";
    public static final String COL_LIVREUR_ID          = "livreur_id";
    public static final String COL_CAMION_ID           = "camion_id";
    public static final String COL_SERIAL_ID           = "serial_id";
    public static final String COL_LCRNODE             = "lcrnode";
    public static final String COL_BTMAC               = "btmac";

    // Type de transaction
    public static final String COL_STOP_TYPE           = "stop_type";           // CHARGEMENT/LIVRAISON/TRANSFERT
    public static final String COL_TYPE                = "type";                // ORIGINAL/CORRECTION/ANNULATION/MANUELLE
    public static final String COL_SOURCE              = "source";              // REGISTRE/MANUEL
    public static final String COL_TICKET_NO_REF       = "ticket_no_ref";
    public static final String COL_APPROBATION_REQ     = "approbation_required";
    public static final String COL_APPROBATION_STATUS  = "approbation_status";  // PENDING/APPROVED/REJECTED
    public static final String COL_APPROBATION_BY      = "approbation_by";
    public static final String COL_APPROBATION_TS      = "approbation_ts";

    // Données commerciales (depuis deep link / sync)
    public static final String COL_CLIENT              = "client";
    public static final String COL_PRODUIT_NO          = "produit_no";
    public static final String COL_COMPARTIMENT_ID     = "compartiment_id";
    public static final String COL_PRESET_L            = "preset_l";
    public static final String COL_PRIX_UNITAIRE       = "prix_unitaire";
    public static final String COL_TPS                 = "tps";
    public static final String COL_TVQ                 = "tvq";
    public static final String COL_TAXE_CARBONE        = "taxe_carbone";
    public static final String COL_MEMO_DISPATCH       = "memo_dispatch";

    // Données terrain (registre LCR)
    public static final String COL_TICKET_NO           = "ticket_no";
    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on va ajouter le ticket de
    // livraison en table avec le statut running_flowing... si jamais on a
    // un crash une réinstallation on veut être en mesure de la reprendre
    // là où elle est") — ancre stable, connue dès l'armement (jobId, un
    // UUID généré avant même que ticket_no soit connu — Field #23=0 tant
    // que rien n'est imprimé). UNIQUE(wo_num, ticket_no) ON CONFLICT
    // IGNORE ne peut pas servir de mécanisme de mise à jour pour une
    // livraison encore en cours (ticket_no vide/instable à ce stade) —
    // job_id sert de vraie clé pour retrouver et mettre à jour LA MÊME
    // ligne au fil de la progression, sans jamais dupliquer ni perdre les
    // données finales en silence.
    public static final String COL_JOB_ID               = "job_id";
    public static final String COL_SALE_NO             = "sale_no";
    public static final String COL_NET_L               = "net_l";
    public static final String COL_GROSS_L             = "gross_l";
    public static final String COL_DELTA_NET_L         = "delta_net_l";
    public static final String COL_DELTA_GROSS_L       = "delta_gross_l";
    public static final String COL_PRESET_STATUS       = "preset_status";       // EXACT/UNDER/OVER/RESOLVE
    public static final String COL_START_UTC           = "start_utc";
    public static final String COL_END_UTC             = "end_utc";
    public static final String COL_DURATION_S          = "duration_s";

    // Inventaire camion
    public static final String COL_INVENTAIRE_AVANT_L  = "inventaire_avant_l";
    public static final String COL_INVENTAIRE_APRES_L  = "inventaire_apres_l";
    public static final String COL_SERIAL_ID_ORIGINAL  = "serial_id_original";
    public static final String COL_SERIAL_ID_NOUVEAU   = "serial_id_nouveau";

    // Notes et statut sync
    public static final String COL_NOTES_LIVREUR       = "notes_livreur";
    public static final String COL_SYNC_STATUS         = "sync_status";         // PENDING/SYNCED/ERROR
    public static final String COL_PAYLOAD_JSON        = "payload_json";
    public static final String COL_TS_CREATED_MS       = "ts_created_ms";
    public static final String COL_TS_UPDATED_MS       = "ts_updated_ms";

    // Historique livraisons précédentes
    public static final String COL_PREVIOUS_NET_L      = "previous_net_l";
    public static final String COL_PREVIOUS_GROSS_L    = "previous_gross_l";
    public static final String COL_PREVIOUS_TICKET_NO  = "previous_ticket_no";
    public static final String COL_TOTAL_NET_L         = "total_net_l";
    public static final String COL_TOTAL_GROSS_L       = "total_gross_l";
    public static final String COL_DELIVERY_COUNT      = "delivery_count";
    public static final String COL_PRESET_OVERAGE_L    = "preset_overage_l";

    // Erreurs
    public static final String COL_ERROR_CODE          = "error_code";
    public static final String COL_ERROR_MSG           = "error_msg";

    // Valeurs sync_status
    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED  = "SYNCED";
    public static final String SYNC_ERROR   = "ERROR";

    // Valeurs stop_type
    public static final String STOP_TYPE_CHARGEMENT = "CHARGEMENT";
    public static final String STOP_TYPE_LIVRAISON  = "LIVRAISON";
    public static final String STOP_TYPE_TRANSFERT  = "TRANSFERT";
    // ✅ AJOUTÉ (13 oct 2026, demande Paul — "dans le cas d'une situation
    // orphelin avec un deeplink... tu la marques comme orphelin et sur
    // dataverse on l'indique comme orphelin" / "je le mettrais dans le
    // payload json") — valeur LOCALE seulement : une ligne RUNNING_FLOWING
    // jamais suivie, remplacée par une livraison plus récente du même
    // registre. Poussée vers Dataverse sous stop_type="LIVRAISON" (valeur
    // déjà connue de Dataverse) ; l'indication d'orphelin est dans le
    // payload JSON ("orphelin": true). Voir marquerOrphelines().
    public static final String STOP_TYPE_ORPHELIN   = "ORPHELIN";

    // Valeurs type
    public static final String TYPE_ORIGINAL    = "ORIGINAL";
    public static final String TYPE_CORRECTION  = "CORRECTION";
    public static final String TYPE_ANNULATION  = "ANNULATION";
    public static final String TYPE_MANUELLE    = "MANUELLE";
    public static final String TYPE_REPRINT       = "REPRINT";
    public static final String TYPE_FUITE_VANNE   = "FUITE_VANNE"; // volume detecte apres preset

    // Valeurs preset_status
    public static final String PRESET_EXACT   = "EXACT";
    public static final String PRESET_UNDER   = "UNDER";
    public static final String PRESET_OVER    = "OVER";
    public static final String PRESET_RESOLVE = "RESOLVE";

    // =========================================================
    // Colonnes — filgo_note_template
    // =========================================================
    public static final String NOTE_COL_ID          = "id";
    public static final String NOTE_COL_CODE        = "code";
    public static final String NOTE_COL_LIBELLE_FR  = "libelle_fr";
    public static final String NOTE_COL_LIBELLE_EN  = "libelle_en";
    public static final String NOTE_COL_CATEGORIE   = "categorie";
    public static final String NOTE_COL_ACTIVE      = "active";
    public static final String NOTE_COL_ORDRE       = "ordre";

    // =========================================================
    // Constructeur
    // =========================================================
    private final Context appContext;

    public LcrDeliveryStatusDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        try (Cursor c = db.rawQuery("PRAGMA journal_mode=WAL", null)) {
            // WAL mode pour performances
        } catch (Exception e) {
            Log.w(TAG, "WAL mode non activé", e);
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createDeliveryStatusTable(db);
        createNoteTemplateTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v2: champs historique + erreurs
        if (oldVersion < 2) {
            addColumnIfMissing(db, TABLE_DELIVERY, COL_PREVIOUS_NET_L,     "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_PREVIOUS_GROSS_L,   "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_PREVIOUS_TICKET_NO, "TEXT");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_TOTAL_NET_L,        "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_TOTAL_GROSS_L,      "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_DELIVERY_COUNT,     "INTEGER DEFAULT 1");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_PRESET_OVERAGE_L,   "REAL DEFAULT 0");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_ERROR_CODE,         "TEXT");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_ERROR_MSG,          "TEXT");
        }
        // v3: contrainte UNIQUE(wo_num, ticket_no) — recréer la table
        if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_DELIVERY + "_old");
            db.execSQL("ALTER TABLE " + TABLE_DELIVERY + " RENAME TO " + TABLE_DELIVERY + "_old");
            onCreate(db);
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_DELIVERY +
                " SELECT * FROM " + TABLE_DELIVERY + "_old");
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_DELIVERY + "_old");
        }
        // v4: job_id — ancre stable pour retrouver/mettre à jour une
        // livraison encore en cours (ticket_no pas encore connu à
        // l'armement)
        if (oldVersion < 4) {
            addColumnIfMissing(db, TABLE_DELIVERY, COL_JOB_ID, "TEXT");
        }
        // v5: stopid / productid — GUID Field Service reçus par le deep link,
        // simplement transportés (jamais utilisés pour décider)
        if (oldVersion < 5) {
            addColumnIfMissing(db, TABLE_DELIVERY, COL_STOPID,    "TEXT");
            addColumnIfMissing(db, TABLE_DELIVERY, COL_PRODUCTID, "TEXT");
        }
    }

    // =========================================================
    // Création table filgo_delivery_status
    // =========================================================
    private static void createDeliveryStatusTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_DELIVERY + " (" +
            COL_ID                 + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            COL_DATAVERSE_ID       + " TEXT," +

            // Identification
            COL_JOB_ID             + " TEXT," +
            COL_WO_NUM             + " TEXT NOT NULL," +
            COL_WO_ID_GUID         + " TEXT," +
            COL_TOURNEE_ID         + " TEXT," +
            COL_TRANSACTION_NO     + " INTEGER DEFAULT 1," +
            COL_STOP_SEQUENCE      + " INTEGER," +
            COL_LIVREUR_ID         + " TEXT," +
            COL_CAMION_ID          + " TEXT," +
            COL_SERIAL_ID          + " TEXT," +
            COL_LCRNODE            + " INTEGER," +
            COL_BTMAC              + " TEXT," +

            // Type de transaction
            COL_STOP_TYPE          + " TEXT DEFAULT 'LIVRAISON'," +
            COL_TYPE               + " TEXT DEFAULT 'ORIGINAL'," +
            COL_SOURCE             + " TEXT DEFAULT 'REGISTRE'," +
            COL_TICKET_NO_REF      + " TEXT," +
            COL_APPROBATION_REQ    + " INTEGER DEFAULT 0," +
            COL_APPROBATION_STATUS + " TEXT," +
            COL_APPROBATION_BY     + " TEXT," +
            COL_APPROBATION_TS     + " TEXT," +

            // Données commerciales
            COL_CLIENT             + " TEXT," +
            COL_PRODUIT_NO         + " INTEGER," +
            COL_COMPARTIMENT_ID    + " INTEGER," +
            COL_PRESET_L           + " REAL," +
            COL_PRIX_UNITAIRE      + " REAL," +
            COL_TPS                + " REAL," +
            COL_TVQ                + " REAL," +
            COL_TAXE_CARBONE       + " REAL," +
            COL_MEMO_DISPATCH      + " TEXT," +

            // Données terrain (registre)
            COL_TICKET_NO          + " TEXT," +
            COL_SALE_NO            + " TEXT," +
            COL_NET_L              + " REAL," +
            COL_GROSS_L            + " REAL," +
            COL_DELTA_NET_L        + " REAL," +
            COL_DELTA_GROSS_L      + " REAL," +
            COL_PRESET_STATUS      + " TEXT," +
            COL_START_UTC          + " TEXT," +
            COL_END_UTC            + " TEXT," +
            COL_DURATION_S         + " REAL," +

            // Inventaire camion
            COL_INVENTAIRE_AVANT_L + " REAL," +
            COL_INVENTAIRE_APRES_L + " REAL," +
            COL_SERIAL_ID_ORIGINAL + " TEXT," +
            COL_SERIAL_ID_NOUVEAU  + " TEXT," +

            // Notes et statut
            COL_NOTES_LIVREUR      + " TEXT," +
            COL_SYNC_STATUS        + " TEXT NOT NULL DEFAULT 'PENDING'," +
            COL_PAYLOAD_JSON       + " TEXT," +
            COL_TS_CREATED_MS      + " INTEGER NOT NULL," +
            COL_TS_UPDATED_MS      + " INTEGER NOT NULL," +

            // Historique
            COL_PREVIOUS_NET_L     + " REAL DEFAULT 0," +
            COL_PREVIOUS_GROSS_L   + " REAL DEFAULT 0," +
            COL_PREVIOUS_TICKET_NO + " TEXT," +
            COL_TOTAL_NET_L        + " REAL DEFAULT 0," +
            COL_TOTAL_GROSS_L      + " REAL DEFAULT 0," +
            COL_DELIVERY_COUNT     + " INTEGER DEFAULT 1," +
            COL_PRESET_OVERAGE_L   + " REAL DEFAULT 0," +

            // Erreurs
            COL_ERROR_CODE         + " TEXT," +
            COL_ERROR_MSG          + " TEXT," +
            // GUID Field Service (deep link) — en fin de table, comme après un ALTER TABLE ADD COLUMN
            COL_STOPID             + " TEXT," +
            COL_PRODUCTID          + " TEXT," +
            // Anti-doublon: une seule ligne par (wo_num, ticket_no)
            "UNIQUE(" + COL_WO_NUM + "," + COL_TICKET_NO + ") ON CONFLICT IGNORE" +
            ");"
        );

        // Index pour sync et recherche
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_sync ON " + TABLE_DELIVERY +
            "(" + COL_SYNC_STATUS + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_wo ON " + TABLE_DELIVERY +
            "(" + COL_WO_NUM + "," + COL_WO_ID_GUID + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_tournee ON " + TABLE_DELIVERY +
            "(" + COL_TOURNEE_ID + "," + COL_STOP_SEQUENCE + ");");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_serial ON " + TABLE_DELIVERY +
            "(" + COL_SERIAL_ID + ");");
    }

    // =========================================================
    // Création table filgo_note_template
    // =========================================================
    private static void createNoteTemplateTable(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE_NOTE + " (" +
            NOTE_COL_ID         + " INTEGER PRIMARY KEY AUTOINCREMENT," +
            NOTE_COL_CODE       + " TEXT NOT NULL UNIQUE," +
            NOTE_COL_LIBELLE_FR + " TEXT NOT NULL," +
            NOTE_COL_LIBELLE_EN + " TEXT," +
            NOTE_COL_CATEGORIE  + " TEXT," +
            NOTE_COL_ACTIVE     + " INTEGER NOT NULL DEFAULT 1," +
            NOTE_COL_ORDRE      + " INTEGER DEFAULT 0" +
            ");"
        );
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_note_active ON " + TABLE_NOTE +
            "(" + NOTE_COL_ACTIVE + "," + NOTE_COL_ORDRE + ");");
    }

    // =========================================================
    // CRUD — filgo_delivery_status
    // =========================================================

    /**
     * Insère une nouvelle transaction — toujours un nouvel enregistrement.
     * Chaque impression = une nouvelle ligne dans filgo_delivery_status.
     * Calcule automatiquement previous/total/count depuis les lignes précédentes.
     */
    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on tient compte du cumul
    // partout même dans dataverse... on renforcit même avec une livraison
    // en cours") — logique cumulative (previous/total/count/overage)
    // extraite d'insertDelivery() en fonction partagée, réutilisée par
    // upsertByJobId() aussi. excludeJobId : quand on MET À JOUR notre
    // propre ligne (armement → fin, même job_id), il faut l'exclure de
    // la recherche du "précédent" — sinon une ligne se compterait
    // elle-même comme sa propre livraison précédente, faussant le cumul.
    // ✅ CORRIGÉ EN PROFONDEUR (25 sept 2026, demande Paul — "je veux la
    // vérité du cumul peu importe l'ordre que c'est arrivé. plus tard
    // les dev de FieldService feront l'ordre qu'ils veulent") — l'ancien
    // calcul INCRÉMENTAL ("dernière ligne existante + ma valeur")
    // suppose que les livraisons arrivent une à la fois, dans l'ordre —
    // faux dès que deux processus séparés insèrent à des moments
    // différents (confirmé par log réel : pullDeliveryByTicket insère
    // le ticket COURANT seul, AVANT que pullAllDeliveriesForWorkOrder
    // n'insère le reste de l'historique du même WO — peu importe le tri
    // de CE DEUXIÈME pull, le premier ticket inséré "voit" une BD encore
    // vide et devient à tort la base de tout ce qui suit). Plus aucune
    // dépendance à l'ordre d'arrivée : additionne maintenant TOUTES les
    // lignes déjà en BD pour ce WO (peu importe quand/dans quel ordre
    // elles sont arrivées), plus la livraison courante — une vraie
    // somme, jamais un chaînage.
    private void computeCumulativeFields(ContentValues cv, String woNum, long excludeRowId) {
        if (woNum == null || woNum.isEmpty()) return;
        double newNet   = cv.getAsDouble(COL_NET_L)    != null ? cv.getAsDouble(COL_NET_L)    : 0;
        double newGross = cv.getAsDouble(COL_GROSS_L)  != null ? cv.getAsDouble(COL_GROSS_L)  : 0;
        double presetL  = cv.getAsDouble(COL_PRESET_L) != null ? cv.getAsDouble(COL_PRESET_L) : 0;

        double totalNet = newNet, totalGross = newGross;
        int count = 1;
        DeliveryRow plusRecente = null;

        for (DeliveryRow r : getAllForWo(woNum)) {
            // ✅ CORRIGÉ (19 oct 2026) — exclut la ligne précise mise à jour (par id), plus
            // TOUTES les lignes portant ce job_id : un même job_id peut maintenant servir
            // plusieurs livraisons (lignes finales distinctes) qui doivent rester comptées.
            if (excludeRowId > 0 && r.id == excludeRowId) continue;
            if (TYPE_ANNULATION.equals(r.type)) continue;
            totalNet   += r.netL;
            totalGross += r.grossL;
            count++;
            if (presetL <= 0 && r.presetL > 0) presetL = r.presetL;
            if (plusRecente == null
                    || (r.endUtc != null && !r.endUtc.isEmpty()
                        && (plusRecente.endUtc == null || plusRecente.endUtc.isEmpty()
                            || r.endUtc.compareTo(plusRecente.endUtc) > 0))) {
                plusRecente = r;
            }
        }

        double overage = totalNet > presetL && presetL > 0 ? totalNet - presetL : 0;
        cv.put(COL_TOTAL_NET_L,        totalNet);
        cv.put(COL_TOTAL_GROSS_L,      totalGross);
        cv.put(COL_DELIVERY_COUNT,     count);
        cv.put(COL_PRESET_OVERAGE_L,   overage);
        if (plusRecente != null) {
            cv.put(COL_PREVIOUS_NET_L,     plusRecente.netL);
            cv.put(COL_PREVIOUS_GROSS_L,   plusRecente.grossL);
            cv.put(COL_PREVIOUS_TICKET_NO, plusRecente.ticketNo);
        }
    }

    // ✅ AJOUTÉ (28 août 2026, même correctif) — comme getLatestForWo(),
    // mais exclut une ligne précise par job_id — nécessaire pour que la
    // mise à jour d'une livraison déjà en table (armement → fin) ne se
    // trouve pas elle-même comme "précédente".
    private DeliveryRow getLatestForWoExcludingJobId(String woNum, String excludeJobId) {
        if (excludeJobId == null || excludeJobId.isEmpty()) return getLatestForWo(woNum);
        // ✅ CORRIGÉ (25 sept 2026, demande Paul — "le point important
        // est le delivery-uid... on cumule les ticket_number en lien
        // avec le wo" + investigation du cumul faussé) — trouvé la vraie
        // cause de fond, au-delà du tri du pull Dataverse déjà corrigé :
        // "la plus récente" se déterminait par COL_ID DESC (ordre
        // D'INSERTION locale), pas par la vraie date de livraison.
        // Confirmé par log réel : pullDeliveryByTicket() (recherche d'un
        // seul ticket, ex. 290) s'exécute AVANT pullAllDeliveriesForWorkOrder()
        // (le pull en bloc, trié) sur une BD fraîche — insérant ce ticket
        // isolément (existing=null, traité comme "premier") avant même
        // que les autres livraisons plus anciennes du même WO n'arrivent,
        // brisant la chaîne à sa tête peu importe l'ordre du pull en
        // bloc. Trié maintenant par la vraie chronologie (end_utc), pas
        // l'ordre d'insertion — une ligne sans end_utc (livraison encore
        // en cours, armement seul) se retrouve naturellement en dernier,
        // jamais prise à tort pour "la plus récente terminée".
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=? AND (" + COL_JOB_ID + " IS NULL OR " + COL_JOB_ID + "!=?)",
                new String[]{woNum, excludeJobId},
                null, null,
                "CASE WHEN " + COL_END_UTC + " IS NULL OR " + COL_END_UTC + "='' THEN 0 ELSE 1 END DESC, "
                    + COL_END_UTC + " DESC, " + COL_ID + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLatestForWoExcludingJobId ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getLatestForWoExcludingJobId", e); } catch (Exception ignored) {}
        }
        return null;
    }

    public long insertDelivery(ContentValues cv) {
        long now = System.currentTimeMillis();
        cv.put(COL_TS_CREATED_MS, now);
        cv.put(COL_TS_UPDATED_MS, now);
        if (!cv.containsKey(COL_SYNC_STATUS)) {
            cv.put(COL_SYNC_STATUS, SYNC_PENDING);
        }

        // Calculer les champs historique depuis la dernière ligne du même wo_num
        String woNum = cv.getAsString(COL_WO_NUM);
        computeCumulativeFields(cv, woNum, -1);

        try {
            long id = getWritableDatabase().insertOrThrow(TABLE_DELIVERY, null, cv);
            if (id == -1) logInsertIgnore("insertDelivery", cv);
            Log.i(TAG, "insertDelivery INSERT id=" + id + " wo=" + woNum
                + " type=" + cv.getAsString(COL_TYPE)
                + " ticket=" + cv.getAsString(COL_TICKET_NO)
                + " net=" + cv.getAsDouble(COL_NET_L));
            // ✅ FIX (12 août 2026) — invalide le cache getAllForWo() pour ce
            // WO précis : une vraie écriture vient de se produire, le cache
            // de 3s pourrait sinon servir des données périmées à un
            // appelant suivant dans cette même fenêtre.
            if (woNum != null) ALL_FOR_WO_CACHE.remove(woNum);
            return id;
        } catch (Exception e) {
            Log.e(TAG, "insertDelivery ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.insertDelivery", e); } catch (Exception ignored) {}
            return -1;
        }
    }

    /**
     * Met à jour une transaction existante (par ID local).
     */
    /** Chercher une livraison par ticket_no — pour retrouver le WO depuis le registre */
    public DeliveryRow getByTicketNo(String ticketNo) {
        if (ticketNo == null || ticketNo.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_TICKET_NO + "=?", new String[]{ticketNo},
                null, null,
                COL_ID + " DESC", "1" /* ✅ CORRIGÉ 18 sept 2026 - meme bug que getLatestForWo(), transaction_no jamais ecrit localement */)) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) { Log.e(TAG, "getByTicketNo ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getByTicketNo", e); } catch (Exception ignored) {} }
        return null;
    }

    // ✅ AJOUTÉ (16 oct 2026, demande Paul — "le ticket number en tenant compte du #série du
    // registre") — le numéro de ticket est propre à chaque registre : deux registres peuvent
    // avoir le même. Sans #série, retombe sur getByTicketNo().
    public DeliveryRow getByTicketNoAndSerial(String ticketNo, String serialId) {
        if (ticketNo == null || ticketNo.isEmpty()) return null;
        if (serialId == null || serialId.trim().isEmpty()) return getByTicketNo(ticketNo);
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_TICKET_NO + "=? AND " + COL_SERIAL_ID + "=?", new String[]{ticketNo, serialId.trim()},
                null, null,
                COL_ID + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) { Log.e(TAG, "getByTicketNoAndSerial ERR: " + e.getMessage()); }
        return null;
    }

    public int updateDelivery(long id, ContentValues cv) {
        cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
        try {
            return getWritableDatabase().update(TABLE_DELIVERY, cv,
                COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "updateDelivery ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.updateDelivery", e); } catch (Exception ignored) {}
            return 0;
        }
    }

    /**
     * Marque une transaction comme SYNCED avec son GUID Dataverse.
     */
    public void markSynced(long id, String dataverseId) {
        ContentValues cv = new ContentValues();
        cv.put(COL_SYNC_STATUS, SYNC_SYNCED);
        cv.put(COL_DATAVERSE_ID, dataverseId);
        cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
        try {
            getWritableDatabase().update(TABLE_DELIVERY, cv,
                COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "markSynced ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.markSynced", e); } catch (Exception ignored) {}
        }
    }

    /**
     * ✅ AJOUTÉ (16 oct 2026, demande Paul) — ligne SYNCED localement mais ABSENTE de Dataverse :
     * remise en PENDING pour être repoussée. Clé = ticket_no + #série (serial vide : ticket seul).
     * @return nombre de lignes modifiées
     */
    public int remettreEnPending(String ticketNo, String serialId) {
        if (ticketNo == null || ticketNo.trim().isEmpty()) return 0;
        ContentValues cv = new ContentValues();
        cv.put(COL_SYNC_STATUS, SYNC_PENDING);
        cv.putNull(COL_DATAVERSE_ID);
        cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
        try {
            boolean avecSerie = serialId != null && !serialId.trim().isEmpty();
            return getWritableDatabase().update(TABLE_DELIVERY, cv,
                COL_TICKET_NO + "=?" + (avecSerie ? " AND " + COL_SERIAL_ID + "=?" : "") + " AND " + COL_SYNC_STATUS + "=?",
                avecSerie ? new String[]{ticketNo, serialId.trim(), SYNC_SYNCED}
                          : new String[]{ticketNo, SYNC_SYNCED});
        } catch (Exception e) {
            Log.e(TAG, "remettreEnPending ERR: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Marque une transaction comme ERROR avec un message.
     */
    public void markError(long id, String errorMsg) {
        ContentValues cv = new ContentValues();
        cv.put(COL_SYNC_STATUS, SYNC_ERROR);
        cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
        try {
            getWritableDatabase().update(TABLE_DELIVERY, cv,
                COL_ID + "=?", new String[]{String.valueOf(id)});
            Log.w(TAG, "markError id=" + id + " msg=" + errorMsg);
        } catch (Exception e) {
            Log.e(TAG, "markError ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.markError", e); } catch (Exception ignored) {}
        }
    }

    /**
     * Retourne toutes les transactions à synchroniser — PENDING **et** ERROR.
     * ✅ FIX (3 août 2026, confirmé sur tickets 10899/10900/10905) : avant ce fix,
     * cette méthode ne retournait QUE les lignes PENDING. Dès qu'un push échouait une
     * seule fois, pushPending() appelait markError() (SYNC_ERROR), et la ligne sortait
     * DÉFINITIVEMENT du champ de cette requête — le service de sync périodique
     * (DeliverySyncWorker, toutes les 15 min) ne la relisait donc plus jamais, même
     * après des heures. La livraison restait alors invisible dans Dataverse
     * (filgo_lcr_delivery_status) indéfiniment, même si msdyn_workordersummary (mis à
     * jour par un chemin de code séparé, patchSummaryConsolidated(), qui lit la BD
     * locale SANS filtrer sur sync_status) affichait déjà cette livraison — d'où la
     * confusion "je l'ai dans le résumé du WO mais pas dans la table de livraisons".
     * Inclure ERROR ici permet un vrai retry automatique à chaque cycle, au lieu d'un
     * abandon silencieux après le premier échec.
     */
    public List<DeliveryRow> getPendingDeliveries() {
        List<DeliveryRow> list = new ArrayList<>();
        // ❌ CORRIGÉ (8 sept 2026, demande Paul — confirmé par vraie BD :
        // une ligne écrite à l'armement (stop_type=RUNNING_FLOWING,
        // sync_status=PENDING, net=0, ticket="") a été poussée vers
        // Dataverse et marquée SYNCED 1 SECONDE après sa création — avant
        // même que la vraie fin de livraison n'ait la chance d'écrire les
        // vraies valeurs par-dessus. sync_status=PENDING sert à DEUX
        // choses différentes sans les distinguer : "trace de secours à
        // l'armement" et "prêt à pousser vers Dataverse". Exclut
        // maintenant explicitement RUNNING_FLOWING : une livraison encore
        // en train de couler n'est jamais éligible au push, peu importe
        // son sync_status.
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_SYNC_STATUS + " IN (?, ?) AND " + COL_STOP_TYPE + " != ?",
                new String[]{SYNC_PENDING, SYNC_ERROR, "RUNNING_FLOWING"},
                null, null, COL_TS_CREATED_MS + " ASC")) {
            while (c.moveToNext()) {
                list.add(DeliveryRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getPendingDeliveries ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getPendingDeliveries", e); } catch (Exception ignored) {}
        }
        return list;
    }

    /**
     * ✅ AJOUTÉ (21 sept 2026, demande Paul) — nombre total de lignes,
     * peu importe le statut — pour détecter une table vraiment vide
     * (BD vierge/réinstall/vidée pour un test) avant de déclencher
     * automatiquement restoreAllAsync() au démarrage.
     */
    public int getTotalCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_DELIVERY, null)) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            Log.e(TAG, "getTotalCount ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getTotalCount", e); } catch (Exception ignored) {}
        }
        return 0;
    }

    /**
     * Retourne le nombre de transactions PENDING (indicateur UI).
     */
    public int getPendingCount() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_DELIVERY +
                " WHERE " + COL_SYNC_STATUS + "=?",
                new String[]{SYNC_PENDING})) {
            if (c.moveToFirst()) return c.getInt(0);
        } catch (Exception e) {
            Log.e(TAG, "getPendingCount ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getPendingCount", e); } catch (Exception ignored) {}
        }
        return 0;
    }

    /**
     * Vérifie si une livraison est en cours (PENDING ou pas encore terminée)
     * pour un WO différent du WO actuel.
     * Retourne le wo_num en cours si conflit, null sinon.
     */
    public String getActiveConflictWo(String currentWoNum) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT " + COL_WO_NUM + " FROM " + TABLE_DELIVERY +
                " WHERE " + COL_SYNC_STATUS + "=?" +
                " AND " + COL_WO_NUM + "!=?" +
                " AND " + COL_PRESET_STATUS + " IS NULL" +
                " LIMIT 1",
                new String[]{SYNC_PENDING, currentWoNum != null ? currentWoNum : ""})) {
            if (c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
            Log.e(TAG, "getActiveConflictWo ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getActiveConflictWo", e); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Retourne la dernière transaction pour un WO donné.
     */
    public DeliveryRow getLatestForWo(String woNum) {
        // ✅ CORRIGÉ (18 sept 2026, demande Paul — "row.ticketNo=245"
        // restait figé dans le log tout au long d'une livraison qui
        // était pourtant rendue à 246 puis 247) — trouvé : le tri se
        // faisait par transaction_no, un champ QUI N'EST JAMAIS ÉCRIT
        // localement à l'armement (seulement lu depuis Dataverse une
        // fois synchronisé, filgo_transaction_no). Une nouvelle ligne
        // locale reste à la valeur par défaut du schéma (DEFAULT 1),
        // alors qu'une ancienne ligne déjà synchronisée porte une vraie
        // valeur Dataverse — donc "ORDER BY transaction_no DESC" plaçait
        // TOUJOURS l'ancienne ligne synchronisée en premier, peu importe
        // combien de nouvelles livraisons suivaient. COL_ID
        // (AUTOINCREMENT, purement local, jamais dépendant de Dataverse)
        // reflète le vrai ordre d'insertion — utilisé à la place de
        // transaction_no à cette époque.
        // ✅ CORRIGÉ DE NOUVEAU (25 sept 2026, demande Paul — investigation
        // du cumul WO faussé) — COL_ID (ordre d'INSERTION locale) reste
        // vulnérable au même problème de fond que transaction_no visait
        // à régler : confirmé par log réel, un pull Dataverse à ticket
        // unique (pullDeliveryByTicket) peut insérer une livraison
        // récente AVANT qu'un pull en bloc plus tard n'insère des
        // livraisons chronologiquement plus anciennes du même WO sur une
        // BD fraîche — corrompant tout ce qui dépend de "la plus
        // récente" (Bon déjà complété, RETOUR_WO, le cumul), pas
        // seulement l'armement. Trié maintenant par la vraie
        // chronologie (end_utc) — une ligne sans end_utc (livraison
        // encore en cours) ne prend jamais à tort la place de la
        // dernière livraison réellement terminée.
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=?", new String[]{woNum},
                null, null,
                "CASE WHEN " + COL_END_UTC + " IS NULL OR " + COL_END_UTC + "='' THEN 0 ELSE 1 END DESC, "
                    + COL_END_UTC + " DESC, " + COL_ID + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLatestForWo ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getLatestForWo", e); } catch (Exception ignored) {}
        }
        return null;
    }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — "si jamais on a un crash une
    // réinstallation on veut être en mesure de la reprendre là où elle
    // est") — retrouve une livraison par job_id, l'ancre stable connue
    // dès l'armement, avant même que ticket_no ne soit fiable.
    public DeliveryRow getByJobId(String jobId) {
        if (jobId == null || jobId.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_JOB_ID + "=?", new String[]{jobId},
                // ✅ CORRIGÉ (19 oct 2026) — un même job_id peut porter plusieurs lignes
                // (livraisons successives réutilisant le jobId) : la ligne EN COURS
                // (RUNNING_FLOWING) d'abord, sinon la plus récente.
                null, null,
                "CASE WHEN " + COL_STOP_TYPE + "='RUNNING_FLOWING' THEN 0 ELSE 1 END, "
                    + COL_TS_CREATED_MS + " DESC, " + COL_ID + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getByJobId ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getByJobId", e); } catch (Exception ignored) {}
        }
        return null;
    }

    // ✅ AJOUTÉ (28 août 2026, même correctif) — retrouve la ligne
    // "filet de sécurité" (running_flowing, pas encore terminée) pour ce
    // WO/serial, au retour dans le tab après un crash. STOP_TYPE=
    // "RUNNING_FLOWING" distingue cette ligne d'une vraie ligne finale
    // (STOP_TYPE="LIVRAISON") ou d'une annulation (STOP_TYPE="ANNULATION")
    // — ne cherche QUE les lignes jamais arrivées à terme.
    public DeliveryRow getRunningFlowingSafetyNet(String woNum, String serialId) {
        // ✅ AJOUTÉ (13 oct 2026, demande Paul) — point d'entrée commun de
        // toutes les reprises (MainActivity.upsertRegisterTabFromScan,
        // [FINALISATION-ORPHELINE], [RECUP-RUNNING], [FILET-CONNECTED]) :
        // avant de renvoyer une ligne "non résolue", écarte celles qu'une
        // livraison plus récente du même registre a remplacées (orphelines)
        // pour qu'elles ne détournent plus un nouveau deep link.
        if (serialId != null && !serialId.isEmpty()) {
            try { marquerOrphelines(appContext, serialId); } catch (Exception ignoredOrph) {}
        }
        String where; String[] args;
        if (woNum != null && !woNum.isEmpty()) {
            where = COL_WO_NUM + "=? AND " + COL_STOP_TYPE + "=?";
            args = new String[]{woNum, "RUNNING_FLOWING"};
        } else if (serialId != null && !serialId.isEmpty()) {
            where = COL_SERIAL_ID + "=? AND " + COL_STOP_TYPE + "=?";
            args = new String[]{serialId, "RUNNING_FLOWING"};
        } else {
            return null;
        }
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null, where, args,
                null, null, COL_TS_CREATED_MS + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getRunningFlowingSafetyNet ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getRunningFlowingSafetyNet", e); } catch (Exception ignored) {}
        }
        return null;
    }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on va ajouter une étape
    // avant de démarrer la livraison... on ne mettra pas à jour
    // l'enregistrement local et sur dataverse pour cette livraison") —
    // vraie sémantique upsert par job_id : si une ligne avec ce job_id
    // existe déjà (créée à l'armement, RUNNING_FLOWING), UPDATE cette
    // MÊME ligne au fil de la progression — jamais un doublon, jamais
    // ignoré en silence par la contrainte UNIQUE(wo_num, ticket_no)
    // (qui ne s'applique qu'aux vraies livraisons complétées, via
    // insertDelivery() — fonction séparée, inchangée, avec son propre
    // calcul d'historique previous/total qui ne s'applique pas ici,
    // puisqu'une livraison encore en cours n'est pas "livrée"). Retourne
    // l'id de la ligne (nouvelle ou mise à jour).
    // ✅ AJOUTÉ (28 août 2026, demande Paul — "pourquoi ne prends-tu pas le
    // même format que la livraison, pourquoi es-tu obligé d'avoir une
    // nouvelle fonction??? il faut pas juste coder pour coder") — UNE
    // SEULE fonction partagée, utilisée par TOUS les points d'écriture
    // (armement, fin normale, annulation, récupération, finalisation
    // orpheline, retour au bon de travail) — élimine le patron répété
    // toute la journée où chaque fonction reconstruisait sa propre
    // structure séparément, oubliant un champ ici, l'ajoutant là. Un
    // seul endroit à maintenir désormais. net_l/gross_l valent 0 à
    // l'armement, les vraies valeurs à la fin — MÊME structure dans les
    // deux cas, comme demandé.
    // ✅ CORRIGÉ (28 août 2026, demande Paul — "utilise le même format que
    // livraison terminée, et si on a besoin de reconstruire la livraison
    // on doit aller chercher les infos aussi dans le payload") — le
    // payload_complet (description/code/type/preset nichés dedans) est
    // maintenant construit UNE SEULE FOIS, ici, dans cette fonction —
    // jamais par chaque appelant séparément. La BD le stocke aussi (via
    // COL_PAYLOAD_JSON, déjà utilisée par LocalDeliveryBackup pour la
    // restauration) — même source unique, BD et JSON, exactement comme
    // le fait déjà une livraison terminée (le vrai payload du registre,
    // extraJson, contient déjà ces mêmes champs nichés).
    // ✅ AJOUTÉ (28 août 2026, demande Paul — "continue à chercher, passe
    // en revue toute l'application") — trouvé : ApiServer.java a déjà un
    // mécanisme équivalent (extractBestEffort), mais privé, spécifique à
    // ce fichier. Cette version, publique et partagée ici, vérifie
    // plusieurs clés possibles en repli (ex: "ticket_no", "ticketNo",
    // "ticket") — et, si aucune ne matche au niveau racine, cherche aussi
    // dans un sous-objet "result" imbriqué (le vrai registre y range
    // parfois ces champs, confirmé pour product_number/preset_requested).
    // Remplace les replis manuels répétés un peu partout dans
    // l'application.
    public static String optFirstString(org.json.JSONObject j, String... keys) {
        if (j == null) return "";
        for (String k : keys) {
            String v = j.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        org.json.JSONObject result = j.optJSONObject("result");
        if (result != null) {
            for (String k : keys) {
                String v = result.optString(k, "");
                if (!v.isEmpty()) return v;
            }
        }
        return "";
    }

    public static int optFirstInt(org.json.JSONObject j, int defaultVal, String... keys) {
        if (j == null) return defaultVal;
        for (String k : keys) {
            if (j.has(k)) return j.optInt(k, defaultVal);
        }
        org.json.JSONObject result = j.optJSONObject("result");
        if (result != null) {
            for (String k : keys) {
                if (result.has(k)) return result.optInt(k, defaultVal);
            }
        }
        return defaultVal;
    }

    public static double optFirstDouble(org.json.JSONObject j, double defaultVal, String... keys) {
        if (j == null) return defaultVal;
        for (String k : keys) {
            if (j.has(k)) return j.optDouble(k, defaultVal);
        }
        org.json.JSONObject result = j.optJSONObject("result");
        if (result != null) {
            for (String k : keys) {
                if (result.has(k)) return result.optDouble(k, defaultVal);
            }
        }
        return defaultVal;
    }

    private static String construirePayloadInterne(
            int produitNo, String produitDescription, String produitCode, int produitType,
            double presetL, String extra) {
        try {
            org.json.JSONObject p = (extra != null && !extra.isEmpty())
                ? new org.json.JSONObject(extra) : new org.json.JSONObject();
            p.put("active_product", produitNo);
            p.put("active_product_description", produitDescription != null ? produitDescription : "");
            p.put("active_product_code", produitCode != null ? produitCode : "");
            p.put("active_product_type", produitType);
            p.put("preset_requested", presetL);
            return p.toString();
        } catch (Exception e) {
            return extra != null ? extra : "{}";
        }
    }

    // Surcharge historique (sans stopid/productid) — conservée pour tout appelant existant.
    // null = ne PAS écrire ces colonnes (un upsert ne doit jamais effacer des GUID déjà présents).
    public static ContentValues construireLivraisonComplete(
            String jobId, String woNum, String woIdGuid, String ticketNo, String saleNo,
            double netL, double grossL, String serialId, int lcrnode, String btmac,
            int produitNo, String produitDescription, String produitCode, int produitType,
            double presetL, String type, String stopType, String syncStatus, String payloadExtra) {
        return construireLivraisonComplete(jobId, woNum, woIdGuid, ticketNo, saleNo,
            netL, grossL, serialId, lcrnode, btmac,
            produitNo, produitDescription, produitCode, produitType,
            presetL, type, stopType, syncStatus, payloadExtra, null, null);
    }

    public static ContentValues construireLivraisonComplete(
            String jobId, String woNum, String woIdGuid, String ticketNo, String saleNo,
            double netL, double grossL, String serialId, int lcrnode, String btmac,
            int produitNo, String produitDescription, String produitCode, int produitType,
            double presetL, String type, String stopType, String syncStatus, String payloadExtra,
            String stopid, String productid) {
        ContentValues cv = new ContentValues();
        if (jobId != null && !jobId.isEmpty()) cv.put(COL_JOB_ID, jobId);
        cv.put(COL_WO_NUM, woNum != null ? woNum : "");
        cv.put(COL_WO_ID_GUID, woIdGuid != null ? woIdGuid : "");
        if (stopid != null)    cv.put(COL_STOPID, stopid);
        if (productid != null) cv.put(COL_PRODUCTID, productid);
        cv.put(COL_TICKET_NO, ticketNo != null ? ticketNo : "");
        cv.put(COL_SALE_NO, saleNo != null ? saleNo : "");
        cv.put(COL_NET_L, netL);
        cv.put(COL_GROSS_L, grossL);
        cv.put(COL_SERIAL_ID, serialId != null ? serialId : "");
        cv.put(COL_LCRNODE, lcrnode);
        cv.put(COL_BTMAC, btmac != null ? btmac : "");
        cv.put(COL_PRODUIT_NO, produitNo);
        cv.put(COL_PRESET_L, presetL);
        cv.put(COL_TYPE, type != null ? type : TYPE_ORIGINAL);
        cv.put(COL_STOP_TYPE, stopType != null ? stopType : "LIVRAISON");
        cv.put(COL_SYNC_STATUS, syncStatus != null ? syncStatus : SYNC_PENDING);
        cv.put(COL_PAYLOAD_JSON, construirePayloadInterne(
            produitNo, produitDescription, produitCode, produitType, presetL, payloadExtra));
        return cv;
    }

    // Surcharge historique (sans stopid/productid) — les clés restent présentes, vides.
    public static org.json.JSONObject construireJsonLivraisonComplet(
            String jobId, String woNum, String woIdGuid, String ticketNo, String saleNo,
            double netL, double grossL, String serialId, int lcrnode, String btmac,
            int produitNo, String produitDescription, String produitCode, int produitType,
            double presetL, String type, String syncStatus, String payloadExtra) throws org.json.JSONException {
        return construireJsonLivraisonComplet(jobId, woNum, woIdGuid, ticketNo, saleNo,
            netL, grossL, serialId, lcrnode, btmac,
            produitNo, produitDescription, produitCode, produitType,
            presetL, type, syncStatus, payloadExtra, "", "");
    }

    public static org.json.JSONObject construireJsonLivraisonComplet(
            String jobId, String woNum, String woIdGuid, String ticketNo, String saleNo,
            double netL, double grossL, String serialId, int lcrnode, String btmac,
            int produitNo, String produitDescription, String produitCode, int produitType,
            double presetL, String type, String syncStatus, String payloadExtra,
            String stopid, String productid) throws org.json.JSONException {
        org.json.JSONObject j = new org.json.JSONObject();
        if (jobId != null && !jobId.isEmpty()) j.put("job_id", jobId);
        j.put("wo_num", woNum != null ? woNum : "");
        j.put("wo_id_guid", woIdGuid != null ? woIdGuid : "");
        j.put("stopid", stopid != null ? stopid : "");
        j.put("productid", productid != null ? productid : "");
        j.put("ticket_no", ticketNo != null ? ticketNo : "");
        j.put("sale_no", saleNo != null ? saleNo : "");
        j.put("net_l", netL);
        j.put("gross_l", grossL);
        j.put("serial_id", serialId != null ? serialId : "");
        j.put("lcrnode", lcrnode);
        j.put("btmac", btmac != null ? btmac : "");
        j.put("type", type != null ? type : TYPE_ORIGINAL);
        j.put("backup_ts", System.currentTimeMillis());
        j.put("payload_complet", construirePayloadInterne(
            produitNo, produitDescription, produitCode, produitType, presetL, payloadExtra));
        j.put("sync_status", syncStatus != null ? syncStatus : SYNC_PENDING);
        return j;
    }

    // ✅ AJOUTÉ (13 oct 2026, demande Paul — "dans le cas d'une situation
    // orphelin avec un deeplink le plus simple est que tu la marques comme
    // orphelin et sur dataverse on l'indique comme orphelin" / "je le
    // mettrais dans le payload json"). Confirmé par la BD du 13 oct 17:20 :
    // le repli connectBtByMacAndOpenTab écrivait une ligne RUNNING_FLOWING
    // sous un jobId aléatoire (ticket 338 = numéro de vente AVANT le RUN)
    // alors que le suivi tournait sous un AUTRE jobId (ticket 339) — la
    // première ligne ne se finalisait jamais, puis détournait les deep
    // links suivants. Une ligne RUNNING_FLOWING est déclarée orpheline
    // seulement si, pour le MÊME registre (#série) et le MÊME bon de
    // travail, une ligne d'un AUTRE job_id, créée APRÈS elle, porte un
    // numéro de ticket numériquement SUPÉRIEUR. (Le numéro de ticket sert
    // de garde : des lignes restaurées depuis Dataverse sur une BD neuve
    // ont un ts_created récent mais jamais un ticket supérieur.) Ticket
    // vide/non numérique = on ne touche à rien (reprise après crash
    // préservée).
    // Effet : stop_type=ORPHELIN, sync_status=PENDING (poussée vers
    // Dataverse), payload JSON enrichi ("orphelin", "orphelin_raison",
    // "orphelin_ts", "orphelin_remplace_par"), copie JSON locale réécrite
    // sans "RUNNING_FLOWING" (sinon retrouvée comme filet de sécurité),
    // et ActiveDeliveryStore vidé s'il pointait ce job. Retourne le
    // nombre de lignes marquées.
    public int marquerOrphelines(Context ctx, String serialId) {
        if (serialId == null || serialId.isEmpty()) return 0;
        int marquees = 0;
        List<DeliveryRow> toutes = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_SERIAL_ID + "=?", new String[]{serialId},
                null, null, COL_TS_CREATED_MS + " ASC")) {
            while (c.moveToNext()) toutes.add(DeliveryRow.fromCursor(c));
        } catch (Exception e) {
            Log.e(TAG, "marquerOrphelines lecture ERR: " + e.getMessage());
            return 0;
        }
        for (DeliveryRow r : toutes) {
            if (!"RUNNING_FLOWING".equals(r.stopType)) continue;
            if (r.jobId == null || r.jobId.isEmpty()) continue;
            long tRef;
            try { tRef = Long.parseLong(r.ticketNo == null ? "" : r.ticketNo.trim()); }
            catch (NumberFormatException nfe) { continue; }
            DeliveryRow remplacante = null;
            for (DeliveryRow s : toutes) {
                if (s == r) continue;
                if (s.jobId != null && s.jobId.equals(r.jobId)) continue;
                if (estOrphelin(s)) continue;
                if (s.woNum == null || !s.woNum.equals(r.woNum)) continue;
                if (s.tsCreatedMs <= r.tsCreatedMs) continue;
                long tS;
                try { tS = Long.parseLong(s.ticketNo == null ? "" : s.ticketNo.trim()); }
                catch (NumberFormatException nfe) { continue; }
                if (tS > tRef) { remplacante = s; break; }
            }
            if (remplacante == null) continue;

            try {
                org.json.JSONObject pj;
                try { pj = new org.json.JSONObject(r.payloadJson != null && !r.payloadJson.isEmpty() ? r.payloadJson : "{}"); }
                catch (Exception ePj) { pj = new org.json.JSONObject(); }
                String raison = "Armement jamais suivi (jobId different du suivi reel) — remplace par jobId="
                    + remplacante.jobId + " ticket=" + remplacante.ticketNo;
                pj.put("orphelin", true);
                pj.put("orphelin_raison", raison);
                pj.put("orphelin_ts", System.currentTimeMillis());
                pj.put("orphelin_remplace_par", remplacante.jobId);
                pj.put("orphelin_ticket_armement", r.ticketNo);
                // Plus jamais retrouvée comme "filet de sécurité" (recherche par texte)
                String pjStr = pj.toString().replace("RUNNING_FLOWING", "ORPHELIN");

                ContentValues cv = new ContentValues();
                cv.put(COL_STOP_TYPE, STOP_TYPE_ORPHELIN);
                cv.put(COL_SYNC_STATUS, SYNC_PENDING);
                cv.put(COL_PAYLOAD_JSON, pjStr);
                cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
                int n = getWritableDatabase().update(
                    TABLE_DELIVERY, cv, COL_JOB_ID + "=?", new String[]{r.jobId});
                if (n <= 0) continue;
                if (r.woNum != null) ALL_FOR_WO_CACHE.remove(r.woNum);
                marquees++;
                Log.w(TAG, "marquerOrphelines: jobId=" + r.jobId + " ticket=" + r.ticketNo + " → ORPHELIN");
                try { com.pa.lcr.lcp.log.LogBus.api(r.lcrnode, "[ORPHELIN] ligne RUNNING_FLOWING jamais suivie marquée orpheline — jobId="
                    + r.jobId + " ticket=" + r.ticketNo + " wo=" + r.woNum + " — " + raison); } catch (Exception ignoredLb) {}

                // Copie JSON locale (nommée par jobId à l'armement) réécrite sans RUNNING_FLOWING
                if (ctx != null) {
                    try {
                        org.json.JSONObject jf = new org.json.JSONObject();
                        jf.put("job_id", r.jobId);
                        jf.put("wo_num", r.woNum != null ? r.woNum : "");
                        jf.put("wo_id_guid", r.woIdGuid != null ? r.woIdGuid : "");
                        jf.put("stopid", r.stopid != null ? r.stopid : "");
                        jf.put("productid", r.productid != null ? r.productid : "");
                        jf.put("ticket_no", r.ticketNo != null ? r.ticketNo : "");
                        jf.put("sale_no", r.saleNo != null ? r.saleNo : "");
                        jf.put("net_l", r.netL);
                        jf.put("gross_l", r.grossL);
                        jf.put("serial_id", r.serialId != null ? r.serialId : "");
                        jf.put("lcrnode", r.lcrnode);
                        jf.put("btmac", r.btmac != null ? r.btmac : "");
                        jf.put("type", r.type != null ? r.type : TYPE_ORIGINAL);
                        jf.put("backup_ts", System.currentTimeMillis());
                        jf.put("payload_complet", pjStr);
                        jf.put("sync_status", SYNC_PENDING);
                        LocalDeliveryBackup.backupDeliveryAsync(ctx, r.woNum, r.jobId, jf);
                    } catch (Exception eJf) {
                        Log.w(TAG, "marquerOrphelines: copie JSON ERR (non-bloquant): " + eJf.getMessage());
                    }
                    // ActiveDeliveryStore : ne plus pointer ce job
                    try {
                        ActiveDeliveryStore ads = new ActiveDeliveryStore(ctx);
                        ActiveDeliveryStore.ActiveDelivery ad = ads.load();
                        if (ad != null && r.jobId.equals(ad.jobId)) {
                            ads.clear();
                            try { com.pa.lcr.lcp.log.LogBus.api(r.lcrnode, "[ORPHELIN] ActiveDeliveryStore vidé — jobId=" + r.jobId); } catch (Exception ignoredLb2) {}
                        }
                    } catch (Exception eAds) {
                        Log.w(TAG, "marquerOrphelines: ActiveDeliveryStore ERR (non-bloquant): " + eAds.getMessage());
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "marquerOrphelines ERR jobId=" + r.jobId + ": " + e.getMessage());
            }
        }
        return marquees;
    }

    // Une ligne déjà orpheline ne peut pas "remplacer" une autre.
    private static boolean estOrphelin(DeliveryRow s) {
        return STOP_TYPE_ORPHELIN.equals(s.stopType);
    }

    /** Ligne portant exactement ce (wo_num, ticket_no) — la clé de la contrainte UNIQUE. */
    public DeliveryRow getByWoAndTicket(String woNum, String ticketNo) {
        if (woNum == null || woNum.isEmpty() || ticketNo == null || ticketNo.isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=? AND " + COL_TICKET_NO + "=?", new String[]{woNum, ticketNo},
                null, null, COL_ID + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getByWoAndTicket ERR: " + e.getMessage());
        }
        return null;
    }

    private boolean existeTicketVidePourWo(String woNum) {
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, new String[]{COL_ID},
                COL_WO_NUM + "=? AND " + COL_TICKET_NO + "=''", new String[]{woNum},
                null, null, null, "1")) {
            return c.moveToFirst();
        } catch (Exception e) {
            Log.e(TAG, "existeTicketVidePourWo ERR: " + e.getMessage());
            return false;
        }
    }

    private static boolean estLigneFinale(String stopType) {
        return STOP_TYPE_LIVRAISON.equals(stopType) || "ANNULATION".equals(stopType);
    }

    // ✅ AJOUTÉ (19 oct 2026, demande Paul — analyse tickets 61-65) — un INSERT refusé par
    // UNIQUE(wo_num, ticket_no) ON CONFLICT IGNORE retournait -1 sans aucune trace : les
    // litres de la livraison étaient perdus en silence (ids AUTOINCREMENT manquants 2/4/5).
    // ✅ CORRIGÉ (20 oct 2026, demande Paul — log du 20 oct : 307 lignes identiques en ~1 min pendant
    // un débit, séquence AUTOINCREMENT à 336 pour 29 lignes) : (1) l'APPELANT est maintenant journalisé
    // (première frame hors de cette classe) pour identifier qui réécrit sans arrêt ; (2) un seul message
    // par (wo, ticket, appelant) toutes les 10 s, avec le nombre de répétitions supprimées entre-temps.
    private static final java.util.Map<String, long[]> INSERT_IGNORE_VU = new java.util.HashMap<>();

    private void logInsertIgnore(String origine, ContentValues cv) {
        try {
            String wo = cv.getAsString(COL_WO_NUM);
            String tk = cv.getAsString(COL_TICKET_NO);
            String appelant = "?";
            for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
                String cn = el.getClassName();
                if (cn.startsWith("java.") || cn.startsWith("dalvik.") || cn.startsWith("android.")) continue;
                if (cn.equals(LcrDeliveryStatusDb.class.getName())) continue;
                appelant = cn.substring(cn.lastIndexOf('.') + 1) + "." + el.getMethodName() + ":" + el.getLineNumber();
                break;
            }
            String cle = wo + "|" + tk + "|" + appelant;
            long now = System.currentTimeMillis();
            long supprimes = 0;
            synchronized (INSERT_IGNORE_VU) {
                long[] st = INSERT_IGNORE_VU.get(cle); // {dernier log (ms), répétitions supprimées}
                if (st != null && now - st[0] < 10000L) { st[1]++; return; }
                if (st != null) supprimes = st[1];
                INSERT_IGNORE_VU.put(cle, new long[]{now, 0});
            }
            DeliveryRow dup = getByWoAndTicket(wo, tk);
            String msg = "[DB-INSERT-IGNORÉ] " + origine + " wo=" + wo + " ticket=" + tk
                + " net=" + cv.getAsDouble(COL_NET_L) + " stop_type=" + cv.getAsString(COL_STOP_TYPE)
                + " — refusé par UNIQUE(wo_num,ticket_no); ligne existante id="
                + (dup != null ? dup.id + " net=" + dup.netL + " stop_type=" + dup.stopType : "?")
                + " | appelant=" + appelant
                + (supprimes > 0 ? " | " + supprimes + " répétition(s) identique(s) non journalisée(s) depuis le dernier message" : "");
            Log.w(TAG, msg);
            com.pa.lcr.lcp.log.LogBus.api(0, msg);
        } catch (Exception ignored) {}
    }

    /**
     * ✅ AJOUTÉ (19 oct 2026) — faut-il écrire dans une NOUVELLE ligne plutôt que d'écraser
     * la ligne existante portant ce job_id ? Oui si cette ligne est déjà finale
     * (LIVRAISON / ANNULATION) et que l'écriture entrante ne la prolonge pas : nouvel
     * armement (RUNNING_FLOWING) réutilisant le jobId, annulation d'une livraison faite,
     * ou fin de livraison d'un AUTRE ticket.
     */
    private static boolean doitCreerNouvelleLigne(DeliveryRow existing, ContentValues cv) {
        if (existing == null || !estLigneFinale(existing.stopType)) return false;
        String inType = cv.getAsString(COL_STOP_TYPE);
        if ("RUNNING_FLOWING".equals(inType)) return true;
        if (STOP_TYPE_LIVRAISON.equals(existing.stopType)) {
            if ("ANNULATION".equals(inType)) return true;
        } else { // existante = ANNULATION
            if (!"ANNULATION".equals(inType)) return true;
        }
        String inTicket = cv.getAsString(COL_TICKET_NO);
        String exTicket = existing.ticketNo;
        return inTicket != null && !inTicket.trim().isEmpty()
            && exTicket != null && !exTicket.trim().isEmpty()
            && !inTicket.trim().equals(exTicket.trim());
    }

    public long upsertByJobId(ContentValues cv) {
        String jobId = cv.getAsString(COL_JOB_ID);
        String woNum = cv.getAsString(COL_WO_NUM);
        long now = System.currentTimeMillis();
        cv.put(COL_TS_UPDATED_MS, now);

        // Ligne cible à mettre à jour (par id) : celle du job_id, sauf si elle est déjà
        // finale pour une autre livraison (voir doitCreerNouvelleLigne).
        DeliveryRow cible = null;
        if (jobId != null && !jobId.isEmpty()) {
            DeliveryRow existing = getByJobId(jobId);
            if (existing != null) {
                if (doitCreerNouvelleLigne(existing, cv)) {
                    String msg = "[DB-JOBID-REUTILISE] job_id=" + jobId + " ligne finale id=" + existing.id
                        + " ticket=" + existing.ticketNo + " (" + existing.stopType + ", net="
                        + existing.netL + ") conservée — écriture entrante stop_type="
                        + cv.getAsString(COL_STOP_TYPE) + " ticket=" + cv.getAsString(COL_TICKET_NO)
                        + " va dans une nouvelle ligne";
                    Log.w(TAG, msg);
                    try { com.pa.lcr.lcp.log.LogBus.api(0, msg); } catch (Exception ignored) {}
                } else {
                    cible = existing;
                }
            }
        }
        computeCumulativeFields(cv, woNum, cible != null ? cible.id : -1);

        if (cible != null) {
            try {
                int rows = getWritableDatabase().update(
                    TABLE_DELIVERY, cv, COL_ID + "=?", new String[]{String.valueOf(cible.id)});
                if (rows > 0) {
                    if (woNum != null) ALL_FOR_WO_CACHE.remove(woNum);
                    return cible.id;
                }
            } catch (Exception e) {
                Log.e(TAG, "upsertByJobId UPDATE ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.upsertByJobId", e); } catch (Exception ignored) {}
                return -1;
            }
        }

        // Insertion fraîche — mais si (wo_num, ticket_no) existe déjà, l'INSERT serait
        // ignoré en silence (UNIQUE ON CONFLICT IGNORE) et les litres perdus : on traite
        // alors cette ligne existante explicitement.
        String ticket = cv.getAsString(COL_TICKET_NO);
        DeliveryRow dup = getByWoAndTicket(woNum, ticket);
        if (dup != null) {
            boolean entrantEnCours = "RUNNING_FLOWING".equals(cv.getAsString(COL_STOP_TYPE));
            Double inNet = cv.getAsDouble(COL_NET_L);
            boolean dupFinale = estLigneFinale(dup.stopType);
            boolean peutRemplacer = !dupFinale
                || (dup.netL == 0 && inNet != null && inNet > 0 && !entrantEnCours);
            if (!peutRemplacer) {
                String msg = "[DB-UPSERT-IGNORÉ] wo=" + woNum + " ticket=" + ticket + " stop_type="
                    + cv.getAsString(COL_STOP_TYPE) + " net=" + inNet + " — ligne finale id=" + dup.id
                    + " (net=" + dup.netL + ") conservée, écriture entrante non appliquée";
                Log.w(TAG, msg);
                try { com.pa.lcr.lcp.log.LogBus.api(0, msg); } catch (Exception ignored) {}
                return dup.id;
            }
            try {
                computeCumulativeFields(cv, woNum, dup.id);
                int rows = getWritableDatabase().update(
                    TABLE_DELIVERY, cv, COL_ID + "=?", new String[]{String.valueOf(dup.id)});
                if (rows > 0) {
                    if (woNum != null) ALL_FOR_WO_CACHE.remove(woNum);
                    String msg = "[DB-UPSERT-FUSION] wo=" + woNum + " ticket=" + ticket
                        + " — ligne id=" + dup.id + " (" + dup.stopType + ", net=" + dup.netL
                        + ") mise à jour avec stop_type=" + cv.getAsString(COL_STOP_TYPE)
                        + " net=" + inNet + " (l'INSERT aurait été ignoré par UNIQUE)";
                    Log.w(TAG, msg);
                    try { com.pa.lcr.lcp.log.LogBus.api(0, msg); } catch (Exception ignored) {}
                    return dup.id;
                }
            } catch (Exception e) {
                Log.e(TAG, "upsertByJobId FUSION ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.upsertByJobId", e); } catch (Exception ignored) {}
                return -1;
            }
        }

        // Ticket vide ("" à l'armement) : UNIQUE(wo_num, ticket_no) traite "" comme une valeur —
        // une 2e ligne sans ticket pour ce WO serait refusée en silence. NULL, lui, n'entre
        // jamais en conflit : on l'utilise seulement quand une ligne "" existe déjà.
        if ((ticket == null || ticket.trim().isEmpty()) && woNum != null && !woNum.isEmpty()
                && existeTicketVidePourWo(woNum)) {
            cv.putNull(COL_TICKET_NO);
        }
        cv.put(COL_TS_CREATED_MS, now);
        if (!cv.containsKey(COL_SYNC_STATUS)) {
            cv.put(COL_SYNC_STATUS, SYNC_PENDING);
        }
        try {
            long id = getWritableDatabase().insert(TABLE_DELIVERY, null, cv);
            if (id == -1) logInsertIgnore("upsertByJobId", cv);
            if (woNum != null) ALL_FOR_WO_CACHE.remove(woNum);
            return id;
        } catch (Exception e) {
            Log.e(TAG, "upsertByJobId INSERT ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.upsertByJobId", e); } catch (Exception ignored) {}
            return -1;
        }
    }

    /**
     * ✅ Retourne la dernière livraison peu importe le WO.
     * Utilisé pour récupérer node et serial en mode manuel (sans deep link FSM).
     */
    public DeliveryRow getLastDelivery() {
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                null, null,
                null, null,
                COL_ID + " DESC", "1" /* ✅ CORRIGÉ 18 sept 2026 - meme bug que getLatestForWo(), transaction_no jamais ecrit localement */)) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLastDelivery ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getLastDelivery", e); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * ✅ FIX : nécessaire pour retrouver le WO après une reconnexion (diagnostic)
     * suite à une livraison DÉJÀ TERMINÉE — ActiveDeliveryStore est vidé une fois
     * la livraison finie, donc currentWoNum ne peut plus se restaurer via ce
     * chemin. Sans filtre par #série, getLastDelivery() risquait de retourner
     * la livraison d'un AUTRE registre si plusieurs sont connectés en même
     * temps (ex: TCP-LC3 + BT-LCR-II simultanément).
     */
    public DeliveryRow getLastDeliveryForSerial(String serialId) {
        if (serialId == null || serialId.trim().isEmpty()) return null;
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_SERIAL_ID + "=?", new String[]{serialId.trim()},
                null, null,
                COL_ID + " DESC", "1" /* ✅ CORRIGÉ 18 sept 2026 - meme bug que getLatestForWo(), transaction_no jamais ecrit localement */)) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLastDeliveryForSerial ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getLastDeliveryForSerial", e); } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Retourne toutes les livraisons/annulations pour un WO donné,
     * triées par transaction_no ASC. Utilisé pour le payload consolidé Dataverse.
     */
    // ✅ FIX CRITIQUE (12 août 2026, demande Paul — "corrige-moi les 4
    // trous", trou #4 : getAllForWo() refait 3 fois séparément (panneau UI,
    // WorkOrderUpdater, patchDataverse) autour de la même fin de livraison,
    // chacun recréant sa propre instance de LcrDeliveryStatusDb) — cache
    // STATIQUE (partagé entre toutes les instances) de très courte durée
    // (3s), par woNum. Les 3 appelants qui interrogent le même WO à
    // quelques secondes d'intervalle réutilisent maintenant le même
    // résultat au lieu de refaire 3 scans BD complets. Toujours une COPIE
    // défensive retournée — jamais la même instance de liste partagée
    // entre appelants (évite qu'un appelant mutant la liste affecte les
    // autres).
    private static final java.util.Map<String, Object[]> ALL_FOR_WO_CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long ALL_FOR_WO_CACHE_TTL_MS = 3000;

    public List<DeliveryRow> getAllForWo(String woNum) {
        if (woNum != null) {
            Object[] cached = ALL_FOR_WO_CACHE.get(woNum);
            if (cached != null) {
                long ts = (Long) cached[0];
                if (System.currentTimeMillis() - ts < ALL_FOR_WO_CACHE_TTL_MS) {
                    @SuppressWarnings("unchecked")
                    List<DeliveryRow> cachedList = (List<DeliveryRow>) cached[1];
                    return new ArrayList<>(cachedList);
                }
            }
        }
        List<DeliveryRow> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=?", new String[]{woNum},
                null, null, COL_TRANSACTION_NO + " ASC")) {
            while (c.moveToNext()) {
                list.add(DeliveryRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllForWo ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getAllForWo", e); } catch (Exception ignored) {}
        }
        if (woNum != null) {
            ALL_FOR_WO_CACHE.put(woNum, new Object[]{System.currentTimeMillis(), new ArrayList<>(list)});
        }
        return list;
    }

    /**
     * Retourne toutes les transactions d'une tournée.
     */
    public List<DeliveryRow> getDeliveriesForTournee(String tourneeId) {
        List<DeliveryRow> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_TOURNEE_ID + "=?", new String[]{tourneeId},
                null, null, COL_STOP_SEQUENCE + " ASC")) {
            while (c.moveToNext()) {
                list.add(DeliveryRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getDeliveriesForTournee ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getDeliveriesForTournee", e); } catch (Exception ignored) {}
        }
        return list;
    }

    // =========================================================
    // CRUD — filgo_note_template
    // =========================================================

    /**
     * Remplace toutes les notes templates (sync depuis Dataverse).
     */
    public void replaceAllNotes(List<ContentValues> notes) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_NOTE, null, null);
            for (ContentValues cv : notes) {
                db.insertOrThrow(TABLE_NOTE, null, cv);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "replaceAllNotes ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.replaceAllNotes", e); } catch (Exception ignored) {}
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Retourne les notes actives triées par ordre.
     */
    public List<NoteRow> getActiveNotes() {
        List<NoteRow> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_NOTE, null,
                NOTE_COL_ACTIVE + "=1", null,
                null, null, NOTE_COL_ORDRE + " ASC")) {
            while (c.moveToNext()) {
                list.add(NoteRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getActiveNotes ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.getActiveNotes", e); } catch (Exception ignored) {}
        }
        return list;
    }

    // =========================================================
    // Modèle de données — DeliveryRow
    // =========================================================
    public static class DeliveryRow {
        public long   id;
        public String dataverseId;
        public String woNum;
        public String woIdGuid;
        public String tourneeId;
        public int    transactionNo;
        public int    stopSequence;
        public String livreurId;
        public String camionId;
        public String serialId;
        public int    lcrnode;
        public String btmac;
        public String stopType;
        public String type;
        public String source;
        public String ticketNoRef;
        public int    approbationRequired;
        public String approbationStatus;
        public String approbationBy;
        public String approbationTs;
        public String client;
        public int    produitNo;
        public int    compartimentId;
        public double presetL;
        public double prixUnitaire;
        public double tps;
        public double tvq;
        public double taxeCarbone;
        public String memoDispatch;
        public String jobId;
        public String ticketNo;
        public String saleNo;
        public double netL;
        public double grossL;
        public double deltaNetL;
        public double deltaGrossL;
        public String presetStatus;
        public String startUtc;
        public String endUtc;
        public double durationS;
        public double inventaireAvantL;
        public double inventaireApresL;
        public String serialIdOriginal;
        public String serialIdNouveau;
        public String notesLivreur;
        public String syncStatus;
        public String payloadJson;
        public long   tsCreatedMs;
        public long   tsUpdatedMs;

        // Historique
        public double previousNetL;
        public double previousGrossL;
        public String previousTicketNo;
        public double totalNetL;
        public double totalGrossL;
        public int    deliveryCount;
        public double presetOverageL;

        // Erreurs
        public String errorCode;
        public String errorMsg;

        // GUID Field Service (deep link) — transportés seulement
        public String stopid;
        public String productid;

        public static DeliveryRow fromCursor(Cursor c) {
            DeliveryRow r = new DeliveryRow();
            r.id                 = getLong(c, COL_ID);
            r.dataverseId        = getString(c, COL_DATAVERSE_ID);
            r.woNum              = getString(c, COL_WO_NUM);
            r.woIdGuid           = getString(c, COL_WO_ID_GUID);
            r.tourneeId          = getString(c, COL_TOURNEE_ID);
            r.transactionNo      = getInt(c, COL_TRANSACTION_NO);
            r.stopSequence       = getInt(c, COL_STOP_SEQUENCE);
            r.livreurId          = getString(c, COL_LIVREUR_ID);
            r.camionId           = getString(c, COL_CAMION_ID);
            r.serialId           = getString(c, COL_SERIAL_ID);
            r.lcrnode            = getInt(c, COL_LCRNODE);
            r.btmac              = getString(c, COL_BTMAC);
            r.stopType           = getString(c, COL_STOP_TYPE);
            r.type               = getString(c, COL_TYPE);
            r.source             = getString(c, COL_SOURCE);
            r.ticketNoRef        = getString(c, COL_TICKET_NO_REF);
            r.approbationRequired= getInt(c, COL_APPROBATION_REQ);
            r.approbationStatus  = getString(c, COL_APPROBATION_STATUS);
            r.approbationBy      = getString(c, COL_APPROBATION_BY);
            r.approbationTs      = getString(c, COL_APPROBATION_TS);
            r.client             = getString(c, COL_CLIENT);
            r.produitNo          = getInt(c, COL_PRODUIT_NO);
            r.compartimentId     = getInt(c, COL_COMPARTIMENT_ID);
            r.presetL            = getDouble(c, COL_PRESET_L);
            r.prixUnitaire       = getDouble(c, COL_PRIX_UNITAIRE);
            r.tps                = getDouble(c, COL_TPS);
            r.tvq                = getDouble(c, COL_TVQ);
            r.taxeCarbone        = getDouble(c, COL_TAXE_CARBONE);
            r.memoDispatch       = getString(c, COL_MEMO_DISPATCH);
            r.jobId              = getString(c, COL_JOB_ID);
            r.ticketNo           = getString(c, COL_TICKET_NO);
            r.saleNo             = getString(c, COL_SALE_NO);
            r.netL               = getDouble(c, COL_NET_L);
            r.grossL             = getDouble(c, COL_GROSS_L);
            r.deltaNetL          = getDouble(c, COL_DELTA_NET_L);
            r.deltaGrossL        = getDouble(c, COL_DELTA_GROSS_L);
            r.presetStatus       = getString(c, COL_PRESET_STATUS);
            r.startUtc           = getString(c, COL_START_UTC);
            r.endUtc             = getString(c, COL_END_UTC);
            r.durationS          = getDouble(c, COL_DURATION_S);
            r.inventaireAvantL   = getDouble(c, COL_INVENTAIRE_AVANT_L);
            r.inventaireApresL   = getDouble(c, COL_INVENTAIRE_APRES_L);
            r.serialIdOriginal   = getString(c, COL_SERIAL_ID_ORIGINAL);
            r.serialIdNouveau    = getString(c, COL_SERIAL_ID_NOUVEAU);
            r.notesLivreur       = getString(c, COL_NOTES_LIVREUR);
            r.syncStatus         = getString(c, COL_SYNC_STATUS);
            r.payloadJson        = getString(c, COL_PAYLOAD_JSON);
            r.tsCreatedMs        = getLong(c, COL_TS_CREATED_MS);
            r.tsUpdatedMs        = getLong(c, COL_TS_UPDATED_MS);
            r.previousNetL       = getDouble(c, COL_PREVIOUS_NET_L);
            r.previousGrossL     = getDouble(c, COL_PREVIOUS_GROSS_L);
            r.previousTicketNo   = getString(c, COL_PREVIOUS_TICKET_NO);
            r.totalNetL          = getDouble(c, COL_TOTAL_NET_L);
            r.totalGrossL        = getDouble(c, COL_TOTAL_GROSS_L);
            r.deliveryCount      = getInt(c, COL_DELIVERY_COUNT);
            r.presetOverageL     = getDouble(c, COL_PRESET_OVERAGE_L);
            r.errorCode          = getString(c, COL_ERROR_CODE);
            r.errorMsg           = getString(c, COL_ERROR_MSG);
            r.stopid             = getString(c, COL_STOPID);
            r.productid          = getString(c, COL_PRODUCTID);
            return r;
        }

        private static String getString(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
        }
        private static int getInt(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return i >= 0 && !c.isNull(i) ? c.getInt(i) : 0;
        }
        private static long getLong(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0L;
        }
        private static double getDouble(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return i >= 0 && !c.isNull(i) ? c.getDouble(i) : 0.0;
        }
    }

    // =========================================================
    // Modèle de données — NoteRow
    // =========================================================
    public static class NoteRow {
        public long   id;
        public String code;
        public String libelleFr;
        public String libelleEn;
        public String categorie;
        public int    active;
        public int    ordre;

        public static NoteRow fromCursor(Cursor c) {
            NoteRow r = new NoteRow();
            int i;
            i = c.getColumnIndex(NOTE_COL_ID);         r.id        = i >= 0 ? c.getLong(i)   : 0;
            i = c.getColumnIndex(NOTE_COL_CODE);        r.code      = i >= 0 ? c.getString(i) : null;
            i = c.getColumnIndex(NOTE_COL_LIBELLE_FR);  r.libelleFr = i >= 0 ? c.getString(i) : null;
            i = c.getColumnIndex(NOTE_COL_LIBELLE_EN);  r.libelleEn = i >= 0 ? c.getString(i) : null;
            i = c.getColumnIndex(NOTE_COL_CATEGORIE);   r.categorie = i >= 0 ? c.getString(i) : null;
            i = c.getColumnIndex(NOTE_COL_ACTIVE);      r.active    = i >= 0 ? c.getInt(i)    : 1;
            i = c.getColumnIndex(NOTE_COL_ORDRE);       r.ordre     = i >= 0 ? c.getInt(i)    : 0;
            return r;
        }

        /** Retourne le libellé selon la langue système. */
        public String getLibelle(String lang) {
            if ("fr".equals(lang) || libelleFr != null) return libelleFr;
            return libelleEn != null ? libelleEn : code;
        }
    }

    // =========================================================
    // Helper colonne
    // =========================================================
    private static void addColumnIfMissing(SQLiteDatabase db, String table, String col, String type) {
        boolean exists = false;
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            int nameIdx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (col.equalsIgnoreCase(c.getString(nameIdx))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type);
            } catch (Exception e) {
                Log.e(TAG, "addColumnIfMissing ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "LcrDeliveryStatusDb.addColumnIfMissing", e); } catch (Exception ignored) {}
            }
        }
    }
}