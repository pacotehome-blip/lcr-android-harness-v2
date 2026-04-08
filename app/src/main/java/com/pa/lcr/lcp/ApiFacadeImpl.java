
package com.pa.lcr.lcp;package com * ApiFacadeImpl
 *
 * Implémentation STRICTE du contrat ApiFacade,
 * alignée avec le DeliveryController réel.
 *
 * Aucune API inventée.
 * Aucune logique métier déplacée.
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // USB (global)
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail(
                "USB Scan: not handled here",
                "USB_SCAN_NOT_SUPPORTED"
        );
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail(
                "USB OpenPing: not handled here",
                "USB_OPENPING_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // Media check
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        // La logique réelle est déjà gérée plus bas dans la stack
        return ApiResult.fail(
                "MediaCheck: 0 - Not supported (legacy facade).",
                "MEDIA_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // LCP connect
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(250, 255);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "Connect: 0 - No valid register",
                    "ERR_NO_REGISTER"
            );
        }

        return ApiResult.ok("Connect: 1 - OK");
    }

    // =========================================================
    // Align / Recover (A)
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        return api_deliveryAlignA(250, 255);
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "AlignA: 0 - No valid register",
                    "ERR_NO_REGISTER"
            );
        }

        dc.alignOrRecover();
        return ApiResult.ok("AlignA: 1 - OK");
    }

    // =========================================================
    // Delivery C
    // =========================================================

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        return api_deliveryStartC(250, 255, product1to16, presetNet);
    }

    @Override
    public ApiResult api_deliveryStartC(
            Integer lcrnode_dec,
            Integer from_dec,
            int product1to16,
            double presetNet
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "DeliveryC: 0 - No valid register",
                    "ERR_NO_REGISTER"
            );
        }

        dc.startDelivery(product1to16, presetNet);
        return ApiResult.ok("DeliveryC: 1 - OK");
    }

    // =========================================================
    // Delivery OneShot (legacy)
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(
            String numero_livraison,
            int product1to16,
            double presetNetL,
            String compartment
    ) {
        // OneShot est traité dans le controller via le flow normal
        return ApiResult.fail(
                "OneShot: 0 - Not supported in legacy facade",
                "ONESHOT_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // Job
    // =========================================================

    @Override
    public ApiResult api_deliveryJobGet(String jobId) {
        return ApiResult.fail(
                "JobGet: 0 - Not supported in legacy facade",
                "JOB_NOT_SUPPORTED"
        );
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        return ApiResult.fail(
                "Continue: 0 - Not supported in legacy facade",
                "CONTINUE_NOT_SUPPORTED"
        );
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        return ApiResult.fail(
                "Terminate: 0 - Not supported in legacy facade",
                "TERMINATE_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // ✅ validateRegister (OBLIGATOIRE - legacy)
    // =========================================================

    @Override
    public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    ) {
        int node = (expected_lcrnode_dec != null) ? expected_lcrnode_dec : 250;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, 255);
        if (dc == null) {
            return ApiResult.fail(
                    "Validate: 0 - No valid register",
                    "ERR_NO_REGISTER"
            );
        }

        return dc.api_registerValidate(
                numero_livraison,
                expected_lcrnode_dec,
                expected_serial_id,
                expected_product_number,
                expected_compartment
        );
    }

    // =========================================================
    // DB
    // =========================================================

    @Override
    public ApiResult api_dbDump() {
        return ApiResult.fail(
                "DB Dump: not handled here",
                "DB_DUMP_NOT_SUPPORTED"
        );
    }
}


/**
