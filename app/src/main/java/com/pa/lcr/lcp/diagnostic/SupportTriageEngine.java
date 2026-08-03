package com.pa.lcr.lcp.diagnostic;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import com.pa.lcr.lcp.storage.DeliveryDb;

import java.util.ArrayList;
import java.util.List;

/**
 * Triage support partagé (demandé 31 juillet 2026) — extrait de MainActivity pour que
 * l'onglet Support (UI) ET l'API exposent EXACTEMENT la même logique, plutôt que deux
 * implémentations qui pourraient diverger avec le temps.
 */
public final class SupportTriageEngine {

    public static final class TriageResult {
        public final String supportLevel;
        public final String layer;
        public final int transportCount, apiCount, uiCount, indetermineCount;
        public final List<DiagnosticMatch> matches;

        TriageResult(String supportLevel, String layer, int transportCount, int apiCount,
                     int uiCount, int indetermineCount, List<DiagnosticMatch> matches) {
            this.supportLevel = supportLevel;
            this.layer = layer;
            this.transportCount = transportCount;
            this.apiCount = apiCount;
            this.uiCount = uiCount;
            this.indetermineCount = indetermineCount;
            this.matches = matches;
        }
    }

    private SupportTriageEngine() {}

    /**
     * Triage global sur TOUS les logs présents (v_diagnostic_events + log_bus_event) pour
     * un ticket/node donnés. Voir MainActivity.computeSupportTriage() (implémentation
     * d'origine, 31 juillet 2026) pour le détail du raisonnement de classification.
     *
     * Cette surcharge accepte des DiagnosticMatch déjà calculés — utile quand l'appelant a
     * déjà évalué les règles (évite de ré-exécuter DiagnosticRuleEngine et de dupliquer les
     * écritures dans diagnostic_match_history).
     */
    public static TriageResult computeTriage(Context ctx, String ticketNo, String nodeFilter,
                                              List<DiagnosticMatch> matches) {
        int cTransport = 0, cApi = 0, cUi = 0, cIndetermine = 0;

        DeliveryDb dbHelper = null;
        SQLiteDatabase db = null;
        try {
            dbHelper = new DeliveryDb(ctx.getApplicationContext());
            db = dbHelper.getReadableDatabase();

            try (android.database.Cursor c = db.rawQuery(
                    "SELECT event_where, level, event_type, event_code, data_json " +
                    "FROM v_diagnostic_events WHERE ticket_no = ?", new String[]{ticketNo})) {
                while (c.moveToNext()) {
                    String eventWhere = c.getString(0);
                    String eventType = c.getString(2);
                    String dataJson = c.getString(4);

                    if (dataJson != null && dataJson.contains("\"level\":\"TRANSPORT\"")) {
                        cTransport++;
                    } else if ("LCP".equals(eventWhere)) {
                        cTransport++;
                    } else if ("API_TRACE".equals(eventType) || "ApiServer".equals(eventWhere)) {
                        cApi++;
                    } else if (eventType != null && eventType.startsWith("UI_")) {
                        cUi++;
                    } else {
                        cIndetermine++;
                    }
                }
            }

            if (nodeFilter != null && !nodeFilter.trim().isEmpty()) {
                try {
                    int node = Integer.parseInt(nodeFilter.trim());
                    try (android.database.Cursor c2 = db.rawQuery(
                            "SELECT src FROM log_bus_event WHERE node = ?", new String[]{String.valueOf(node)})) {
                        while (c2.moveToNext()) {
                            String src = c2.getString(0);
                            if ("IO_TX".equals(src) || "IO_RX".equals(src)) cTransport++;
                            else if ("API".equals(src)) cApi++;
                            else if ("UI".equals(src)) cUi++;
                            else cIndetermine++;
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
            if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
        }

        String layer;
        int maxCount = Math.max(Math.max(cTransport, cApi), cUi);
        if (maxCount == 0) layer = "INDÉTERMINÉ";
        else if (maxCount == cTransport) layer = "TRANSPORT";
        else if (maxCount == cApi) layer = "API";
        else layer = "UI";

        String supportLevel = "N/A";
        int worstSeverity = -1;
        for (DiagnosticMatch m : matches) {
            int sev = severityOf(m.supportLevel);
            if (sev > worstSeverity) {
                worstSeverity = sev;
                supportLevel = m.supportLevel;
            }
        }

        return new TriageResult(supportLevel, layer, cTransport, cApi, cUi, cIndetermine, matches);
    }

    /**
     * Surcharge complète — évalue aussi les règles elle-même (DiagnosticRuleEngine), pour
     * les appelants qui n'ont pas encore de DiagnosticMatch calculés (ex: l'API HTTP).
     */
    public static TriageResult computeTriage(Context ctx, String ticketNo, String nodeFilter) {
        List<DiagnosticMatch> matches;
        try {
            DiagnosticRuleEngine engine = new DiagnosticRuleEngine(ctx);
            matches = engine.evaluateForTicket(ticketNo);
        } catch (Exception e) {
            matches = new ArrayList<>();
        }
        return computeTriage(ctx, ticketNo, nodeFilter, matches);
    }

    private static int severityOf(String supportLevel) {
        if (supportLevel == null || supportLevel.length() < 2 || supportLevel.charAt(0) != 'N') return 0;
        try {
            return Integer.parseInt(supportLevel.substring(1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}