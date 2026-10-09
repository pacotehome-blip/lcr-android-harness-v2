package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * ActiveDeliveryStore — persistance de la livraison courante.
 *
 * Une seule ligne (id=1) dans la table active_delivery.
 * Sauvegardée au démarrage de pollJobUntilDone.
 * Effacée à onDeliveryEnded.
 *
 * Permet de :
 * - Reprendre le poll si l'APK est relancé pendant une livraison active
 * - Détecter qu'une livraison est en cours avant d'en démarrer une nouvelle
 * - Appliquer le bon net/gross au bon WO/jobId à la fin
 *
 * Chemin : app/src/main/java/com/pa/lcr/lcp/storage/ActiveDeliveryStore.java
 */
public class ActiveDeliveryStore {

    private static final String TAG   = "ActiveDeliveryStore";
    private static final int    ROW_ID = 1;

    private final DeliveryDb dbHelper;

    public ActiveDeliveryStore(Context context) {
        this.dbHelper = new DeliveryDb(context);
    }

    // =========================================================
    // Data class
    // =========================================================

    public static class ActiveDelivery {
        public String woNum;
        public String woIdGuid;
        public String jobId;
        public String mac;
        public int    node;
        public String serialId;
        public int    produit;
        public double preset;
        public String status;   // PENDING / STARTED / DONE
        public long   tsStartedMs;
        // GUID Field Service reçus par le deep link (stopid / productid) —
        // simplement transportés avec la livraison, jamais utilisés pour décider.
        public String stopid;
        public String productid;

        @Override
        public String toString() {
            return "ActiveDelivery{woNum=" + woNum + " jobId=" + jobId
                + " mac=" + mac + " node=" + node + " serial=" + serialId
                + " produit=" + produit + " preset=" + preset + " status=" + status + "}";
        }
    }

    // =========================================================
    // Écrire / mettre à jour la livraison courante
    // =========================================================

    // Compatibilité — sans stopid/productid : PRÉSERVE ceux déjà enregistrés pour le même WO
    // (CONFLICT_REPLACE écrase toute la ligne — sans ça, un save() STARTED ou
    // un changement de transport effacerait les GUID reçus par le deep link).
    public void save(String woNum, String woIdGuid, String jobId,
                     String mac, int node, String serialId,
                     int produit, double preset, String status) {
        String stopidExistant = "";
        String productidExistant = "";
        ActiveDelivery existant = load();
        // Même WO seulement : jamais de GUID d'un autre bon de travail.
        if (existant != null && woNum != null && woNum.equals(existant.woNum)) {
            if (existant.stopid != null) stopidExistant = existant.stopid;
            if (existant.productid != null) productidExistant = existant.productid;
        }
        save(woNum, woIdGuid, jobId, mac, node, serialId, produit, preset, status,
             stopidExistant, productidExistant);
    }

    public void save(String woNum, String woIdGuid, String jobId,
                     String mac, int node, String serialId,
                     int produit, double preset, String status,
                     String stopid, String productid) {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("id",            ROW_ID);
            v.put("wo_num",        woNum      != null ? woNum      : "");
            v.put("wo_id_guid",    woIdGuid   != null ? woIdGuid   : "");
            v.put("job_id",        jobId      != null ? jobId      : "");
            v.put("mac",           mac        != null ? mac        : "");
            v.put("node",          node);
            v.put("serial_id",     serialId   != null ? serialId   : "");
            v.put("produit",       produit);
            v.put("preset",        preset);
            v.put("status",        status     != null ? status     : "PENDING");
            v.put("ts_started_ms", System.currentTimeMillis());
            v.put("stopid",        stopid     != null ? stopid     : "");
            v.put("productid",     productid  != null ? productid  : "");
            db.insertWithOnConflict("active_delivery", null, v,
                    SQLiteDatabase.CONFLICT_REPLACE);
            Log.i(TAG, "save: woNum=" + woNum + " jobId=" + jobId + " status=" + status);
        } catch (Exception e) {
            Log.e(TAG, "save ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "ActiveDeliveryStore.save", e); } catch (Exception ignored) {}
        }
    }

    // Compatibilité — save sans produit/preset (status=PENDING)
    public void save(String woNum, String woIdGuid, String jobId,
                     String mac, int node, String serialId) {
        save(woNum, woIdGuid, jobId, mac, node, serialId, 1, 0.0, "PENDING");
    }

    public void updateStatus(String status) {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("status", status);
            db.update("active_delivery", v, "id=?",
                new String[]{String.valueOf(ROW_ID)});
            Log.i(TAG, "updateStatus: " + status);
        } catch (Exception e) {
            Log.e(TAG, "updateStatus ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "ActiveDeliveryStore.updateStatus", e); } catch (Exception ignored) {}
        }
    }

    // =========================================================
    // Lire la livraison courante
    // =========================================================

    public ActiveDelivery load() {
        try {
            SQLiteDatabase db = dbHelper.getReadableDatabase();
            try (Cursor c = db.query("active_delivery", null,
                    "id=?", new String[]{String.valueOf(ROW_ID)},
                    null, null, null)) {
                if (c.moveToFirst()) {
                    ActiveDelivery d = new ActiveDelivery();
                    d.woNum       = c.getString(c.getColumnIndexOrThrow("wo_num"));
                    d.woIdGuid    = c.getString(c.getColumnIndexOrThrow("wo_id_guid"));
                    d.jobId       = c.getString(c.getColumnIndexOrThrow("job_id"));
                    d.mac         = c.getString(c.getColumnIndexOrThrow("mac"));
                    d.node        = c.getInt(c.getColumnIndexOrThrow("node"));
                    d.serialId    = c.getString(c.getColumnIndexOrThrow("serial_id"));
                    d.produit     = c.getInt(c.getColumnIndexOrThrow("produit"));
                    d.preset      = c.getDouble(c.getColumnIndexOrThrow("preset"));
                    d.status      = c.getString(c.getColumnIndexOrThrow("status"));
                    d.tsStartedMs = c.getLong(c.getColumnIndexOrThrow("ts_started_ms"));
                    // stopid / productid : lecture tolérante (colonnes ajoutées en v28)
                    int iStopid = c.getColumnIndex("stopid");
                    int iProductid = c.getColumnIndex("productid");
                    d.stopid    = (iStopid >= 0 && !c.isNull(iStopid)) ? c.getString(iStopid) : "";
                    d.productid = (iProductid >= 0 && !c.isNull(iProductid)) ? c.getString(iProductid) : "";
                    Log.i(TAG, "load: " + d);
                    return d;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "ActiveDeliveryStore.load", e); } catch (Exception ignored) {}
        }
        return null;
    }

    // =========================================================
    // Effacer la livraison courante (appelé à onDeliveryEnded)
    // =========================================================

    public void clear() {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            int rows = db.delete("active_delivery", "id=?",
                    new String[]{String.valueOf(ROW_ID)});
            Log.i(TAG, "clear: " + rows + " row(s) deleted");
        } catch (Exception e) {
            Log.e(TAG, "clear ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "ActiveDeliveryStore.clear", e); } catch (Exception ignored) {}
        }
    }

    // =========================================================
    // Vérifier si une livraison est en cours
    // =========================================================

    public boolean hasActive() {
        return load() != null;
    }

    /**
     * Vérifie si la livraison en cours correspond au WO demandé.
     * Si oui, retourner l'ActiveDelivery pour reprendre le poll.
     * Si non (WO différent), retourner null — nouvelle livraison.
     */
    public ActiveDelivery getIfSameWo(String woNum) {
        ActiveDelivery d = load();
        if (d == null) return null;
        if (woNum != null && woNum.equals(d.woNum)) return d;
        return null;
    }
}