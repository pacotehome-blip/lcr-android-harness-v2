package com.pa.lcr.lcp.diagnostic;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.pa.lcr.lcp.storage.DeliveryDb;

import java.util.ArrayList;
import java.util.List;

/**
 * Moteur de règles diagnostic minimal — Phase 2 du plan diagnostic intelligent (27 juillet 2026).
 *
 * Volontairement simple : chaque règle de diagnostic_rules est exécutée comme UNE requête SQL
 * paramétrée sur v_diagnostic_events, pas un moteur de règles générique complexe. Aligné avec
 * le principe "changement chirurgical, validé un à un" — pas de sur-ingénierie.
 *
 * Convention precondition_code : "TYPE=SOUS-CHAINE" exige qu'un événement de ce type contenant
 * cette sous-chaîne dans detail_short existe dans la fenêtre [ts - window_seconds, ts] avant
 * l'événement candidat. "!TYPE=SOUS-CHAINE" exige l'ABSENCE d'un tel événement dans la fenêtre.
 */
public final class DiagnosticRuleEngine {

    private final Context appCtx;

    public DiagnosticRuleEngine(Context context) {
        this.appCtx = context.getApplicationContext();
    }

    private static final class Rule {
        long ruleId;
        String name;
        String eventCode;
        String eventType;
        String detailLike;
        String dataJsonLike;
        int windowSeconds;
        String preconditionCode; // nullable, format "TYPE=SUBSTR" ou "!TYPE=SUBSTR"
        String diagnostic;
        int confidence;
        String supportLevel;
        String recommendedAction;
    }

    /**
     * Évalue toutes les règles actives contre la chronologie d'un ticket donné.
     * Persiste aussi chaque résultat dans diagnostic_match_history (demande Paul, 31 juillet
     * 2026 — préparation sync BD support centrale / futur agent IA), jusqu'ici calculé à la
     * volée et jamais stocké. Lecture/écriture — ne modifie jamais delivery_event/api_trace,
     * seulement diagnostic_match_history (nouvelle table dédiée).
     */
    public List<DiagnosticMatch> evaluateForTicket(String ticketNo) {
        List<DiagnosticMatch> matches = new ArrayList<>();
        if (ticketNo == null || ticketNo.trim().isEmpty()) return matches;

        DeliveryDb dbHelper = null;
        SQLiteDatabase db = null;
        try {
            dbHelper = new DeliveryDb(appCtx);
            db = dbHelper.getWritableDatabase(); // writable : on persiste aussi les résultats

            List<Rule> rules = loadRules(db);
            for (Rule r : rules) {
                matches.addAll(evaluateRule(db, r, ticketNo.trim()));
            }

            persistMatches(db, ticketNo.trim(), matches);
        } catch (Exception ignored) {
            // Best-effort : un diagnostic manqué n'est jamais pire qu'aucun diagnostic.
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
            if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
        }
        return matches;
    }

    private void persistMatches(SQLiteDatabase db, String ticketNo, List<DiagnosticMatch> matches) {
        if (matches.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (DiagnosticMatch m : matches) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put("ts", now);
                if (m.ruleId > 0) cv.put("rule_id", m.ruleId); else cv.putNull("rule_id");
                cv.put("rule_name", m.ruleName);
                cv.put("ticket_no", ticketNo);
                cv.put("event_id", m.eventId);
                cv.put("event_ts", m.ts);
                cv.put("diagnostic", m.diagnostic);
                cv.put("confidence", m.confidence);
                cv.put("support_level", m.supportLevel);
                cv.put("recommended_action", m.recommendedAction);
                db.insert("diagnostic_match_history", null, cv);
            } catch (Exception ignored) {
                // Une ligne d'historique perdue ne doit jamais faire échouer l'évaluation elle-même.
            }
        }
    }

    private List<Rule> loadRules(SQLiteDatabase db) {
        List<Rule> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT rule_id, name, event_code, event_type, detail_like, " +
                "data_json_like, window_seconds, precondition_code, diagnostic, confidence, " +
                "support_level, recommended_action FROM diagnostic_rules", null)) {
            while (c.moveToNext()) {
                Rule r = new Rule();
                r.ruleId = c.getLong(0);
                r.name = c.getString(1);
                r.eventCode = c.getString(2);
                r.eventType = c.getString(3);
                r.detailLike = c.getString(4);
                r.dataJsonLike = c.getString(5);
                r.windowSeconds = c.getInt(6);
                r.preconditionCode = c.getString(7);
                r.diagnostic = c.getString(8);
                r.confidence = c.getInt(9);
                r.supportLevel = c.getString(10);
                r.recommendedAction = c.getString(11);
                out.add(r);
            }
        }
        return out;
    }

    private List<DiagnosticMatch> evaluateRule(SQLiteDatabase db, Rule r, String ticketNo) {
        List<DiagnosticMatch> out = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "SELECT event_id, ts FROM v_diagnostic_events WHERE ticket_no = ? ");
        List<String> args = new ArrayList<>();
        args.add(ticketNo);

        if (r.eventCode != null && !r.eventCode.isEmpty()) {
            sql.append("AND event_code = ? ");
            args.add(r.eventCode);
        }
        if (r.eventType != null && !r.eventType.isEmpty()) {
            sql.append("AND event_type = ? ");
            args.add(r.eventType);
        }
        if (r.detailLike != null && !r.detailLike.isEmpty()) {
            sql.append("AND detail_short LIKE ? ");
            args.add(r.detailLike);
        }
        if (r.dataJsonLike != null && !r.dataJsonLike.isEmpty()) {
            sql.append("AND data_json LIKE ? ");
            args.add(r.dataJsonLike);
        }

        // Précondition de corrélation temporelle (EXISTS / NOT EXISTS)
        if (r.preconditionCode != null && !r.preconditionCode.trim().isEmpty()) {
            boolean negate = r.preconditionCode.startsWith("!");
            String body = negate ? r.preconditionCode.substring(1) : r.preconditionCode;
            int eq = body.indexOf('=');
            if (eq > 0) {
                String pType = body.substring(0, eq);
                String pSubstr = body.substring(eq + 1);
                long windowMs = Math.max(0, r.windowSeconds) * 1000L;

                sql.append(negate ? "AND NOT EXISTS (" : "AND EXISTS (")
                   .append("SELECT 1 FROM v_diagnostic_events p WHERE p.ticket_no = v_diagnostic_events.ticket_no ")
                   .append("AND p.event_type = ? AND p.detail_short LIKE ? ")
                   .append("AND p.ts BETWEEN v_diagnostic_events.ts - ? AND v_diagnostic_events.ts) ");
                args.add(pType);
                args.add("%" + pSubstr + "%");
                args.add(String.valueOf(windowMs));
            }
        }

        try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) {
                out.add(new DiagnosticMatch(r.ruleId, r.name, c.getLong(0), c.getLong(1),
                        r.diagnostic, r.confidence, r.supportLevel, r.recommendedAction));
            }
        } catch (Exception ignored) {
            // Une règle mal formée ne doit jamais faire échouer les autres.
        }
        return out;
    }
}