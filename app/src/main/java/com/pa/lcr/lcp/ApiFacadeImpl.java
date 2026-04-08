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
 * - BT sans bt_mac: si bt_mac absent, on utilise le transport BT ACTIF (MediaTransportManager.activeKey).
 * - Session LCP pinnée sur le transport choisi via RegisterSessionManager.getOrCreate(transportKey,node,from,io).
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager sessionMgr;
    private final DeliveryController delivery;
    private final Context appCtx;

    public ApiFacadeImpl(RegisterSessionManager sessionMgr, DeliveryController delivery) {
        this.sessionMgr = sessionMgr;
        this.delivery = delivery;
        // Requiert getAppContext() dans RegisterSessionManager (petit ajout non-breaking)
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

    private TransportIo usbIo(MediaTransportManager mtm) {
        if (mtm == null) return null;
        return mtm.getByKey(MediaTransportManager.KEY_USB);
    }

    private TransportIo btIo(MediaTransportManager mtm, String btMac) {
        if (mtm == null) return null;
        String key = resolveBtKeyFromMacOrActive(btMac);
        if (key == null) return null;
        return mtm.getByKey(key);
    }

    /**
     * Sélectionne un transport selon media demandé:
     * - usb: exige USB prêt
     * - bt: exige BT prêt (bt_mac optionnel -> BT actif)
     * - auto: USB si prêt sinon BT
     */
    private TransportIo resolveIoOrThrow(String media, String btMac) {
        MediaTransportManager mtm = mtm();
        String m = normLower(media);
        if (m.isEmpty()) m = "auto";

        if ("usb".equals(m)) {
            TransportIo io = usbIo(mtm);
            if (io == null) throw new IllegalStateException("USB_NOT_READY");
            return io;
        }
        if ("bt".equals(m)) {
            TransportIo io = btIo(mtm, btMac);
            if (io == null) throw new IllegalStateException("BT_NOT_READY");
            return io;
        }

        // auto
        TransportIo u = usbIo(mtm);
        if (u != null) return u;
        TransportIo b = btIo(mtm, btMac);
        if (b != null) return b;
        throw new IllegalStateException("NO_MEDIA_READY");
    }

    private boolean activateExclusive(TransportIo io, String reason) {
        if (io == null) return false;
        MediaTransportManager mtm = mtm();
        if (mtm == null) return false;
        return mtm.activateExclusive(io.getKey(), (reason != null ? reason : "API"));
    }

    private static String mediaOfKey(String key) {
        if (key == null) return "unknown";
        if (MediaTransportManager.KEY_USB.equals(key)) return "usb";
        if (key.toUpperCase(Locale.ROOT).startsWith("BT:")) return "bt";
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
    // USB (global)
    // ---------------------------------------------------------

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

    // ---------------------------------------------------------
    // Media check (USB/BT)  ✅ BT sans bt_mac supporté
    // ---------------------------------------------------------

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        try {
            TransportIo io = resolveIoOrThrow(media, bt_mac);
            return okWithTransport("MediaCheck: 1 - connecté", "api_mediaCheck", io);
        } catch (IllegalStateException ise) {
            String code = ise.getMessage();
            if ("USB_NOT_READY".equals(code)) {
                return failMedia("MediaCheck: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", "api_mediaCheck", "usb not ready");
            }
            if ("BT_NOT_READY".equals(code)) {
                // Distinguish missing mac vs not connected
                String key = resolveBtKeyFromMacOrActive(bt_mac);
                if (key == null) {
                    return failMedia("MediaCheck: 0 - BT mac manquant et aucun BT actif", "ERR_BT_MAC_REQUIRED", "api_mediaCheck", "bt_mac missing and no active BT");
                }
                return failMedia("MediaCheck: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", "api_mediaCheck", "bt not ready");
            }
            return failMedia("MediaCheck: 0 - Aucun média prêt", "ERR_NO_MEDIA_READY", "api_mediaCheck", code);
        } catch (Exception e) {
            return failMedia("MediaCheck: 0 - erreur", "ERR_MEDIA_CHECK", "api_mediaCheck", e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // LCP CONNECT
    // ---------------------------------------------------------

    @Override
    public ApiResult api_connectLcp() {
        // legacy: auto
        return api_connectLcp(250, 255, "auto", null);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = (lcrnode_dec != null) ? (lcrnode_dec & 0xFF) : 250;
        int from = (from_dec != null) ? (from_dec & 0xFF) : 255;
        try {
            TransportIo io = resolveIoOrThrow(media, bt_mac);
            if (!activateExclusive(io, "API_CONNECT_LCP")) {
                return failMedia("Connect LCP: 0 - Transport non prêt", "ERR_TRANSPORT_NOT_READY", "api_connectLcp", io.getKey());
            }

            DeliveryController dc = sessionMgr.getOrCreate(io.getKey(), node, from, io);
            if (dc == null) {
                return failMedia("Connect LCP: 0 - Session non créée", "ERR_SESSION_CREATE", "api_connectLcp", io.getKey());
            }

            return okWithTransport("Connect: 1 - OK", "api_connectLcp", io);

        } catch (IllegalStateException ise) {
            String code = ise.getMessage();
            if ("USB_NOT_READY".equals(code)) {
                return failMedia("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY", "api_connectLcp", "usb not ready");
            }
            if ("BT_NOT_READY".equals(code)) {
                String key = resolveBtKeyFromMacOrActive(bt_mac);
                if (key == null) {
                    return failMedia("Connect LCP: 0 - BT mac manquant et aucun BT actif", "ERR_BT_MAC_REQUIRED", "api_connectLcp", "bt_mac missing and no active BT");
                }
                return failMedia("Connect LCP: 0 - BT non prêt.", "ERR_BT_NOT_READY", "api_connectLcp", "bt not ready");
            }
            return failMedia("Connect LCP: 0 - Aucun média prêt.", "ERR_NO_MEDIA_READY", "api_connectLcp", code);
        } catch (Exception e) {
            return failMedia("Connect LCP: 0 - Erreur.", "ERR_CONNECT", "api_connectLcp", e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // DELIVERY A (legacy + media-aware override)
    // ---------------------------------------------------------

    @Override
    public ApiResult api_deliveryAlignA() {
        // legacy: on exécute A sur le controller déjà connecté (UI-like)
        try {
            delivery.alignOrRecover();
            return ApiResult.okLevel("Align A: 1 - OK", "DELIVERY", "api_deliveryAlignA");
        } catch (Exception e) {
            return ApiResult.failLevel("Align A: 0 - FAILED", "ALIGN_FAILED", "DELIVERY", "api_deliveryAlignA", e.getMessage());
        }
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        // assure transport/session puis délègue au delivery
        ApiResult c = api_connectLcp(lcrnode_dec, from_dec, media, bt_mac);
        if (c == null || c.code == 0) return c;
        return api_deliveryAlignA();
    }

    // ---------------------------------------------------------
    // DELIVERY C (legacy + media-aware override)
    // ---------------------------------------------------------

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        try {
            delivery.startDelivery(product1to16, presetNet);
            return ApiResult.okLevel("Start C: 1 - OK", "DELIVERY", "api_deliveryStartC");
        } catch (Exception e) {
            return ApiResult.failLevel("Start C: 0 - FAILED", "START_FAILED", "DELIVERY", "api_deliveryStartC", e.getMessage());
        }
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet, String media, String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode_dec, from_dec, media, bt_mac);
        if (c == null || c.code == 0) return c;
        return api_deliveryStartC(product1to16, presetNet);
    }

    // ---------------------------------------------------------
    // JOB / ONE SHOT / CONTINUE / TERMINATE
    // ---------------------------------------------------------

    @Override
    public ApiResult api_deliveryJobGet(String jobId) {
        return ApiResult.fail("Job get not handled here", "JOB_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        return ApiResult.fail("Continue not supported", "CONTINUE_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        return ApiResult.fail("Terminate not supported", "TERMINATE_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return ApiResult.fail("OneShot not supported", "ONESHOT_NOT_SUPPORTED");
    }

    // ---------------------------------------------------------
    // DB
    // ---------------------------------------------------------

    @Override
    public ApiResult api_dbDump() {
        return ApiResult.fail("DB Dump handled by ApiServer", "DB_DUMP_NOT_SUPPORTED");
    }

    // ---------------------------------------------------------
    // REGISTER VALIDATE
    // ---------------------------------------------------------

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
