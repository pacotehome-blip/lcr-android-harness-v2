package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copie locale des formulaires d'impression (table formulaire_impression de lcr_delivery.db), meme structure que
 * filgo_print_forms dans Dataverse. 30 oct 2026.
 *
 * Filgo-Registre lit Dataverse, met cette table a jour, puis envoie chaque formulaire au service d'impression
 * (route /print-form). service_sync dit ou on en est : PENDING (a envoyer), SENT, ERROR (a renvoyer).
 * Le PDF est dans la BD (BLOB) : il est donc dans l'export / la sauvegarde de lcr_delivery.db.
 * Les formulaires de repli (grand, petit, registre) sont embarques dans le service, pas ici.
 */
public class FormulaireImpressionStore {

    private static final String TABLE = "formulaire_impression";

    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SENT    = "SENT";
    public static final String SYNC_ERROR   = "ERROR";

    private final DeliveryDb helper;

    public FormulaireImpressionStore(Context ctx) {
        this.helper = new DeliveryDb(ctx);
    }

    public static final class Row {
        public String  code = "";
        public String  name = "";
        /** GRAND, PETIT ou REGISTRE. */
        public String  format = "";
        public String  version = "";
        public boolean active = true;
        public byte[]  pdf;                 // null pour REGISTRE
        public String  pdfSha256 = "";
        public String  manifest = "";
        public String  dataverseId = "";
        public String  dataverseModified = "";
        public String  serviceSync = SYNC_PENDING;
        public String  serviceMsg = "";

        static Row from(Cursor c) {
            Row r = new Row();
            r.code              = s(c, "code");
            r.name              = s(c, "name");
            r.format            = s(c, "format");
            r.version           = s(c, "version");
            r.active            = c.getInt(c.getColumnIndexOrThrow("active")) != 0;
            int ip = c.getColumnIndexOrThrow("pdf");
            r.pdf               = c.isNull(ip) ? null : c.getBlob(ip);
            r.pdfSha256         = s(c, "pdf_sha256");
            r.manifest          = s(c, "manifest");
            r.dataverseId       = s(c, "dataverse_id");
            r.dataverseModified = s(c, "dataverse_modified");
            r.serviceSync       = s(c, "service_sync");
            r.serviceMsg        = s(c, "service_msg");
            return r;
        }

        private static String s(Cursor c, String col) {
            int i = c.getColumnIndexOrThrow(col);
            return c.isNull(i) ? "" : c.getString(i);
        }
    }

    public static String keyOf(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
    }

    public Row get(String code) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, "code=?", new String[]{ keyOf(code) }, null, null, null, "1")) {
            return c.moveToFirst() ? Row.from(c) : null;
        }
    }

    public List<Row> listAll() {
        List<Row> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null, "code ASC")) {
            while (c.moveToNext()) out.add(Row.from(c));
        }
        return out;
    }

    /** Formulaires a envoyer au service : tous ceux qui ne sont pas deja SENT. */
    public List<Row> listToSend() {
        List<Row> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, "service_sync<>?", new String[]{ SYNC_SENT }, null, null, "code ASC")) {
            while (c.moveToNext()) out.add(Row.from(c));
        }
        return out;
    }

    /**
     * Enregistre un formulaire lu dans Dataverse. Si rien n'a change (meme modifiedon, meme version, meme PDF),
     * la ligne n'est pas touchee et reste SENT ; sinon elle repasse en PENDING.
     *
     * @return true si la ligne a change (a renvoyer au service)
     */
    public boolean upsertFromDataverse(Row r) {
        r.code = keyOf(r.code);
        Row old = get(r.code);
        boolean same = old != null
                && old.dataverseModified.equals(r.dataverseModified)
                && old.version.equals(r.version)
                && old.format.equals(r.format)
                && old.active == r.active
                && old.manifest.equals(r.manifest)
                && old.pdfSha256.equals(r.pdfSha256);
        if (same) return false;
        ContentValues cv = new ContentValues();
        cv.put("code", r.code);
        cv.put("name", r.name);
        cv.put("format", r.format);
        cv.put("version", r.version);
        cv.put("active", r.active ? 1 : 0);
        if (r.pdf != null) cv.put("pdf", r.pdf); else cv.putNull("pdf");
        cv.put("pdf_sha256", r.pdfSha256);
        cv.put("manifest", r.manifest);
        cv.put("dataverse_id", r.dataverseId);
        cv.put("dataverse_modified", r.dataverseModified);
        cv.put("service_sync", SYNC_PENDING);
        cv.putNull("service_msg");
        cv.put("ts_updated_ms", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public void markService(String code, String status, String msg) {
        ContentValues cv = new ContentValues();
        cv.put("service_sync", status);
        if (msg == null) cv.putNull("service_msg"); else cv.put("service_msg", msg);
        cv.put("service_ts_ms", System.currentTimeMillis());
        helper.getWritableDatabase().update(TABLE, cv, "code=?", new String[]{ keyOf(code) });
    }

    public void delete(String code) {
        helper.getWritableDatabase().delete(TABLE, "code=?", new String[]{ keyOf(code) });
    }
}
