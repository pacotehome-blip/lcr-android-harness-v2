
package com.pa.lcr.lcp;

import org.json.JSONObject;

/**
 * Implémentation concrète de l'API.
 * Étape 2 : sélection automatique du média (USB prioritaire, fallback BT),
 * alignée STRICTEMENT avec les signatures existantes.
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;

    public ApiFacadeImpl(RegisterSessionManager sessionMgr,
                          DeliveryController delivery) {
        this.sessionMgr = sessionMgr;
        this.delivery  = delivery;
    }

    // =========================================================
    // LCP CONNECT (media-aware, auto USB -> BT)
    // =========================================================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                    Integer from_dec,
                                    String media,
                                    String bt_mac) {
        try {
            // ⚠️ La logique de sélection USB/BT est DEJA intégrée
            // dans la couche Session / Transport
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
    // DELIVERY A (Status / Align)
    // =========================================================
    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec,
                                        Integer from_dec,
                                        String media,
                                        String bt_mac) {
        try {
            return delivery.alignOrRecover();
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
            return delivery.startDelivery(product1to16, presetNet);
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
    // REGISTER VALIDATE (legacy obligatoire)
    // =========================================================
    @Override
    public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment) {

        try {
            // Délégation simple pour l’instant
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
