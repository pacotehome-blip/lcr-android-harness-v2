package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

/**
 * ImprimanteCiterneStore — table locale imprimante_citerne (v29, 29 oct 2026).
 *
 * Une ligne par (citerne, format) : une citerne/camion peut avoir une imprimante GRAND (PJ-862)
 * ET une PETIT. Le format est déduit du pilote (PrinterCatalog.formatFor), jamais saisi.
 * La ligne est poussée au service d'impression (PrintServiceClient) ; service_sync dit où on en est.
 * Les colonnes dataverse_* sont réservées à la 2e phase (push vers filgo_print_printers, table
 * pas encore créée côté Dataverse) — non utilisées ici.
 */
public class ImprimanteCiterneStore {

    public static final String TABLE = "imprimante_citerne";
    public static final String SYNC_PENDING = "PENDING";
    public static final String SYNC_SENT    = "SENT";
    public static final String SYNC_ERROR   = "ERROR";
    public static final String SRC_DATAVERSE = "DATAVERSE";
    public static final String SRC_MANUEL    = "MANUEL";

    private final DeliveryDb helper;

    public ImprimanteCiterneStore(Context ctx) {
        this.helper = new DeliveryDb(ctx);
    }

    public static final class Row {
        public long   id;
        public String truckKey;
        public String truckId;
        public String format;
        public String company;
        public String driverType;
        public String btMac;
        public String printerName;
        public String citerneSource;
        public String registreSerial;
        public Integer registreNud;
        public String registreGuid;
        public String serviceSync;
        public String serviceMsg;
        public long   serviceTsMs;

        static Row from(Cursor c) {
            Row r = new Row();
            r.id             = c.getLong(c.getColumnIndexOrThrow("id"));
            r.truckKey       = s(c, "truck_key");
            r.truckId        = s(c, "truck_id");
            r.format         = s(c, "format");
            r.company        = s(c, "company");
            r.driverType     = s(c, "driver_type");
            r.btMac          = s(c, "bt_mac");
            r.printerName    = s(c, "printer_name");
            r.citerneSource  = s(c, "citerne_source");
            r.registreSerial = s(c, "registre_serial");
            int i = c.getColumnIndexOrThrow("registre_nud");
            r.registreNud    = c.isNull(i) ? null : c.getInt(i);
            r.registreGuid   = s(c, "registre_guid");
            r.serviceSync    = s(c, "service_sync");
            r.serviceMsg     = s(c, "service_msg");
            r.serviceTsMs    = c.getLong(c.getColumnIndexOrThrow("service_ts_ms"));
            return r;
        }
        private static String s(Cursor c, String col) {
            int i = c.getColumnIndexOrThrow(col);
            return c.isNull(i) ? null : c.getString(i);
        }
    }

    public static String keyOf(String truckId) {
        return truckId == null ? "" : truckId.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public List<Row> listAll() {
        List<Row> out = new ArrayList<>();
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null, "truck_key ASC, format ASC")) {
            while (c.moveToNext()) out.add(Row.from(c));
        }
        return out;
    }

    public Row get(String truckId, String format) {
        SQLiteDatabase db = helper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, "truck_key=? AND format=?",
                new String[]{ keyOf(truckId), format }, null, null, null, "1")) {
            return c.moveToFirst() ? Row.from(c) : null;
        }
    }

    /** Crée ou remplace la ligne (citerne, format) ; la repasse en PENDING (à renvoyer au service). */
    public long save(Row r) {
        long now = System.currentTimeMillis();
        ContentValues cv = new ContentValues();
        cv.put("truck_key",  keyOf(r.truckId));
        cv.put("truck_id",   r.truckId.trim());
        cv.put("format",     r.format);
        cv.put("company",    r.company == null ? "" : r.company);
        cv.put("driver_type", r.driverType);
        cv.put("bt_mac",     r.btMac);
        cv.put("printer_name", r.printerName);
        cv.put("citerne_source", r.citerneSource == null ? SRC_MANUEL : r.citerneSource);
        cv.put("registre_serial", r.registreSerial);
        if (r.registreNud != null) cv.put("registre_nud", r.registreNud);
        cv.put("registre_guid", r.registreGuid);
        cv.put("service_sync", SYNC_PENDING);
        cv.putNull("service_msg");
        cv.put("ts_updated_ms", now);
        SQLiteDatabase db = helper.getWritableDatabase();
        int n = db.update(TABLE, cv, "truck_key=? AND format=?",
                new String[]{ keyOf(r.truckId), r.format });
        if (n > 0) return n;
        cv.put("ts_created_ms", now);
        return db.insert(TABLE, null, cv);
    }

    public void markService(long id, String status, String msg) {
        ContentValues cv = new ContentValues();
        cv.put("service_sync", status);
        if (msg == null) cv.putNull("service_msg"); else cv.put("service_msg", msg);
        cv.put("service_ts_ms", System.currentTimeMillis());
        helper.getWritableDatabase().update(TABLE, cv, "id=?", new String[]{ String.valueOf(id) });
    }

    public void delete(long id) {
        helper.getWritableDatabase().delete(TABLE, "id=?", new String[]{ String.valueOf(id) });
    }
}
