package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DAO pour la table register_products dans DeliveryDb (v9).
 *
 * Stocke les descriptions des produits lus depuis le registre LCR-II
 * via opScanAllProductNames() (Field #0 + Field #11).
 *
 * PK : (serial_id, note_idx) — UPSERT via INSERT OR REPLACE.
 * sync_status : PENDING → à synchroniser vers Dataverse filgo_register_product
 *               SYNCED  → déjà synchronisé
 */
public class RegisterProductStore {

    private static final String TAG   = "RegisterProductStore";
    public  static final String TABLE = "register_products";

    public  static final String COL_SERIAL      = "serial_id";
    public  static final String COL_NOTE_IDX    = "note_idx";
    public  static final String COL_DESC        = "description";
    public  static final String COL_UPDATED     = "updated_at";
    public  static final String COL_SYNC_STATUS = "sync_status";

    public  static final String SYNC_PENDING = "PENDING";
    public  static final String SYNC_SYNCED  = "SYNCED";

    private final DeliveryDb helper;

    public RegisterProductStore(Context context) {
        this.helper = new DeliveryDb(context);
    }

    // ===================== ROW MODEL =====================

    public static final class Row {
        public final String serialId;
        public final int    noteIdx;       // 1-based (produit 1..16)
        public final String description;
        public final long   updatedAt;
        public final String syncStatus;

        public Row(String serialId, int noteIdx, String description,
                   long updatedAt, String syncStatus) {
            this.serialId    = serialId;
            this.noteIdx     = noteIdx;
            this.description = description != null ? description : "";
            this.updatedAt   = updatedAt;
            this.syncStatus  = syncStatus != null ? syncStatus : SYNC_PENDING;
        }

        /** Label pour le spinner : "1 - PROPANE" ou "1" si pas de description. */
        public String toSpinnerLabel() {
            if (description.isEmpty()) return String.valueOf(noteIdx);
            return noteIdx + " - " + description;
        }

        /** Vrai si la description contient "propane" (insensible à la casse). */
        public boolean isPropane() {
            return description.toLowerCase(java.util.Locale.ROOT).contains("propane");
        }
    }

    // ===================== ÉCRITURE =====================

    /**
     * Upsert batch — les 16 produits d'un coup depuis opScanAllProductNames().
     * indexToName : Map 0-based index → description (comme retourné par LcpLink).
     * Convertit en 1-based noteIdx pour la table.
     */
    public void upsertAll(String serialId, Map<Integer, String> indexToName) {
        if (serialId == null || serialId.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<Integer, String> e : indexToName.entrySet()) {
                int noteIdx = e.getKey() + 1; // 0-based → 1-based
                ContentValues cv = new ContentValues();
                cv.put(COL_SERIAL,      serialId);
                cv.put(COL_NOTE_IDX,    noteIdx);
                cv.put(COL_DESC,        e.getValue() != null ? e.getValue() : "");
                cv.put(COL_UPDATED,     now);
                cv.put(COL_SYNC_STATUS, SYNC_PENDING);
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            Log.i(TAG, "upsertAll: " + indexToName.size() + " produits — serial=" + serialId);
        } catch (Exception e) {
            Log.e(TAG, "upsertAll ERR: " + e.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    // ===================== LECTURE =====================

    /**
     * Description d'un produit spécifique (noteIdx 1-based).
     * Retourne null si non trouvé.
     */
    public String getDescription(String serialId, int noteIdx) {
        if (serialId == null) return null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE,
                    new String[]{COL_DESC},
                    COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                    new String[]{serialId, String.valueOf(noteIdx)},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "getDescription ERR: " + e.getMessage());
        }
        return null;
    }

    /**
     * Tous les produits pour un registre, triés par noteIdx ASC.
     */
    public List<Row> getAll(String serialId) {
        List<Row> rows = new ArrayList<>();
        if (serialId == null) return rows;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SERIAL + "=?",
                    new String[]{serialId},
                    null, null, COL_NOTE_IDX + " ASC")) {
                if (c != null) {
                    int iNote = c.getColumnIndexOrThrow(COL_NOTE_IDX);
                    int iDesc = c.getColumnIndexOrThrow(COL_DESC);
                    int iUpd  = c.getColumnIndexOrThrow(COL_UPDATED);
                    int iSync = c.getColumnIndexOrThrow(COL_SYNC_STATUS);
                    while (c.moveToNext()) {
                        rows.add(new Row(
                            serialId,
                            c.getInt(iNote),
                            c.getString(iDesc),
                            c.getLong(iUpd),
                            c.getString(iSync)
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getAll ERR: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Tous les produits en attente de sync vers Dataverse (sync_status = PENDING).
     */
    public List<Row> getPending() {
        List<Row> rows = new ArrayList<>();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SYNC_STATUS + "=?",
                    new String[]{SYNC_PENDING},
                    null, null, COL_SERIAL + ", " + COL_NOTE_IDX + " ASC")) {
                if (c != null) {
                    int iSerial = c.getColumnIndexOrThrow(COL_SERIAL);
                    int iNote   = c.getColumnIndexOrThrow(COL_NOTE_IDX);
                    int iDesc   = c.getColumnIndexOrThrow(COL_DESC);
                    int iUpd    = c.getColumnIndexOrThrow(COL_UPDATED);
                    int iSync   = c.getColumnIndexOrThrow(COL_SYNC_STATUS);
                    while (c.moveToNext()) {
                        rows.add(new Row(
                            c.getString(iSerial),
                            c.getInt(iNote),
                            c.getString(iDesc),
                            c.getLong(iUpd),
                            c.getString(iSync)
                        ));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getPending ERR: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Marque un produit comme synchronisé.
     */
    public void markSynced(String serialId, int noteIdx) {
        if (serialId == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_SYNC_STATUS, SYNC_SYNCED);
            helper.getWritableDatabase().update(TABLE, cv,
                COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                new String[]{serialId, String.valueOf(noteIdx)});
        } catch (Exception e) {
            Log.e(TAG, "markSynced ERR: " + e.getMessage());
        }
    }

    /**
     * noteIdx (1-based) du premier produit contenant "propane", ou -1 si aucun.
     */
    public int findPropaneNoteIdx(String serialId) {
        List<Row> rows = getAll(serialId);
        for (Row r : rows) {
            if (r.isPropane()) return r.noteIdx;
        }
        return -1;
    }

    /**
     * Vrai si ce registre a déjà des produits enregistrés.
     */
    public boolean hasProducts(String serialId) {
        if (serialId == null) return false;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE " + COL_SERIAL + "=?",
                    new String[]{serialId})) {
                if (c != null && c.moveToFirst()) return c.getInt(0) > 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "hasProducts ERR: " + e.getMessage());
        }
        return false;
    }

    public void close() {
        try { helper.close(); } catch (Exception ignored) {}
    }
}