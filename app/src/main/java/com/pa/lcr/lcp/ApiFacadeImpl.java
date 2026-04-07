
package com.pa.lcr.lcp;

import org.json.JSONObject;

/**
 * Implémentation concrète de l'API.
 * ✅ Étape 2 : sélection automatique du média (USB -> BT).
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final MediaTransportManager mediaMgr;
    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;

    public ApiFacadeImpl(MediaTransportManager mediaMgr,
                          RegisterSessionManager sessionMgr,
                          DeliveryController delivery) {
        this.mediaMgr = mediaMgr;
        this.sessionMgr = sessionMgr;
        this.delivery  = delivery;
    }

    // =========================================================
    // 🔑 Media resolution (USB -> BT -> FAIL)
    // =========================================================
    private String resolveMediaAuto(String requestedMedia, String btMac) {
        String m = (requestedMedia == null ? "auto" : requestedMedia).toLowerCase();

        // 1) USB forcé
        if ("usb".equals(m)) {
            if (mediaMgr.isUsbReady() && mediaMgr.canOpenUsb()) {
                return "usb";
            }
            throw ApiException.noMedia("USB not ready");
        }

        // 2) BT forcé
        if ("bt".equals(m)) {
            if (mediaMgr.isBtReady() &&
                (btMac == null || btMac.isEmpty() || mediaMgr.isBtPaired(btMac))) {
                return "bt";
            }
            throw ApiException.noMedia("BT not ready");
        }

        // 3) AUTO : USB prioritaire
        if (mediaMgr.isUsbReady() && mediaMgr.canOpenUsb()) {
            return "usb";
        }

        // 4) Fallback BT
        if (mediaMgr.isBtReady() &&
            (btMac == null || btMac.isEmpty() || mediaMgr.isBtPaired(btMac))) {
            return "bt";
        }

        throw ApiException.noMedia("No USB or BT available");
    }

    // =========================================================
    // LCP connect
    // =========================================================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                    Integer from_dec,
                                    String media,
                                    String bt_mac) {
        try {
            String resolved = resolveMediaAuto(media, bt_mac);

            mediaMgr.activateExclusive(resolved, bt_mac);

            sessionMgr.getOrCreate(
                resolved,
                lcrnode_dec != null ? lcrnode_dec : 250,
                from_dec    != null ? from_dec    : 255
            );

            return ApiResult.okLevel(
                "Connect: 1 - OK via " + resolved,
                "LCP",
                "api_connectLcp"
            );

        } catch (ApiException e) {
            return e.toApiResult();
        }
    }

    // =========================================================
    // Delivery A (Status / Align)
    // =========================================================
    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec,
                                        Integer from_dec,
                                        String media,
                                        String bt_mac) {
        try {
            String resolved = resolveMediaAuto(media, bt_mac);
            mediaMgr.activateExclusive(resolved, bt_mac);

            return delivery.alignOrRecover(
                lcrnode_dec != null ? lcrnode_dec : 250,
                from_dec    != null ? from_dec    : 255
            );

        } catch (ApiException e) {
            return e.toApiResult();
        }
    }

    // =========================================================
    // Delivery C (Start)
    // =========================================================
    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec,
                                        Integer from_dec,
                                        int product1to16,
                                        double presetNet,
                                        String media,
                                        String bt_mac) {
        try {
            String resolved = resolveMediaAuto(media, bt_mac);
            mediaMgr.activateExclusive(resolved, bt_mac);

            return delivery.startDelivery(
                lcrnode_dec != null ? lcrnode_dec : 250,
                from_dec    != null ? from_dec    : 255,
                product1to16,
                presetNet
            );

        } catch (ApiException e) {
            return e.toApiResult();
        }
    }
}
