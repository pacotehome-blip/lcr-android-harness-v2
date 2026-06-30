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
    public static final int    DB_VERSION = 3; // v3: UNIQUE(wo_num, ticket_no) anti-doublon

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
    public LcrDeliveryStatusDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
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
    public long insertDelivery(ContentValues cv) {
        long now = System.currentTimeMillis();
        cv.put(COL_TS_CREATED_MS, now);
        cv.put(COL_TS_UPDATED_MS, now);
        if (!cv.containsKey(COL_SYNC_STATUS)) {
            cv.put(COL_SYNC_STATUS, SYNC_PENDING);
        }

        // Calculer les champs historique depuis la dernière ligne du même wo_num
        String woNum = cv.getAsString(COL_WO_NUM);
        if (woNum != null && !woNum.isEmpty()) {
            DeliveryRow existing = getLatestForWo(woNum);
            if (existing != null) {
                double newNet   = cv.getAsDouble(COL_NET_L)    != null ? cv.getAsDouble(COL_NET_L)    : 0;
                double newGross = cv.getAsDouble(COL_GROSS_L)  != null ? cv.getAsDouble(COL_GROSS_L)  : 0;
                double presetL  = cv.getAsDouble(COL_PRESET_L) != null ? cv.getAsDouble(COL_PRESET_L) : existing.presetL;
                double totalNet   = existing.totalNetL  + newNet;
                double totalGross = existing.totalGrossL + newGross;
                int    count      = existing.deliveryCount + 1;
                double overage    = totalNet > presetL && presetL > 0 ? totalNet - presetL : 0;

                cv.put(COL_PREVIOUS_NET_L,     existing.netL);
                cv.put(COL_PREVIOUS_GROSS_L,   existing.grossL);
                cv.put(COL_PREVIOUS_TICKET_NO, existing.ticketNo);
                cv.put(COL_TOTAL_NET_L,        totalNet);
                cv.put(COL_TOTAL_GROSS_L,      totalGross);
                cv.put(COL_DELIVERY_COUNT,     count);
                cv.put(COL_PRESET_OVERAGE_L,   overage);
            } else {
                // Première ligne pour ce WO
                double newNet   = cv.getAsDouble(COL_NET_L)    != null ? cv.getAsDouble(COL_NET_L)    : 0;
                double newGross = cv.getAsDouble(COL_GROSS_L)  != null ? cv.getAsDouble(COL_GROSS_L)  : 0;
                double presetL  = cv.getAsDouble(COL_PRESET_L) != null ? cv.getAsDouble(COL_PRESET_L) : 0;
                cv.put(COL_TOTAL_NET_L,      newNet);
                cv.put(COL_TOTAL_GROSS_L,    newGross);
                cv.put(COL_DELIVERY_COUNT,   1);
                cv.put(COL_PRESET_OVERAGE_L, newNet > presetL && presetL > 0 ? newNet - presetL : 0);
            }
        }

        try {
            long id = getWritableDatabase().insertOrThrow(TABLE_DELIVERY, null, cv);
            Log.i(TAG, "insertDelivery INSERT id=" + id + " wo=" + woNum
                + " type=" + cv.getAsString(COL_TYPE)
                + " ticket=" + cv.getAsString(COL_TICKET_NO)
                + " net=" + cv.getAsDouble(COL_NET_L));
            return id;
        } catch (Exception e) {
            Log.e(TAG, "insertDelivery ERR: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Met à jour une transaction existante (par ID local).
     */
    public int updateDelivery(long id, ContentValues cv) {
        cv.put(COL_TS_UPDATED_MS, System.currentTimeMillis());
        try {
            return getWritableDatabase().update(TABLE_DELIVERY, cv,
                COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            Log.e(TAG, "updateDelivery ERR: " + e.getMessage());
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
            Log.e(TAG, "markSynced ERR: " + e.getMessage());
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
            Log.e(TAG, "markError ERR: " + e.getMessage());
        }
    }

    /**
     * Retourne toutes les transactions PENDING à synchroniser.
     */
    public List<DeliveryRow> getPendingDeliveries() {
        List<DeliveryRow> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_SYNC_STATUS + "=?", new String[]{SYNC_PENDING},
                null, null, COL_TS_CREATED_MS + " ASC")) {
            while (c.moveToNext()) {
                list.add(DeliveryRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getPendingDeliveries ERR: " + e.getMessage());
        }
        return list;
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
            Log.e(TAG, "getPendingCount ERR: " + e.getMessage());
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
            Log.e(TAG, "getActiveConflictWo ERR: " + e.getMessage());
        }
        return null;
    }

    /**
     * Retourne la dernière transaction pour un WO donné.
     */
    public DeliveryRow getLatestForWo(String woNum) {
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=?", new String[]{woNum},
                null, null,
                COL_TRANSACTION_NO + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLatestForWo ERR: " + e.getMessage());
        }
        return null;
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
                COL_TRANSACTION_NO + " DESC", "1")) {
            if (c.moveToFirst()) return DeliveryRow.fromCursor(c);
        } catch (Exception e) {
            Log.e(TAG, "getLastDelivery ERR: " + e.getMessage());
        }
        return null;
    }

    /**
     * Retourne toutes les livraisons/annulations pour un WO donné,
     * triées par transaction_no ASC. Utilisé pour le payload consolidé Dataverse.
     */
    public List<DeliveryRow> getAllForWo(String woNum) {
        List<DeliveryRow> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                TABLE_DELIVERY, null,
                COL_WO_NUM + "=?", new String[]{woNum},
                null, null, COL_TRANSACTION_NO + " ASC")) {
            while (c.moveToNext()) {
                list.add(DeliveryRow.fromCursor(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllForWo ERR: " + e.getMessage());
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
            Log.e(TAG, "getDeliveriesForTournee ERR: " + e.getMessage());
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
            Log.e(TAG, "replaceAllNotes ERR: " + e.getMessage());
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
            Log.e(TAG, "getActiveNotes ERR: " + e.getMessage());
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
                Log.e(TAG, "addColumnIfMissing ERR: " + e.getMessage());
            }
        }
    }
}