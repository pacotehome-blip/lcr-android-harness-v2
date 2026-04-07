
package com.pa.lcr.lcp;

/**
 * Implémentation concrète de l'API.
 * Alignée strictement avec les signatures réelles de ApiFacade.
 * Compatible terrain (USB / BT / replug).
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;

    public ApiFacadeImpl(RegisterSessionManager sessionMgr,
                         DeliveryController delivery) {
        this.sessionMgr = sessionMgr;
        this.delivery   = delivery;
    }

    // =========================================================
    // LCP CONNECT (media auto via la couche existante)
    // =========================================================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                    Integer from_dec,
                                    String media,
                                    String bt_mac) {
        try {
            sessionMgr.resolveOrCreateForNode(
                lcrnode_dec != null ? lcrnode_dec : 250,
                from_dec    != null ? from_dec    : 255
            );

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
    // DELIVERY A (Status / Align / Recover)
    // =========================================================
    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec,
                                        Integer from_dec,
                                        String media,
                                        String bt_mac) {
        try {
            delivery.alignOrRecover(); // void
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
    // DELIVERY C (Start delivery)
    // =========================================================
    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec,
                                        Integer from_dec,
                                        int product1to16,
                                        double presetNet,
                                        String media,
                                        String bt_mac) {
        try {
            delivery.startDelivery(product1to16, presetNet); // void
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
    // DELIVERY CONTINUE (legacy abstrait)
    // =========================================================
    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        // Géré par /v1/delivery/job/continue (niveau job, pas DeliveryController)
        return ApiResult.failLevel(
            "Continue: not handled by DeliveryController",
            "CONTINUE_NOT_SUPPORTED",
            "DELIVERY",
            "api_deliveryContinue"
        );
    }

    // =========================================================
    // DELIVERY TERMINATE (legacy abstrait)
    // =========================================================
    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        // Géré par /v1/delivery/job/terminate (niveau job)
        return ApiResult.failLevel(
            "Terminate: not handled by DeliveryController",
            "TERMINATE_NOT_SUPPORTED",
            "DELIVERY",
            "api_deliveryTerminate"
        );
    }

    // =========================================================
    // DELIVERY ONE-SHOT START (legacy abstrait)
    // =========================================================
    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison,
                                              int product1to16,
                                              double presetNetL,
                                              String compartment) {
        // Géré par /v1/delivery/oneshot/start (niveau API/job)
        return ApiResult.failLevel(
            "OneShotStart: not handled by DeliveryController",
            "ONESHOT_NOT_SUPPORTED",
            "DELIVERY",
            "api_deliveryOneShotStart"
        );
    }

    // =========================================================
    // REGISTER VALIDATE (legacy abstrait)
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
            return ApiResult.failLevel(
                "RegisterValidate: 0 - FAILED",
                "REGISTER_VALIDATE_FAILED",
                "REGISTER",
                "api_registerValidate",
                e.getMessage()
            );
        }
    }
}
