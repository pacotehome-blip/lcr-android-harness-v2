
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ApiFacadeImpl
 *
 * Règles MEDIA (CONTRAT FINAL):
 *
 * media = "bt"
 *   - s'il existe un BT appairé / actif / READY -> utiliser automatiquement (MAC déduit)
 *   - sinon -> fallback USB
 *   - sinon -> erreur
 *
 * media = "usb"
 *   - USB prêt -> OK
 *   - sinon -> erreur
 *
 * ✅ bt_mac N'EST JAMAIS REQUIS côté API
 * ✅ La résolution MAC est INTERNE
 * ✅ Processus transparent pour Field Service et le livreur
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;
    private final Context appCtx;

    public ApiFacadeImpl(RegisterSessionManager sessionMgr, DeliveryController delivery) {
        this.sessionMgr = sessionMgr;
        this.delivery = delivery;
        this.appCtx = (sessionMgr != null) ? sessionMgr.getAppContext() : null;
    }

    // =========================================================
    // Helpers
    // =========================================================

    private MediaTransportManager mtm() {
        return (appCtx != null) ? MediaTransportManager.get(appCtx) : null;
    }

    private static String mediaOfKey(String key) {
        if (key == null) return "unknown";
        if (MediaTransportManager.KEY_USB.equals(key)) return "usb";
        if (key.startsWith("BT:")) return "bt";
        return "unknown";
    }

    private static ApiResult okWithTransport(String msg, String where, TransportIo io) {
        JSONObject d = new JSONObject();
        try {
            d.put("media", mediaOfKey(io != null ? io.getKey() : null));
            d.put("transportKey", io != null ? io.getKey() : JSONObject.NULL);
            d.put("connected", (io != null && io.isOpen()) ? 1 : 0);
        } catch (Exception ignored) {}
        return ApiResult.okLevel(msg, "MEDIA", where, d);
    }

    private static ApiResult failMedia(String msg, String err, String where) {
        return ApiResult.failLevel(msg, err, "MEDIA", where, null);
    }

    // =========================================================
    // USB (contrat)
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail("USB scan not supported via API", "USB_SCAN_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail("USB open-ping not supported via API", "USB_OPENPING_NOT_SUPPORTED");
    }

    // =========================================================
    // MEDIA CHECK (✅ AUTO BT + FALLBACK USB)
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        MediaTransportManager mtm = mtm();
        if (mtm == null) {
            return failMedia("MediaCheck: 0 - MTM absent", "ERR_NO_MTM", "api_mediaCheck");
        }

        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "usb";

        // ---- BT demandé: résolution automatique ----
        if ("bt".equals(m)) {

            // 1) tenter BT actif / READY automatiquement
            TransportIo bt = mtm.autoSelectConnect("bt", null);
            if (bt != null && bt.isOpen()) {
                return okWithTransport("MediaCheck: 1 - BT actif", "api_mediaCheck", bt);
            }

            // 2) fallback USB
            TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
            if (usb != null && usb.isOpen()) {
                return okWithTransport("MediaCheck: 1 - USB fallback", "api_mediaCheck", usb);
            }

            return failMedia("MediaCheck: 0 - Aucun média prêt", "ERR_NO_MEDIA_READY", "api_mediaCheck");
        }

        // ---- USB demandé explicitement ----
        if ("usb".equals(m)) {
            TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
            if (usb != null && usb.isOpen()) {
                return okWithTransport("MediaCheck: 1 - USB prêt", "api_mediaCheck", usb);
            }
            return failMedia("MediaCheck: 0 - USB non prêt", "ERR_USB_NOT_CONNECTED", "api_mediaCheck");
        }

        return failMedia("MediaCheck: 0 - media invalide", "ERR_MEDIA_INVALID", "api_mediaCheck");
    }

    // =========================================================
    // LCP CONNECT (✅ AUTO BT + FALLBACK USB)
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(250, 255, "bt", null);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec,
                                    String media, String bt_mac) {

        MediaTransportManager mtm = mtm();
        if (mtm == null) {
            return failMedia("Connect LCP: 0 - MTM absent", "ERR_NO_MTM", "api_connectLcp");
        }

        int node = (lcrnode_dec != null) ? (lcrnode_dec & 0xFF) : 250;
        int from = (from_dec != null) ? (from_dec & 0xFF) : 255;

        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "bt";
        TransportIo io = null;

        // ---- BT auto ----
        if ("bt".equals(m)) {
            io = mtm.autoSelectConnect("bt", null);
            if (io == null || !io.isOpen()) {
                io = mtm.getByKey(MediaTransportManager.KEY_USB); // fallback
            }
        }

        // ---- USB explicite ----
        if (io == null && "usb".equals(m)) {
            io = mtm.getByKey(MediaTransportManager.KEY_USB);
        }

        if (io == null || !io.isOpen()) {
            return failMedia("Connect LCP: 0 - Aucun média connectable",
                    "ERR_NO_MEDIA_CONNECTABLE", "api_connectLcp");
        }

        if (!mtm.activateExclusive(io.getKey(), "API_CONNECT_LCP")) {
            return failMedia("Connect LCP: 0 - Activation échouée",
                    "ERR_TRANSPORT_NOT_READY", "api_connectLcp");
        }

        if (sessionMgr != null) {
            sessionMgr.getOrCreate(io.getKey(), node, from, io);
        }

        return okWithTransport("Connect LCP: 1 - OK", "api_connectLcp", io);
    }

    // =========================================================
    // DELIVERY A / C
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        try {
            delivery.alignOrRecover();
            return ApiResult.ok("Align A: 1 - OK");
        } catch (Exception e) {
            return ApiResult.fail("Align A failed", "ALIGN_FAILED");
        }
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode, Integer from,
                                       String media, String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode, from, media, bt_mac);
        if (c == null || c.code != 1) return c;
        return api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        try {
            delivery.startDelivery(product1to16, presetNet);
            return ApiResult.ok("Start C: 1 - OK");
        } catch (Exception e) {
            return ApiResult.fail("Start C failed", "START_FAILED");
        }
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode, Integer from,
                                       int product1to16, double presetNet,
                                       String media, String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode, from, media, bt_mac);
        if (c == null || c.code != 1) return c;
        return api_deliveryStartC(product1to16, presetNet);
    }

    // =========================================================
    // Required contract stubs
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison,
                                             int product1to16,
                                             double presetNetL,
                                             String compartment) {
        return ApiResult.fail("OneShot not supported", "ONESHOT_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryJobGet(String jobId) {
        return ApiResult.fail("Job get not supported", "JOB_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryContinue(String jobId) {
        return ApiResult.fail("Continue not supported", "CONTINUE_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryTerminate(String jobId) {
        return ApiResult.fail("Terminate not supported", "TERMINATE_NOT_SUPPORTED");
    }

    @Override public ApiResult api_dbDump() {
        return ApiResult.fail("DB dump not supported", "DB_DUMP_NOT_SUPPORTED");
    }

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
            return ApiResult.fail("RegisterValidate failed", "REGISTER_VALIDATE_FAILED");
        }
    }
}
