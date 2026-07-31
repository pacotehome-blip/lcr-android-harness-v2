package com.pa.lcr.lcp.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Écriture de la boucle de rétroaction — Phase 3 du plan diagnostic intelligent (27 juillet 2026).
 *
 * Upsert simple : si un incident avec le même (rule_id, serial_id, ticket_no, symptom) existe déjà,
 * incrémente occurrence_count au lieu de dupliquer la ligne — permet de mesurer combien de fois
 * un même diagnostic revient, pour calibrer les confidence de diagnostic_rules avec du signal réel
 * plutôt que des chiffres devinés.
 */
public class IncidentHistoryStore {

    private final DeliveryDb helper;

    public IncidentHistoryStore(Context context) {
        this.helper = new DeliveryDb(context.getApplicationContext());
    }

    /**
     * @param ruleId    nullable — null si diagnostic manuel (pas détecté par une règle automatique)
     * @param serialId  nullable
     * @param ticketNo  nullable
     * @param symptom   requis — description courte du symptôme observé
     * @param rootCause nullable
     * @param resolution nullable
     * @param resolutionTimeMs nullable
     * @param validatedBy nullable — nom de la personne qui a confirmé le diagnostic
     */
    public void recordIncident(Long ruleId, String serialId, String ticketNo, String symptom,
                                String rootCause, String resolution, Long resolutionTimeMs,
                                String validatedBy) {
        if (symptom == null || symptom.trim().isEmpty()) return;

        SQLiteDatabase db = helper.getWritableDatabase();

        // Cherche un incident identique existant pour incrémenter plutôt que dupliquer
        StringBuilder where = new StringBuilder("symptom = ? ");
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(symptom);
        if (ruleId != null) { where.append("AND rule_id = ? "); args.add(String.valueOf(ruleId)); }
        else { where.append("AND rule_id IS NULL "); }
        if (serialId != null) { where.append("AND serial_id = ? "); args.add(serialId); }
        else { where.append("AND serial_id IS NULL "); }
        if (ticketNo != null) { where.append("AND ticket_no = ? "); args.add(ticketNo); }
        else { where.append("AND ticket_no IS NULL "); }

        long existingId = -1;
        try (Cursor c = db.rawQuery(
                "SELECT incident_id FROM incident_history WHERE " + where + "LIMIT 1",
                args.toArray(new String[0]))) {
            if (c.moveToFirst()) existingId = c.getLong(0);
        }

        if (existingId >= 0) {
            db.execSQL("UPDATE incident_history SET occurrence_count = occurrence_count + 1, " +
                    "root_cause = COALESCE(?, root_cause), resolution = COALESCE(?, resolution), " +
                    "resolution_time_ms = COALESCE(?, resolution_time_ms), " +
                    "validated_by = COALESCE(?, validated_by) WHERE incident_id = ?",
                    new Object[]{rootCause, resolution, resolutionTimeMs, validatedBy, existingId});
            return;
        }

        ContentValues cv = new ContentValues();
        if (ruleId != null) cv.put("rule_id", ruleId); else cv.putNull("rule_id");
        cv.put("serial_id", serialId);
        cv.put("ticket_no", ticketNo);
        cv.put("symptom", symptom);
        cv.put("root_cause", rootCause);
        cv.put("resolution", resolution);
        if (resolutionTimeMs != null) cv.put("resolution_time_ms", resolutionTimeMs);
        cv.put("validated_by", validatedBy);
        cv.put("occurrence_count", 1);
        cv.put("created_ts", System.currentTimeMillis());

        db.insert("incident_history", null, cv);
    }
}