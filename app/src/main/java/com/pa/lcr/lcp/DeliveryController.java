
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.Arrays;

/**
 * DeliveryController — attente FLOW alignée sur le script Python lcr_simple_deliverV2.py
 * - Poll 0x23 (GET_MACHINE) toutes ~200 ms
 * - Détection front montant de FLOW (anti-rebond = 2 confirmations)
 * - Filet : variation GrossCount0 (#44)
 * - Méthode utilitaire "open mode" (preset=0) : set product + clear presets + RUN + wait
 */
public class DeliveryController {

    public enum State { IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE }

    private final LcpLink link;
    private final LcpLink.Logger logger;

    // Masques delCode (parité LcpLink)
    private static final int LCRSc_DEL_TICKET_PENDING = LcpLink.LCRSc_DEL_TICKET_PENDING;
    private static final int LCRSc_FLOW_ACTIVE        = LcpLink.LCRSc_FLOW_ACTIVE;
    private static final int LCRSc_DELIVERY_ACTIVE    = LcpLink.LCRSc_DELIVERY_ACTIVE;
    private static final int LCRSc_BEGIN_DELIVERY     = LcpLink.LCRSc_BEGIN_DELIVERY;

    public DeliveryController(LcpLink link, LcpLink.Logger logger) {
        this.link = link;
        this.logger = logger != null ? logger : s -> {};
    }

    private void log(String s){ if (logger != null) logger.log(s); }

    /* ------------------------------------------------------------
     * Util: int32 -> big-endian bytes (pour #5/#6)
     * ------------------------------------------------------------ */
    private static byte[] i32be(int v) {
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8 ) & 0xFF),
                (byte)((v      ) & 0xFF),
        };
    }

    /* ------------------------------------------------------------
     * Attendre FLOW après un RUN déjà envoyé (équivalent Python)
     *  - timeoutMs : total (ex: 20_000)
     *  - pollMs    : cadence (ex: 200)
     *  - acceptFlow: utilise le flag FLOW
     *  - acceptCounts: variation GrossCount0 (#44) = filet
     * Retourne true si FLOW détecté (ou DELIVERY_ACTIVE/BEGIN), sinon IOException
     * ------------------------------------------------------------ */
    public boolean waitForFlowOnly(long timeoutMs,
                                   long pollMs,
                                   boolean acceptFlow,
                                   boolean acceptCounts) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;
        boolean prevFlow = false;
        int flowTrueConsec = 0; // anti-rebond = 2 confirmations

        int g0 = 0;
        try {
            byte[] raw = link.opGetField(44); // GrossCount0
            g0 = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16) | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
        } catch (Exception ignored) {}

        while (System.currentTimeMillis() < tEnd) {
            int[] ms = link.opMachineStatusFull(); // dev, ds, dc
            int dc = ms[2];
            boolean flow   = (dc & LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LCRSc_BEGIN_DELIVERY) != 0;

            log(String.format("[WAIT_FLOW] delCode=0x%04X flow=%s active=%s", dc, flow, active));

            // Conditions directes (comme Python)
            if (active || begin) return true;

            // Front FLOW (anti-rebond)
            if (acceptFlow) {
                if (flow) { flowTrueConsec++; } else { flowTrueConsec = 0; }
                if (!prevFlow && flowTrueConsec >= 2) return true;
                prevFlow = flow;
            }

            // Filet par variation GrossCount0
            if (acceptCounts) {
                try {
                    byte[] rg = link.opGetField(44);
                    int g = ((rg[0] & 0xFF) << 24) | ((rg[1] & 0xFF) << 16) | ((rg[2] & 0xFF) << 8) | (rg[3] & 0xFF);
                    if (g > g0) return true;
                } catch (Exception ignored) {}
            }

            sleepMs((int)pollMs);
        }
        throw new IOException("START_TIMEOUT: FLOW non détecté dans le délai (ouvrir le pistolet / interlock ?)");
    }

    private static void sleepMs(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /* ------------------------------------------------------------
     * Exemple "open mode" (preset=0) : set product + clear #5/#6 + RUN + wait
     *  - productId: 1..16 (ton usage)
     *  - useCmd00 : true => RUN 0x00
     * ------------------------------------------------------------ */
    public boolean doStartOpenMode(int productId, boolean useCmd00,
                                   long timeoutMs, long pollMs) throws IOException {

        // Sélection produit (#0) — 1 octet 0-based
        if (productId < 1 || productId > 16)
            throw new IllegalArgumentException("ProductNumber doit être 1..16");
        link.opSetField(0, new byte[]{ (byte)(productId - 1) });
        log("[PRE] Sélection produit: " + productId);

        // Clear presets (#5 gross, #6 net) -> mode ouvert
        link.opSetField(5, i32be(0));
        link.opSetField(6, i32be(0));
        log("[PRE] Mode ouvert → clear presets #5/#6");

        // RUN 0x00 / 0x01
        link.opIssueCommand(useCmd00 ? 0x00 : 0x01);
        log(String.format("[START] Issue RUN %s", useCmd00 ? "0x00" : "0x01"));

        // Attente FLOW (200 ms, 20 s) — parité Python
        return waitForFlowOnly(timeoutMs, pollMs, true, true);
    }
}