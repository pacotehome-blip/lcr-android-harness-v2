
package com.pa.lcr.lcp;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public final class DeliveryLogDbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "lcr_api.db";
    public static final int DB_VERSION = 1;

    public static final String T_DELIVERY = "delivery_log";

    public DeliveryLogDbHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS " + T_DELIVERY + " (" +
            " job_id TEXT PRIMARY KEY," +
            " numero_livraison TEXT," +
            " ticket_no TEXT," +
            " serial_id TEXT," +
            " compartment TEXT," +
            " product_number INTEGER," +
            " delivery_uid TEXT," +
            " start_ms INTEGER," +
            " end_ms INTEGER," +
            " gross_delta INTEGER," +
            " net_delta INTEGER," +
            " gross_total INTEGER," +
            " net_total INTEGER," +
            " inventory_written TEXT," +
            " host_printed INTEGER," +
            " gross_delta_l REAL," +
            " net_delta_l REAL," +
            " gross_total_l REAL," +
            " net_total_l REAL," +
            " result_json TEXT NOT NULL," +
            " created_at_ms INTEGER NOT NULL," +
            " updated_at_ms INTEGER NOT NULL" +
            ");"
        );

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_updated ON " + T_DELIVERY + "(updated_at_ms);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_end ON " + T_DELIVERY + "(end_ms);");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_delivery_uid ON " + T_DELIVERY + "(delivery_uid);");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Minimaliste: on garde les données; ajouter migrations si DB_VERSION change.
        // Pour V1 -> V2, on ferait des ALTER TABLE.
    }
}
