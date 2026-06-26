package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * RegisterProductDb — Table de descriptions des produits par registre LCR-II.
 *
 * Table: register_products
 *   serial_id   TEXT    — numéro de série du registre (ex: "16466294")
 *   note_idx    INTEGER — index du produit 1..16 (1-based, aligné sur spnProduct)
 *   description TEXT    — description lue depuis le registre (ex: "Diesel #2")
 *   updated_at  INTEGER — timestamp ms de la dernière lecture
 *
 * PK: (serial_id, note_idx) → UPSERT via INSERT OR REPLACE
 *
 * Usage:
 *   RegisterProductDb db = new RegisterProductDb(ctx);
 *   db.upsert("16466294", 1, "Diesel #2");
 *   String desc = db.getDescription("16466294", 1); // → "Diesel #2"
 *   List<Row> all = db.getAll("16466294");           // → tous les produits
 *   db.close();
 */
public class RegisterProductDb extends SQLiteOpenHelper {

    private static final String TAG = "RegisterProductDb";

    private static final String DB_NAME    = "register_products.db";
    private static final int    DB_VERSION = 1;

    public static final String TABLE         = "register_products";
    public static final String COL_SERIAL    = "serial_id";
    public static final String COL_NOTE_IDX  = "note_idx";
    public static final String COL_DESC      = "description";
    public static final String COL_UPDATED   = "updated_at";

    // ===================== ROW MODEL =====================
    public static final class Row {
        public final String serialId;
        public final int    noteIdx;     // 1-based (produit 1..16)
        public final String description;
        public final long   updatedAt;

        public Row(String serialId, int noteIdx, String description, long updatedAt) {
            this.serialId    = serialId;
            this.noteIdx     = noteIdx;
            this.description = description;
            this.updatedAt   = updatedAt;
        }

        /** Libellé pour le spinner : "1 - Diesel #2" */
        public String toSpinnerLabel() {
            if (description == null || description.isEmpty())
                return String.valueOf(noteIdx);
            return noteIdx + " - " + description;
        }

        /** Vrai si la description contient "propane" (insensible à la casse). */
        public boolean isPropane() {
            return description != null
                && description.toLowerCase(java.util.Locale.ROOT).contains("propane");
        }
    }

    // ===================== CTOR =====================
    public RegisterProductDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    // ===================== LIFECYCLE =====================
    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
            + COL_SERIAL   + " TEXT    NOT NULL,"
            + COL_NOTE_IDX + " INTEGER NOT NULL,"
            + COL_DESC     + " TEXT    NOT NULL DEFAULT '',"
            + COL_UPDATED  + " INTEGER NOT NULL DEFAULT 0,"
            + "PRIMARY KEY (" + COL_SERIAL + ", " + COL_NOTE_IDX + ")"
            + ")"
        );
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 → pas de migration pour l'instant
    }

    // ===================== ÉCRITURE =====================

    /**
     * Insère ou met à jour la description d'un produit pour ce registre.
     *
     * @param serialId    numéro de série du registre (String)
     * @param noteIdx     index 1-based (1..16, aligné sur spnProduct)
     * @param description description lue depuis le registre
     */
    public void upsert(String serialId, int noteIdx, String description) {
        if (serialId == null || serialId.isEmpty()) return;
        ContentValues cv = new ContentValues();
        cv.put(COL_SERIAL,   serialId);
        cv.put(COL_NOTE_IDX, noteIdx);
        cv.put(COL_DESC,     description != null ? description : "");
        cv.put(COL_UPDATED,  System.currentTimeMillis());
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.insertOrThrow(TABLE, null, cv); // INSERT OR REPLACE via conflict algorithm
        } catch (android.database.sqlite.SQLiteConstraintException e) {
            // PK existe → UPDATE
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.update(TABLE, cv,
                    COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                    new String[]{serialId, String.valueOf(noteIdx)});
            } catch (Exception ex) {
                Log.e(TAG, "upsert ERR: " + ex.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "upsert ERR: " + e.getMessage());
        }
    }

    /**
     * Upsert batch — plus efficace pour les 16 produits d'un coup.
     */
    public void upsertAll(String serialId, java.util.Map<Integer, String> indexToName) {
        if (serialId == null || serialId.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (java.util.Map.Entry<Integer, String> e : indexToName.entrySet()) {
                int noteIdx = e.getKey() + 1; // convertir 0-based → 1-based
                ContentValues cv = new ContentValues();
                cv.put(COL_SERIAL,   serialId);
                cv.put(COL_NOTE_IDX, noteIdx);
                cv.put(COL_DESC,     e.getValue() != null ? e.getValue() : "");
                cv.put(COL_UPDATED,  System.currentTimeMillis());
                db.insertWithOnConflict(TABLE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
            Log.i(TAG, "upsertAll: " + indexToName.size() + " produits pour serial=" + serialId);
        } catch (Exception ex) {
            Log.e(TAG, "upsertAll ERR: " + ex.getMessage());
        } finally {
            db.endTransaction();
        }
    }

    // ===================== LECTURE =====================

    /**
     * Retourne la description d'un produit spécifique (noteIdx 1-based).
     * Retourne null si non trouvé.
     */
    public String getDescription(String serialId, int noteIdx) {
        if (serialId == null) return null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE,
                    new String[]{COL_DESC},
                    COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                    new String[]{serialId, String.valueOf(noteIdx)},
                    null, null, null)) {
                if (c != null && c.moveToFirst())
                    return c.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "getDescription ERR: " + e.getMessage());
        }
        return null;
    }

    /**
     * Retourne tous les produits pour un registre, triés par noteIdx ASC.
     * Liste vide si aucun enregistrement.
     */
    public List<Row> getAll(String serialId) {
        List<Row> rows = new ArrayList<>();
        if (serialId == null) return rows;
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SERIAL + "=?",
                    new String[]{serialId},
                    null, null, COL_NOTE_IDX + " ASC")) {
                if (c != null) {
                    int iNote = c.getColumnIndex(COL_NOTE_IDX);
                    int iDesc = c.getColumnIndex(COL_DESC);
                    int iUpd  = c.getColumnIndex(COL_UPDATED);
                    while (c.moveToNext()) {
                        rows.add(new Row(
                            serialId,
                            c.getInt(iNote),
                            c.getString(iDesc),
                            c.getLong(iUpd)
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
     * Vrai si ce registre a déjà des produits enregistrés.
     */
    public boolean hasProducts(String serialId) {
        if (serialId == null) return false;
        try {
            SQLiteDatabase db = getReadableDatabase();
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

    /**
     * Supprime tous les produits d'un registre (ex: avant re-scan).
     */
    public void deleteAll(String serialId) {
        if (serialId == null) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.delete(TABLE, COL_SERIAL + "=?", new String[]{serialId});
        } catch (Exception e) {
            Log.e(TAG, "deleteAll ERR: " + e.getMessage());
        }
    }

    /**
     * Retourne le noteIdx (1-based) du premier produit contenant "propane"
     * dans sa description, ou -1 si aucun.
     * Cherche dans l'ordre noteIdx ASC.
     */
    public int findPropaneNoteIdx(String serialId) {
        List<Row> rows = getAll(serialId);
        for (Row r : rows) {
            if (r.isPropane()) return r.noteIdx;
        }
        return -1;
    }
}