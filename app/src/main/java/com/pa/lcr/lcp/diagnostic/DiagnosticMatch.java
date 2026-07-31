package com.pa.lcr.lcp.diagnostic;

/**
 * Résultat d'une règle de diagnostic qui a matché contre v_diagnostic_events.
 * Phase 2 — plan diagnostic intelligent (27 juillet 2026).
 */
public final class DiagnosticMatch {
    public final String ruleName;
    public final long eventId;
    public final long ts;
    public final String diagnostic;
    public final int confidence;
    public final String supportLevel;
    public final String recommendedAction;

    public DiagnosticMatch(String ruleName, long eventId, long ts, String diagnostic,
                            int confidence, String supportLevel, String recommendedAction) {
        this.ruleName = ruleName;
        this.eventId = eventId;
        this.ts = ts;
        this.diagnostic = diagnostic;
        this.confidence = confidence;
        this.supportLevel = supportLevel;
        this.recommendedAction = recommendedAction;
    }
}