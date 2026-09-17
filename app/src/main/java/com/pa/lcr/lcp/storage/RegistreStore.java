package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * RegistreStore — miroir local de la table Dataverse filgo_registrecompteur.
 *
 * Clé métier : numero_de_serie (même valeur que filgo_numerodeserie côté
 * Dataverse) — pas le GUID Dataverse, que l'app ne connaît qu'une fois la
 * fiche déjà présente là-bas.
 *
 * ✅ AJOUTÉ (17 sept 2026, demande Paul) — "on va mettre à jour la table
 * locale ou ajouter une nouvelle fiche dans registre" lors d'une validation
 * de connexion réussie (INIT 1/7 REGISTRE). upsertOnConnexion() ne touche
 * QUE les champs que l'app connaît réellement au moment de la connexion
 * (serial, node, adresse/nom Bluetooth, ip/port si TCP) — jamais les champs
 * administratifs (calibration, scellé, coefficient) qui appartiennent à
 * Jacques côté Dataverse et que l'app n'a aucun moyen de connaître ici.
 * Marque sync_status=PENDING seulement si une valeur a réellement changé,
 * pour ne pas redéclencher une synchronisation Dataverse à chaque simple
 * reconnexion sans rien de nouveau.
 */
public class RegistreStore {

    private static final String TAG = "RegistreStore";

    public static final String TABLE                = "registre";
    public static final String COL_SERIAL            = "numero_de_serie";
    public static final String COL_DATAVERSE_ID       = "registrecompteur_id";
    public static final String COL_NOM               = "nom";
    public static final String COL_NUD               = "nud";
    public static final String COL_BT_ADDR           = "adresse_bluetooth";
    public static final String COL_BT_NOM            = "nom_bluetooth";
    public static final String COL_IP_ADDR           = "adresse_ip";
    public static final String COL_IP_PORT           = "port_ip";
    public static final String COL_TRANSPORT_PREFERE = "transport_prefere";
    public static final String COL_TYPE_APPAREIL     = "type_dappareil";
    public static final String COL_VITESSE_COMM      = "vitesse_de_communication";
    public static final String COL_COEFF_BRUT_NET    = "coefficient_brut_net";
    public static final String COL_DATE_CALIBRATION  = "date_de_calibration";
    public static final String COL_DATE_DESCELLEMENT = "date_de_descellement";
    public static final String COL_NUMERO_SCELLE     = "numero_de_scelle";
    public static final String COL_IDENTIFIANT_LC3   = "identifiant_unite_lc3";
    public static final String COL_FIRMWARE          = "firmware";
    public static final String COL_DATAVERSE_VERSION = "dataverse_version";
    public static final String COL_SYNC_STATUS       = "sync_status";
    public static final String COL_UPDATED           = "updated_at";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED  = "SYNCED";

    // Valeurs filgo_transportprefere observées dans Dataverse (optionset) —
    // à confirmer avec Jacques si d'autres valeurs existent.
    public static final int TRANSPORT_BT  = 365290000;
    public static final int TRANSPORT_TCP = 365290001;

    private final DeliveryDb helper;

    public RegistreStore(Context context) {
        this.helper = new DeliveryDb(context);
    }

    /**
     * Row telle que lue depuis la table locale.
     */
    public static final class Row {
        public final String serialId;
        public final String dataverseId;
        public final String nom;
        public final int    nud;
        public final String btAddr;
        public final String btNom;
        public final String ipAddr;
        public final Integer ipPort;
        public final Integer transportPrefere;
        public final Integer typeAppareil;
        public final Integer vitesseComm;
        public final Double  coeffBrutNet;
        public final String dateCalibration;
        public final String dateDescellement;
        public final String numeroScelle;
        public final String identifiantLc3;
        public final String firmware;
        public final String dataverseVersion;
        public final String syncStatus;
        public final long   updatedAt;

        Row(Cursor c) {
            serialId         = getStr(c, COL_SERIAL);
            dataverseId       = getStr(c, COL_DATAVERSE_ID);
            nom              = getStr(c, COL_NOM);
            nud              = c.getInt(c.getColumnIndexOrThrow(COL_NUD));
            btAddr           = getStr(c, COL_BT_ADDR);
            btNom            = getStr(c, COL_BT_NOM);
            ipAddr           = getStr(c, COL_IP_ADDR);
            ipPort           = getIntOrNull(c, COL_IP_PORT);
            transportPrefere = getIntOrNull(c, COL_TRANSPORT_PREFERE);
            typeAppareil     = getIntOrNull(c, COL_TYPE_APPAREIL);
            vitesseComm      = getIntOrNull(c, COL_VITESSE_COMM);
            coeffBrutNet     = getDoubleOrNull(c, COL_COEFF_BRUT_NET);
            dateCalibration  = getStr(c, COL_DATE_CALIBRATION);
            dateDescellement = getStr(c, COL_DATE_DESCELLEMENT);
            numeroScelle     = getStr(c, COL_NUMERO_SCELLE);
            identifiantLc3   = getStr(c, COL_IDENTIFIANT_LC3);
            firmware         = getStr(c, COL_FIRMWARE);
            dataverseVersion = getStr(c, COL_DATAVERSE_VERSION);
            syncStatus       = getStr(c, COL_SYNC_STATUS);
            updatedAt        = c.getLong(c.getColumnIndexOrThrow(COL_UPDATED));
        }

        private static String getStr(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return (i >= 0 && !c.isNull(i)) ? c.getString(i) : null;
        }
        private static Integer getIntOrNull(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return (i >= 0 && !c.isNull(i)) ? c.getInt(i) : null;
        }
        private static Double getDoubleOrNull(Cursor c, String col) {
            int i = c.getColumnIndex(col);
            return (i >= 0 && !c.isNull(i)) ? c.getDouble(i) : null;
        }
    }

    public Row getBySerial(String serialId) {
        if (serialId == null || serialId.trim().isEmpty()) return null;
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, COL_SERIAL + "=?",
                new String[]{ serialId }, null, null, null, "1")) {
            if (c.moveToFirst()) return new Row(c);
            return null;
        }
    }

    /**
     * Met à jour la fiche existante ou en crée une nouvelle, à partir des
     * seules informations connues lors d'une VALIDATION MANUELLE réussie
     * (Configurer → Démarrer la validation) — plus jamais automatiquement
     * à chaque connexion normale (retiré du flux INIT 1/7, demande Paul
     * du 17 sept 2026 : "je voulais que la validation soit faite
     * uniquement quand je demande dans configure, démarrer la
     * validation"). Ne touche jamais les champs administratifs
     * (calibration, scellé, coefficient, identifiant LC3) — laissés
     * null/inchangés s'ils n'existaient pas déjà, jamais écrasés par une
     * valeur vide.
     *
     * @return true si une fiche a été créée OU modifiée (donc à synchroniser),
     *         false si la fiche existait déjà avec exactement les mêmes valeurs.
     */
    public boolean upsertDepuisValidation(String serialId, int nud, String btAddr, String btNom,
                                           String ipAddr, Integer ipPort, Integer transportPrefere,
                                           String firmware) {
        if (serialId == null || serialId.trim().isEmpty()) {
            Log.w(TAG, "upsertDepuisValidation: numero_de_serie vide — ignoré");
            return false;
        }

        Row existing = getBySerial(serialId);
        boolean changed = existing == null
            || existing.nud != nud
            || !eq(existing.btAddr, btAddr)
            || !eq(existing.btNom, btNom)
            || !eq(existing.ipAddr, ipAddr)
            || !eqInt(existing.ipPort, ipPort)
            || !eqInt(existing.transportPrefere, transportPrefere)
            || !eq(existing.firmware, firmware);

        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_SERIAL, serialId);
        cv.put(COL_NUD, nud);
        if (btAddr != null) cv.put(COL_BT_ADDR, btAddr);
        if (btNom  != null) cv.put(COL_BT_NOM,  btNom);
        if (ipAddr != null) cv.put(COL_IP_ADDR, ipAddr);
        if (ipPort != null) cv.put(COL_IP_PORT, ipPort);
        if (transportPrefere != null) cv.put(COL_TRANSPORT_PREFERE, transportPrefere);
        if (firmware != null && !firmware.trim().isEmpty() && !"?".equals(firmware.trim())) {
            cv.put(COL_FIRMWARE, firmware);
        }
        cv.put(COL_UPDATED, System.currentTimeMillis());
        if (changed) cv.put(COL_SYNC_STATUS, SYNC_PENDING);

        long rows = db.update(TABLE, cv, COL_SERIAL + "=?", new String[]{ serialId });
        if (rows == 0) {
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            Log.i(TAG, "upsertDepuisValidation: nouvelle fiche registre — serial=" + serialId + " nud=" + nud);
        } else if (changed) {
            Log.i(TAG, "upsertDepuisValidation: fiche registre mise à jour — serial=" + serialId + " nud=" + nud);
        }
        return changed;
    }

    private static boolean eq(String a, String b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
    private static boolean eqInt(Integer a, Integer b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    // ── Sync Dataverse ──────────────────────────────────────────

    /** Toutes les fiches PENDING (créées ou modifiées localement, pas encore confirmées Dataverse). */
    public java.util.List<Row> getPending() {
        java.util.List<Row> out = new java.util.ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, COL_SYNC_STATUS + "=?",
                new String[]{ SYNC_PENDING }, null, null, null)) {
            while (c.moveToNext()) out.add(new Row(c));
        }
        return out;
    }

    /** Après un push Dataverse réussi (POST ou PATCH) : mémorise le GUID et le versionnumber lus, marque SYNCED. */
    public void markSynced(String serialId, String dataverseId, String dataverseVersion) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        if (dataverseId != null) cv.put(COL_DATAVERSE_ID, dataverseId);
        if (dataverseVersion != null) cv.put(COL_DATAVERSE_VERSION, dataverseVersion);
        cv.put(COL_SYNC_STATUS, SYNC_SYNCED);
        db.update(TABLE, cv, COL_SERIAL + "=?", new String[]{ serialId });
    }
}
