
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ApiFacadeImpl
 *
 * Correctifs:
 * - Respect réel du paramètre media (usb/bt/auto) pour /lcp/connect et pour les opérations A/C.
 * - OPTION B: auto-connect BT (sans UI) si transport BT connu mais non ouvert.
 * - BT sans bt_mac: si bt_mac absent, on utilise le transport BT ACTIF.
 * - Session LCP pinnée sur le transport choisi via RegisterSessionManager.getOrCreate().
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

    // ---------------------------------------------------------
    // Helpers media
    // ---------------------------------------------------------

    private static String norm(String s) {
        return (s == null) ? "" : s.trim();
    }

    private static String normLower(String s) {
        return norm(s).toLowerCase(Locale.ROOT);
    }

    private static boolean isBtKey(String k) {
        return k != null && k.toUpperCase(Locale.ROOT).startsWith("BT:");
    }

    /**
     * Résout la clé BT: soit depuis bt_mac, soit depuis le transport BT actif.
     */
    private static String resolveBtKeyFromMacOrActive(String btMac) {
        String mac = norm(btMac);
        if (!mac.isEmpty()) {
            return MediaTransportManager.btKey(mac);
        }
        String active = MediaTransportManager.getActiveKeyStatic();
        return isBtKey(active) ? active : null;
    }

    private MediaTransportManager mtm() {
        return (appCtx != null) ? MediaTransportManager.get(appCtx) : null;
    }

    private static String mediaOfKey(String key) {
        if (key == null) return "unknown";
        if (MediaTransportManager.KEY_USB.equals(key)) return "usb";
        if (isBtKey(key)) return "bt";
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

    private static ApiResult failMedia(String msg, String err, String where, String detail) {
        return ApiResult.failLevel(msg, err, "MEDIA", where, detail);
    }

    // ---------------------------------------------------------
    // Media check (OPTION B)
    // ---------------------------------------------------------

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        try {
            MediaTransportManager mtm = mtm();
            if (mtm == null) {
                return failMedia("MediaCheck: 0 - MTM absent",
                        "ERR_NO_MTM", "api_mediaCheck", "");
            }

            TransportIo io = mtm.autoConnect(media, bt_mac, 2000);
            if (io == null || !io.isOpen()) {
                return failMedia("MediaCheck: 0 - média non connecté",
                        "ERR_MEDIA_NOT_CONNECTED", "api_mediaCheck", media);
            }

            return okWithTransport("MediaCheck: 1 - connecté", "api_mediaCheck", io);

        } catch (Exception e) {
            return failMedia("MediaCheck: 0 - erreur",
                    "ERR_MEDIA_CHECK", "api_mediaCheck", e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // LCP CONNECT (OPTION B)
    // ---------------------------------------------------------

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(250, 255, "auto", null);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                    Integer from_dec,
                                    String media,
                                    String bt_mac) {

        int node = (lcrnode_dec != null) ? (lcrnode_dec & 0xFF) : 250;
        int from = (from_dec != null) ? (from_dec & 0xFF) : 255;

        try {
            MediaTransportManager mtm = mtm();
            if (mtm == null) {
                return failMedia("Connect LCP: 0 - MTM absent",
                        "ERR_NO_MTM", "api_connectLcp", "");
            }

            TransportIo io = mtm.autoConnect(media, bt_mac, 8000);
            if (io == null || !io.isOpen()) {
                return failMedia("Connect LCP: 0 - Aucun média connectable",
                        "ERR_NO_MEDIA_CONNECTABLE", "api_connectLcp", media);
            }

            if (!mtm.activateExclusive(io.getKey(), "API_CONNECT_LCP")) {
                return failMedia("Connect LCP: 0 - Activation échouée",
                        "ERR_TRANSPORT_NOT_READY", "api_connectLcp", io.getKey());
            }

            if (sessionMgr != null) {
                sessionMgr.getOrCreate(io.getKey(), node, from, io);
            }

            return okWithTransport("Connect LCP: 1 - OK", "api_connectLcp", io);

        } catch (Exception e) {
            return failMedia("Connect LCP: 0 - erreur",
                    "ERR_CONNECT_LCP", "api_connectLcp", e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // DELIVERY A / C (inchangé)
    // ---------------------------------------------------------

    @Override
    public ApiResult api_deliveryAlignA() {
        try {
            delivery.alignOrRecover();
            return ApiResult.okLevel("Align A: 1 - OK", "DELIVERY", "api_deliveryAlignA");
        } catch (Exception e) {
            return ApiResult.failLevel("Align A: 0 - FAILED",
                    "ALIGN_FAILED", "DELIVERY", "api_deliveryAlignA", e.getMessage());
        }
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec,
                                       Integer from_dec,
                                       String media,
                                       String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode_dec, from_dec, media, bt_mac);
        if (c == null || c.code != 0) return c;
        return api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        try {
            delivery.startDelivery(product1to16, presetNet);
            return ApiResult.okLevel("Start C: 1 - OK", "DELIVERY", "api_deliveryStartC");
        } catch (Exception e) {
            return ApiResult.failLevel("Start C: 0 - FAILED",
                    "START_FAILED", "DELIVERY", "api_deliveryStartC", e.getMessage());
        }
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec,
                                       Integer from_dec,
                                       int product1to16,
                                       double presetNet,
                                       String media,
                                       String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode_dec, from_dec, media, bt_mac);
        if (c == null || c.code != 0) return c;
        return api_deliveryStartC(product1to16, presetNet);
    }

    // ---------------------------------------------------------
    // JOB / DB / REGISTER (inchangé)
    // ---------------------------------------------------------

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
            return ApiResult.fail("RegisterValidate failed",
                    "REGISTER_VALIDATE_FAILED");
        }
    }
}
