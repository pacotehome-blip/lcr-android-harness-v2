
package com.pa.lcr.lcp;

/**
 * Implémentation concrète de l'API.
 * Strictement alignée avec ApiFacade.java
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;

    public ApiFacadeImpl(RegisterSessionManager sessionMgr,
                         DeliveryController delivery) {
        this.sessionMgr = sessionMgr;
        this.delivery = delivery;
    }

    // =========================================================
    // USB (global)
    // =========================================================
    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.failLevel(
            "USB Scan: not handled here",
            "USB_SCAN_NOT_SUPPORTED",
            "USB",
            "api_scanUsb"
        );
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.failLevel(
            "USB OpenPing: not handled here",
            "USB_OPENPING_NOT_SUPPORTED",
            "USB",
            "api_openPingUsb"
        );
    }

    // =========================================================
    // LCP CONNECT
    // =========================================================
    @Override
    public ApiResult api_connectLcp() {
        try {
            sessionMgr.resolveOrCreateForNode(250, 255);
            return ApiResult.okLevel(
                "Connect: 1 - OK",
                "LCP",
                "api_connectLcp"
            );
        } catch (Exception e) {
            return ApiResult.failLevel(
                "Connect: 0 - FAILED",
                "CONNECT_FAILED",
                "LCP",
                "api_connectLcp",
                e.getMessage()
            );
        }
    }

    // =========================================================
    // DELIVERY A
    // =========================================================
    @Override
    public ApiResult api_deliveryAlignA() {
        try {
            delivery.alignOrRecover();
            return ApiResult.okLevel(
                "Align A: 1 - OK",
                "DELIVERY",
                "api_deliveryAlignA"
            );
        } catch (Exception e) {
            return ApiResult.failLevel(
                "Align A: 0 - FAILED",
                "ALIGN_FAILED",
                "DELIVERY",
                "api_deliveryAlignA",
                e.getMessage()
            );
        }
    }

    // =========================================================
    // DELIVERY C (LEGACY)
    // =========================================================
    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        try {
            delivery.startDelivery(product1to16, presetNet);
            return ApiResult.okLevel(
                "Start C: 1 - OK",
                "DELIVERY",
                "api_deliveryStartC"
            );
        } catch (Exception e) {
            return ApiResult.failLevel(
                "Start C: 0 - FAILED",
                "START_FAILED",
                "DELIVERY",
                "api_deliveryStartC",
                e.getMessage()
            );
        }
    }

    // =========================================================
    // JOB / ONE SHOT / CONTINUE / TERMINATE
    // =========================================================
    @Override public ApiResult api_deliveryJobGet(String jobId) {
        return ApiResult.fail("Job get not handled here", "JOB_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryContinue(String jobId) {
        return ApiResult.fail("Continue not supported", "CONTINUE_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryTerminate(String jobId) {
        return ApiResult.fail("Terminate not supported", "TERMINATE_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison,
                                              int product1to16,
                                              double presetNetL,
                                              String compartment) {
        return ApiResult.fail(
            "OneShot not supported",
            "ONESHOT_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // DB
    // =========================================================
    @Override
    public ApiResult api_dbDump() {
        return ApiResult.fail(
            "DB Dump handled by ApiServer",
            "DB_DUMP_NOT_SUPPORTED"
        );
    }

    // =========================================================
    // REGISTER VALIDATE
    // =========================================================
    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                          Integer expected_lcrnode_dec,
                                          String expected_serial_id,
                                          Integer expected_product_number,
                                          String expected_compartment) {
        try {
            return delivery.api_registerValidate(
                numero_livraison,
                expected_lcrnode_dec,
                expected_serial_id,
                expected_product_number,
                expected_compartment
            );
        } catch (Exception e) {
            return ApiResult.fail(
                "RegisterValidate failed",
                "REGISTER_VALIDATE_FAILED"
            );
        }
    }
}
