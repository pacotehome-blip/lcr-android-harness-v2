package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Copie locale des bannieres d'impression (table banniere_impression de lcr_delivery.db), meme structure que
 * filgo_print_banners dans Dataverse (en-tete, pied de page, taxes, logo). 30 oct 2026.
 *
 * Lues dans Dataverse et gardees ici (donc dans la sauvegarde de la BD). PAS encore envoyees au service
 * d'impression : il n'a pas encore de route pour les recevoir.
 */
public class BanniereImpressionStore {

    private static final String TABLE = "banniere_impression";

    private final DeliveryDb helper;

    public BanniereImpressionStore(Context ctx) {
        this.helper = new DeliveryDb(ctx);
    }

    public static final class Row {
        public String  code = "";
        public String  name = "";
        public byte[]  logo;
        public String  logoSha256 = "";
        public String  addressLine1 = "";
        public String  city = "";
        public String  province = "";
        public String  postalCode = "";
        public String  phone = "";
        public String  tollFreePhone = "";
        public String  taxInfo = "";
        public String  footerText = "";
        public String  company = "";
        public String  version = "";
        public boolean active = true;
        public String  dataverseId = "";
        public String  dataverseModified = "";

        static Row from(Cursor c) {
            Row r = new Row();
            r.code = s(c, "code");
            r.name = s(c, "name");
            int il = c.getColumnIndexOrThrow("logo");
            r.logo = c.isNull(il) ? null : c.getBlob(il);
            r.logoSha256 = s(c, "logo_sha256");
            r.addressLine1 = s(c, "address_line1");
            r.city = s(c, "city");
            r.province = s(c, "province");
            r.postalCode = s(c, "postal_code");
            r.phone = s(c, "phone");
            r.tollFreePhone = s(c, "tollfree_phone");
            r.taxInfo = s(c, "tax_info");
            r.footerText = s(c, "footer_text");
            r.company = s(c, "company");
            r.version = s(c, "version");
            r.active = c.getInt(c.getColumnIndexOrThrow("active")) != 0;
            r.dataverseId = s(c, "dataverse_id");
            r.dataverseModified = s(c, "dataverse_modified");
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

    /** @return true si la ligne a change. Le logo n'est retelecharge que si modifiedon ou version a change. */
    public boolean upsertFromDataverse(Row r) {
        r.code = keyOf(r.code);
        Row old = get(r.code);
        if (old != null && old.dataverseModified.equals(r.dataverseModified) && old.version.equals(r.version)
                && old.active == r.active) return false;
        ContentValues cv = new ContentValues();
        cv.put("code", r.code);
        cv.put("name", r.name);
        if (r.logo != null) cv.put("logo", r.logo); else cv.putNull("logo");
        cv.put("logo_sha256", r.logoSha256);
        cv.put("address_line1", r.addressLine1);
        cv.put("city", r.city);
        cv.put("province", r.province);
        cv.put("postal_code", r.postalCode);
        cv.put("phone", r.phone);
        cv.put("tollfree_phone", r.tollFreePhone);
        cv.put("tax_info", r.taxInfo);
        cv.put("footer_text", r.footerText);
        cv.put("company", r.company);
        cv.put("version", r.version);
        cv.put("active", r.active ? 1 : 0);
        cv.put("dataverse_id", r.dataverseId);
        cv.put("dataverse_modified", r.dataverseModified);
        cv.put("ts_updated_ms", System.currentTimeMillis());
        helper.getWritableDatabase().insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public void delete(String code) {
        helper.getWritableDatabase().delete(TABLE, "code=?", new String[]{ keyOf(code) });
    }
}
