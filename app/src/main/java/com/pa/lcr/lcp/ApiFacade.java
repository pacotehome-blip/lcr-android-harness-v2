
package com.pa.lcr.lcp;

/**
 * API-Face: contrat entre ApiServer et la logique métier.
 *
 * Objectif:
 * - Garder une compatibilité avec la façade mono-registre (DeliveryApiFacadeImpl) existante
 * - Ajouter des variantes node-aware (B2 multi-registre) via des méthodes default
 * pour que ApiServer puisse router par lcrnode_dec / from_dec.
 *
 * Convention:
 * - lcrnode_dec: 1..250 (null -> default 250)
 * - from_dec: 0..255 (null -> default 255)
 */
public interface ApiFacade {

    // =========================================================
    // USB (global)
    // =========================================================
    ApiResult api_scanUsb();
    ApiResult api_openPingUsb();

    // =========================================================
    // ✅ NEW (Option A): Media check (USB/BT) - diagnostic simple
    // =========================================================
    default ApiResult api_mediaCheck(String media, String bt_mac) {
        return ApiResult.fail("MediaCheck: 0 - Not supported (legacy facade).", "MEDIA_NOT_SUPPORTED");
    }

    // =========================================================
    // LCP connect (legacy mono-registre)
    // =========================================================
    ApiResult api_connectLcp();

    // ✅ Node-aware default (B2): fallback sur legacy si non override
    default ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        return api_connectLcp();
    }

    // =========================================================
    // Align / Recover (A) (legacy mono-registre)
    // =========================================================
    ApiResult api_deliveryAlignA();

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        return api_deliveryAlignA();
    }

    // =========================================================
    // DB (global)
    // =========================================================
    ApiResult api_dbDump();

    // =========================================================
    // Delivery (legacy mono-registre)
    // =========================================================
    ApiResult api_deliveryStartC(int product1to16, double presetNet);

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        return api_deliveryStartC(product1to16, presetNet);
    }

    ApiResult api_deliveryJobGet(String jobId);

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        return api_deliveryJobGet(jobId);
    }

    // =========================================================
    // Delivery OneShot + controls (legacy mono-registre)
    // =========================================================
    ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment);

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                              String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
    }

    ApiResult api_deliveryContinue(String jobId);

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        return api_deliveryContinue(jobId);
    }

    ApiResult api_deliveryTerminate(String jobId);

    // ✅ Node-aware default (B2)
    default ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        return api_deliveryTerminate(jobId);
    }

    // =========================================================
    // ✅ COMMIT 2: validateRegister (legacy signature)
    // =========================================================
    ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    );

    // ✅ Node-aware default (B2): from_dec support, fallback sur legacy validate
    default ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            Integer from_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    ) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, expected_serial_id,
                expected_product_number, expected_compartment);
    }

    // =========================================================
    // ✅ NEW: TickBus (B+) - long-poll tick change (cache-only)
    // =========================================================
    default ApiResult api_tickWait(Long since_seq, Integer wait_ms) {
        return ApiResult.fail("Tick: 0 - Not supported (legacy facade).", "TICK_NOT_SUPPORTED");
    }

    default ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        return api_tickWait(since_seq, wait_ms);
    }

    // =========================================================
    // ✅ NEW: Ticket reprint current
    // =========================================================
    /** Legacy mono-registre: reprint ticket courant. */
    default ApiResult api_ticketReprintCurrent() {
        return ApiResult.fail("Reprint: 0 - Not supported (legacy facade).", "REPRINT_NOT_SUPPORTED");
    }

    /** Node-aware (B2): reprint ticket courant pour lcrnode/from. */
    default ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec) {
        return api_ticketReprintCurrent();
    }
}
