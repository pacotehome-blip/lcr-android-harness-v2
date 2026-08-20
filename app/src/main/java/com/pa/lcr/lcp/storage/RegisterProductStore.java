package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.pa.lcr.lcp.LcpLink;

import java.util.ArrayList;
import java.util.List;

public class RegisterProductStore {

    private static final String TAG = "RegisterProductStore";

    public static final String TABLE           = "register_products";
    public static final String COL_SERIAL      = "serial_id";
    public static final String COL_NOTE_IDX    = "note_idx";
    public static final String COL_DESC        = "description";
    public static final String COL_LCR_NODE    = "lcr_node";
    public static final String COL_IS_PROPANE  = "is_propane";
    public static final String COL_UPDATED     = "updated_at";
    public static final String COL_SYNC_STATUS = "sync_status";
    // ✅ AJOUTÉ (20 août 2026, demande Paul) — voir DeliveryDb v25.
    public static final String COL_PRODUCT_CODE = "product_code";
    public static final String COL_PRODUCT_TYPE = "product_type";
    public static final String COL_FSM_CODE     = "fsm_code";        // réservé pour le futur, jamais peuplé pour l'instant
    public static final String COL_FSM_DESC     = "fsm_description"; // réservé pour le futur, jamais peuplé pour l'instant

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SYNCED  = "SYNCED";

    private final DeliveryDb helper;

    public RegisterProductStore(Context context) {
        this.helper = new DeliveryDb(context);
    }

    // ── Row ──────────────────────────────────────────────────

    public static final class Row {
        public final String  serialId;
        public final int     noteIdx;
        public final String  description;
        public final int     lcrNode;
        public final boolean isPropane;
        public final long    updatedAt;
        public final String  syncStatus;
        // ✅ AJOUTÉ (20 août 2026, demande Paul)
        public final String  productCode;
        public final int     productType;   // -1 = absent, sinon 0-7 (List 2 du PDF)
        public final String  fsmCode;        // réservé futur — toujours null pour l'instant
        public final String  fsmDescription; // réservé futur — toujours null pour l'instant

        public Row(String serialId, int noteIdx, String description,
                   int lcrNode, boolean isPropane, long updatedAt, String syncStatus,
                   String productCode, int productType, String fsmCode, String fsmDescription) {
            this.serialId    = serialId;
            this.noteIdx     = noteIdx;
            this.description = description != null ? description : "";
            this.lcrNode     = lcrNode;
            this.isPropane   = isPropane;
            this.updatedAt   = updatedAt;
            this.syncStatus  = syncStatus != null ? syncStatus : SYNC_PENDING;
            this.productCode = productCode != null ? productCode : "";
            this.productType = productType;
            this.fsmCode        = fsmCode;
            this.fsmDescription = fsmDescription;
        }

        public String productTypeLabel() {
            return com.pa.lcr.lcp.LcpLink.decodeProductType(productType);
        }

        public String toSpinnerLabel() {
            if (description.isEmpty()) return String.valueOf(noteIdx);
            return noteIdx + " - " + description;
        }

        public boolean matchesName(String name) {
            if (name == null || description.isEmpty()) return false;
            String a = norm(description);
            String b = norm(name);
            return a.equals(b) || a.contains(b) || b.contains(a);
        }

        private static String norm(String s) {
            return s.trim().toLowerCase(java.util.Locale.ROOT)
                     .replace("-", " ").replace("_", " ")
                     .replaceAll("\\s+", " ");
        }
    }

    // ── Écriture ─────────────────────────────────────────────

    public void upsertAll(String serialId, int lcrNode,
                          List<LcpLink.ProductScanResult> results) {
        if (serialId == null || serialId.isEmpty() || results == null) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            for (LcpLink.ProductScanResult r : results) {
                ContentValues cv = new ContentValues();
                cv.put(COL_SERIAL,      serialId);
                cv.put(COL_NOTE_IDX,    r.noteIdx);
                cv.put(COL_DESC,        r.description);
                cv.put(COL_LCR_NODE,    lcrNode);
                cv.put(COL_IS_PROPANE,  r.isPropane ? 1 : 0);
                cv.put(COL_UPDATED,     now);
                cv.put(COL_SYNC_STATUS, SYNC_PENDING);
                // ✅ AJOUTÉ (20 août 2026) — fsm_code/fsm_description ne
                // sont volontairement PAS écrits ici (null par défaut) —
                // réservés pour le futur, jamais peuplés par le scan.
                cv.put(COL_PRODUCT_CODE, r.productCode != null ? r.productCode : "");
                cv.put(COL_PRODUCT_TYPE, r.productType);
                db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            // ✅ FIX CRITIQUE (11 août 2026, demande Paul — "si ça doit être
            // dans le log Support, qu'est-ce que t'attends pour le mettre")
            // — cette exception était avalée en silence (Log.e brut,
            // invisible dans Support/LogBus) — un vrai échec de sauvegarde
            // du scan produits aurait pu passer complètement inaperçu.
            // Maintenant visible dans Support, avec le node concerné.
            Log.e(TAG, "upsertAll ERR: " + e.getMessage());
            try {
                com.pa.lcr.lcp.log.LogBus.err(lcrNode, "RegisterProductStore.upsertAll", e);
            } catch (Exception ignored) {}
        } finally {
            db.endTransaction();
        }
    }

    // ── Lecture ───────────────────────────────────────────────

    /** Tous les produits filtrés par serial_id + lcr_node. */
    public List<Row> getAll(String serialId, int lcrNode) {
        List<Row> rows = new ArrayList<>();
        if (serialId == null) return rows;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SERIAL + "=? AND " + COL_LCR_NODE + "=?",
                    new String[]{serialId, String.valueOf(lcrNode)},
                    null, null, COL_NOTE_IDX + " ASC")) {
                while (c != null && c.moveToNext()) rows.add(map(c, serialId));
            }
        } catch (Exception e) { Log.e(TAG, "getAll ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(lcrNode, "RegisterProductStore.getAll", e); } catch (Exception ignored) {} }
        return rows;
    }

    /** Fallback sans node. */
    public List<Row> getAll(String serialId) {
        List<Row> rows = new ArrayList<>();
        if (serialId == null) return rows;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SERIAL + "=?", new String[]{serialId},
                    null, null, COL_NOTE_IDX + " ASC")) {
                while (c != null && c.moveToNext()) rows.add(map(c, serialId));
            }
        } catch (Exception e) { Log.e(TAG, "getAll ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.getAll(sansNode)", e); } catch (Exception ignored) {} }
        return rows;
    }

    public String getDescription(String serialId, int noteIdx) {
        if (serialId == null) return null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, new String[]{COL_DESC},
                    COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                    new String[]{serialId, String.valueOf(noteIdx)},
                    null, null, null)) {
                if (c != null && c.moveToFirst()) return c.getString(0);
            }
        } catch (Exception e) { Log.e(TAG, "getDescription ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.getDescription", e); } catch (Exception ignored) {} }
        return null;
    }

    public Row findByNoteIdx(String serialId, int noteIdx) {
        if (serialId == null) return null;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null,
                    COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                    new String[]{serialId, String.valueOf(noteIdx)},
                    null, null, null, "1")) {
                if (c != null && c.moveToFirst()) return map(c, serialId);
            }
        } catch (Exception e) { Log.e(TAG, "findByNoteIdx ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.findByNoteIdx", e); } catch (Exception ignored) {} }
        return null;
    }

    public Row findByName(String serialId, String name) {
        if (serialId == null || name == null || name.isEmpty()) return null;
        for (Row r : getAll(serialId)) {
            if (r.matchesName(name)) return r;
        }
        return null;
    }

    /**
     * Résout noteIdx ou name vers un Row.
     * "1" ou 1      → findByNoteIdx
     * "propane" etc → findByName (insensible casse)
     * Les deux      → vérifie cohérence
     */
    public Row resolveProduct(String serialId, Integer noteIdx, String name) {
        if (noteIdx != null && name != null && !name.isEmpty()) {
            Row r = findByNoteIdx(serialId, noteIdx);
            if (r == null || !r.matchesName(name)) return null;
            return r;
        }
        if (noteIdx != null) return findByNoteIdx(serialId, noteIdx);
        if (name != null && !name.isEmpty()) return findByName(serialId, name);
        return null;
    }

    public int findPropaneNoteIdx(String serialId) {
        int fallback = -1;
        for (Row r : getAll(serialId)) {
            if (!r.isPropane) continue;
            if (r.noteIdx <= 2) return r.noteIdx;
            if (fallback == -1) fallback = r.noteIdx;
        }
        return fallback;
    }

    public boolean hasProducts(String serialId) {
        if (serialId == null) return false;
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + TABLE
                    + " WHERE " + COL_SERIAL + "=?", new String[]{serialId})) {
                if (c != null && c.moveToFirst()) return c.getInt(0) > 0;
            }
        } catch (Exception e) { Log.e(TAG, "hasProducts ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.hasProducts", e); } catch (Exception ignored) {} }
        return false;
    }

    public List<Row> getPending() {
        List<Row> rows = new ArrayList<>();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            try (Cursor c = db.query(TABLE, null, COL_SYNC_STATUS + "=?",
                    new String[]{SYNC_PENDING}, null, null,
                    COL_SERIAL + ", " + COL_NOTE_IDX + " ASC")) {
                while (c != null && c.moveToNext())
                    rows.add(map(c, c.getString(c.getColumnIndexOrThrow(COL_SERIAL))));
            }
        } catch (Exception e) { Log.e(TAG, "getPending ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.getPending", e); } catch (Exception ignored) {} }
        return rows;
    }

    public void markSynced(String serialId, int noteIdx) {
        if (serialId == null) return;
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_SYNC_STATUS, SYNC_SYNCED);
            helper.getWritableDatabase().update(TABLE, cv,
                COL_SERIAL + "=? AND " + COL_NOTE_IDX + "=?",
                new String[]{serialId, String.valueOf(noteIdx)});
        } catch (Exception e) { Log.e(TAG, "markSynced ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "RegisterProductStore.markSynced", e); } catch (Exception ignored) {} }
    }

    public void close() { try { helper.close(); } catch (Exception ignored) {} }

    // ── Map ───────────────────────────────────────────────────

    private static Row map(Cursor c, String serialId) {
        // ✅ AJOUTÉ (20 août 2026) — lecture défensive des nouvelles
        // colonnes (getColumnIndex, pas getColumnIndexOrThrow) pour ne
        // jamais planter sur une base pas encore migrée en v25 — ne
        // devrait pas arriver (onUpgrade s'en charge), mais coût nul à
        // être prudent sur une PRIMARY KEY aussi fréquemment lue.
        int idxCode = c.getColumnIndex(COL_PRODUCT_CODE);
        int idxType = c.getColumnIndex(COL_PRODUCT_TYPE);
        int idxFsmCode = c.getColumnIndex(COL_FSM_CODE);
        int idxFsmDesc = c.getColumnIndex(COL_FSM_DESC);
        return new Row(
            serialId,
            c.getInt(c.getColumnIndexOrThrow(COL_NOTE_IDX)),
            c.getString(c.getColumnIndexOrThrow(COL_DESC)),
            c.getInt(c.getColumnIndexOrThrow(COL_LCR_NODE)),
            c.getInt(c.getColumnIndexOrThrow(COL_IS_PROPANE)) != 0,
            c.getLong(c.getColumnIndexOrThrow(COL_UPDATED)),
            c.getString(c.getColumnIndexOrThrow(COL_SYNC_STATUS)),
            idxCode >= 0 ? c.getString(idxCode) : "",
            idxType >= 0 ? c.getInt(idxType) : -1,
            idxFsmCode >= 0 ? c.getString(idxFsmCode) : null,
            idxFsmDesc >= 0 ? c.getString(idxFsmDesc) : null
        );
    }
}