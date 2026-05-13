package com.pa.lcr.lcp;

/**
 * API-Face: contrat entre ApiServer et la logique métier.
 *
 * Objectif:
 * - Garder une compatibilité avec la façade mono-registre existante
 * - Ajouter des variantes node-aware (B2 multi-registre)
 * - ✅ Option B: ajouter des overloads media-aware (media + bt_mac) pour USB/BT
 *
 * Convention:
 * - lcrnode_dec: 1..250 (null -> default 250)
 * - from_dec: 0..255 (null -> default 255)
 * - media: "usb" "bt" "wifi" (wifi = futur)
 * - bt_mac requis si media="bt"
 */
public interface ApiFacade {

    // =========================================================
    // ✅ BT (debug/ops)
    // =========================================================

    default ApiResult api_btList() {
        return ApiResult.fail(
                "BT list: 0 - Not supported (legacy facade).",
                "BT_LIST_NOT_SUPPORTED"
        );
    }

    default ApiResult api_btActivate() {
        return ApiResult.fail(
                "BT activate: 0 - Not supported (legacy facade).",
                "BT_ACTIVATE_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // ✅ BT Signal (RSSI + qualité IO)
    // =========================================================

    /**
     * GET /v1/bt/signal
     * Retourne le dernier signal BT connu (RSSI + score IO) depuis la DB.
     * bt_mac optionnel — si absent, utilise le transport BT actif.
     */
    default ApiResult api_btSignalGet(String bt_mac) {
        return ApiResult.fail(
                "BT signal: 0 - Not supported (legacy facade).",
                "BT_SIGNAL_NOT_SUPPORTED"
        );
    }

    /**
     * POST /v1/bt/signal/scan
     * Déclenche un scan RSSI ponctuel via BluetoothAdapter.startDiscovery().
     * ⚠️ Bloqué si une livraison est active (sauf perte de connexion détectée).
     * bt_mac optionnel — si absent, scanne tous les appareils pairés.
     */
    default ApiResult api_btSignalScan(String bt_mac) {
        return ApiResult.fail(
                "BT signal scan: 0 - Not supported (legacy facade).",
                "BT_SIGNAL_SCAN_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // USB (global)
    // =========================================================
    ApiResult api_scanUsb();
    ApiResult api_registerConnectAuto(String serialId, Integer lcrnode);
    ApiResult api_openPingUsb();

    // =========================================================
    // ✅ Media check (USB/BT)
    // =========================================================
    default ApiResult api_mediaCheck(String media, String bt_mac) {
        return ApiResult.fail(
                "MediaCheck: 0 - Not supported (legacy facade).",
                "MEDIA_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // LCP connect (legacy mono-registre)
    // =========================================================
    ApiResult api_connectLcp();

    default ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        return api_connectLcp();
    }

    default ApiResult api_connectLcp(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String bt_mac) {
        return api_connectLcp(lcrnode_dec, from_dec);
    }

    default ApiResult api_deliveryStatusB(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        return ApiResult.fail("api_deliveryStatusB: not supported", "NOT_SUPPORTED");
    }
    
    default ApiResult api_printerStatus(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        return ApiResult.fail("api_printerStatus: not supported", "NOT_SUPPORTED");
    }    
    
    // =========================================================
    // Align / Recover (A)
    // =========================================================
    ApiResult api_deliveryAlignA();

    default ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        return api_deliveryAlignA();
    }

    default ApiResult api_deliveryAlignA(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String bt_mac) {
        return api_deliveryAlignA(lcrnode_dec, from_dec);
    }

    // =========================================================
    // DB (global)
    // =========================================================
    ApiResult api_dbDump();

    // =========================================================
    // Delivery (legacy mono-registre)
    // =========================================================
    ApiResult api_deliveryStartC(int product1to16, double presetNet);

    default ApiResult api_deliveryStartC(
            Integer lcrnode_dec,
            Integer from_dec,
            int product1to16,
            double presetNet) {
        return api_deliveryStartC(product1to16, presetNet);
    }

    default ApiResult api_deliveryStartC(
            Integer lcrnode_dec,
            Integer from_dec,
            int product1to16,
            double presetNet,
            String media,
            String bt_mac) {
        return api_deliveryStartC(lcrnode_dec, from_dec, product1to16, presetNet);
    }

    // =========================================================
    // Delivery Job
    // =========================================================
    ApiResult api_deliveryJobGet(String jobId);

    default ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        return api_deliveryJobGet(jobId);
    }

    // =========================================================
    // Delivery OneShot + controls
    // =========================================================
    ApiResult api_deliveryOneShotStart(
            String numero_livraison,
            int product1to16,
            double presetNetL,
            String compartment
    );

    default ApiResult api_deliveryOneShotStart(
            Integer lcrnode_dec,
            Integer from_dec,
            String numero_livraison,
            int product1to16,
            double presetNetL,
            String compartment) {
        return api_deliveryOneShotStart(
                numero_livraison, product1to16, presetNetL, compartment);
    }

    default ApiResult api_deliveryOneShotStart(
            Integer lcrnode_dec,
            Integer from_dec,
            String numero_livraison,
            int product1to16,
            double presetNetL,
            String compartment,
            String media,
            String bt_mac) {
        return api_deliveryOneShotStart(
                lcrnode_dec, from_dec,
                numero_livraison, product1to16, presetNetL, compartment);
    }

    ApiResult api_deliveryContinue(String jobId);

    default ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        return api_deliveryContinue(jobId);
    }

    ApiResult api_deliveryTerminate(String jobId);

    default ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        return api_deliveryTerminate(jobId);
    }

    // =========================================================
    // validateRegister
    // =========================================================
    ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    );

    default ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            Integer from_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment) {
        return api_registerValidate(
                numero_livraison, expected_lcrnode_dec,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    default ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            Integer from_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment,
            String media,
            String bt_mac) {
        return api_registerValidate(
                numero_livraison, expected_lcrnode_dec, from_dec,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    // =========================================================
    // TickBus
    // =========================================================
    default ApiResult api_tickWait(Long since_seq, Integer wait_ms) {
        return ApiResult.fail(
                "Tick: 0 - Not supported (legacy facade).",
                "TICK_NOT_SUPPORTED"
        );
    }

    default ApiResult api_tickWait(
            Integer lcrnode_dec,
            Long since_seq,
            Integer wait_ms) {
        return api_tickWait(since_seq, wait_ms);
    }

    // =========================================================
    // Ticket reprint current
    // =========================================================
    default ApiResult api_ticketReprintCurrent() {
        return ApiResult.fail(
                "Reprint: 0 - Not supported (legacy facade).",
                "REPRINT_NOT_SUPPORTED"
        );
    }

    default ApiResult api_ticketReprintCurrent(
            Integer lcrnode_dec,
            Integer from_dec) {
        return api_ticketReprintCurrent();
    }

    default ApiResult api_ticketReprintCurrent(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String bt_mac) {
        return api_ticketReprintCurrent(lcrnode_dec, from_dec);
    }
}
