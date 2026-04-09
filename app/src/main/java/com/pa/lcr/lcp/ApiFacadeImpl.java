
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import java.util.Locale;

/**
 * ApiFacadeImpl — VERSION SIMPLE ET SAINE
 *
 * RÈGLE :
 * - Détecter les BT
 * - En activer UN (comme le bouton UI)
 * - Laisser /lcp/connect faire son travail normal
 *
 * AUCUN scan registre ici
 * AUCUN LCP ici
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // Global (non gérés ici)
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail("USB Scan not supported", "USB_SCAN_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail("USB OpenPing not supported", "USB_OPENPING_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_dbDump() {
        return ApiResult.fail("DB Dump not supported", "DB_DUMP_NOT_SUPPORTED");
    }

    // =========================================================
    // Media check (diagnostic)
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MediaCheck: MTM null", "ERR_MEDIA_MTM_NULL");
        }

        String m = normMedia(media, "usb");

        if ("bt".equals(m)) {
            for (TransportSnapshot s : mtm.listSnapshots()) {
                if (s != null &&
                    s.key != null &&
                    s.key.startsWith("BT:") &&
                    s.status == TransportStatus.READY) {
                    return ApiResult.ok("BT available", null);
                }
            }
            return ApiResult.fail("BT not ready", "ERR_BT_NOT_READY");
        }

        if ("usb".equals(m)) {
            TransportIo io = mtm.getByKey(MediaTransportManager.KEY_USB);
            return (io != null && io.isOpen())
                    ? ApiResult.ok("USB available", null)
                    : ApiResult.fail("USB not ready", "ERR_USB_NOT_READY");
        }

        return ApiResult.fail("Invalid media", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // LCP CONNECT — COMME AVANT
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer node, Integer from) {
        return api_connectLcp(node, from, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer node,
                                   Integer from,
                                   String media,
                                   String bt) {

        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media
        );

        if (dc == null) {
            return ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
        }

        // ✅ COMME AVANT : ON LAISSE LE CONTROLLER FAIRE LCP CONNECT
        return dc.api_connectLcp();
    }

    // =========================================================
    // Align / Delivery / Job — inchangés
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryAlignA()
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryStartC(int p, double v) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryStartC(p, v)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryOneShotStart(n, p, v, c)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryJobGet(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryJobGet(j)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryContinue(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryContinue(j)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryTerminate(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryTerminate(j)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_registerValidate(String num,
                                         Integer n,
                                         String s,
                                         Integer p,
                                         String c) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_registerValidate(num, n, s, p, c)
                : ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
    }

    // =========================================================
    // ✅ CŒUR : ACTIVATION BT UNIQUEMENT
    // =========================================================

    private DeliveryController selectController(int node,
                                               int from,
                                               String media) {

        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        String m = normMedia(media, "auto");

        // === BT D’ABORD (COMME UI) =============================
        if (!"usb".equals(m)) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {

                if (snap == null || snap.key == null) continue;
                if (!snap.key.startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;

                // ✅ EXACTEMENT comme le bouton BT
                mtm.activateExclusive(snap.key, "API_BT_SELECT");

                TransportIo io = mtm.getByKey(snap.key);
                if (io == null || !io.isOpen()) return null;

                return rsm.getOrCreate(io.getKey(), node, from, io);
            }
        }

        // === USB SEULEMENT SI DEMANDÉ =========================
        if ("usb".equals(m)) {
            TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
            if (usb != null && usb.isOpen()) {
                return rsm.getOrCreate(usb.getKey(), node, from, usb);
            }
        }

        return null;
    }

    private DeliveryController requireActive() {
        return rsm.getLastController();
    }

    // =========================================================
    // Utils
    // =========================================================

    private MediaTransportManager getMtm() {
        try {
            Context ctx = rsm.getAppContext();
            return ctx != null ? MediaTransportManager.get(ctx) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normMedia(String m, String def) {
        return (m == null || m.trim().isEmpty())
                ? def
                : m.toLowerCase(Locale.ROOT);
    }
}
