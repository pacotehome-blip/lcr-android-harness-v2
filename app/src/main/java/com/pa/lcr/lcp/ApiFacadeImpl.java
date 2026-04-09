
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ApiFacadeImpl — FINAL ABSOLU
 *
 * - Multi-BT réel (tous les BT READY)
 * - CONNECTER le BT avant validation du registre
 * - STOP immédiat dès qu’un registre est trouvé
 * - Fallback USB
 * - Toutes les signatures ApiFacade implémentées
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;
    private static final int PROBE_SERIAL_TIMEOUT_MS = 700;

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // USB / DB globals
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
        return ApiResult.fail("DB dump not supported here", "DB_DUMP_NOT_SUPPORTED");
    }

    // =========================================================
    // Media check
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        String m = normMedia(media, "usb");

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MediaCheck: MTM null", "ERR_MEDIA_MTM_NULL");
        }

        if ("usb".equals(m)) {
            TransportIo io = mtm.getByKey(MediaTransportManager.KEY_USB);
            return (io != null && io.isOpen())
                    ? ApiResult.ok("MediaCheck: usb OK", null)
                    : ApiResult.fail("USB not ready", "ERR_USB_NOT_READY");
        }

        if ("bt".equals(m)) {
            for (TransportSnapshot s : mtm.listSnapshots()) {
                if (s != null && s.key != null &&
                    s.key.startsWith("BT:") &&
                    s.status == TransportStatus.READY) {
                    return ApiResult.ok("MediaCheck: bt OK", null);
                }
            }
            return ApiResult.fail("BT not ready", "ERR_BT_NOT_READY");
        }

        return ApiResult.fail("Invalid media", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // LCP connect
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
    public ApiResult api_connectLcp(Integer node, Integer from, String media, String bt) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media,
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_connectLcp();
    }

    // =========================================================
    // Align A
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        return api_deliveryAlignA(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer n, Integer f) {
        return api_deliveryAlignA(n, f, "auto", "");
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer n, Integer f, String m, String b) {
        DeliveryController dc = selectController(
                n != null ? n : DEFAULT_NODE,
                f != null ? f : DEFAULT_FROM,
                m,
                rsm.getExpectedSerial(n != null ? n : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryAlignA();
    }

    // =========================================================
    // Delivery C
    // =========================================================

    @Override
    public ApiResult api_deliveryStartC(int p, double v) {
        return api_deliveryStartC(DEFAULT_NODE, DEFAULT_FROM, p, v, "auto", "");
    }

    @Override
    public ApiResult api_deliveryStartC(Integer n, Integer f, int p, double v, String m, String b) {
        DeliveryController dc = selectController(
                n != null ? n : DEFAULT_NODE,
                f != null ? f : DEFAULT_FROM,
                m,
                rsm.getExpectedSerial(n != null ? n : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryStartC(p, v);
    }

    // =========================================================
    // OneShot
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(String num, int p, double v, String c) {
        return api_deliveryOneShotStart(DEFAULT_NODE, DEFAULT_FROM, num, p, v, c, "auto", "");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer n, Integer f, String num, int p, double v, String c, String m, String b) {
        DeliveryController dc = selectController(
                n != null ? n : DEFAULT_NODE,
                f != null ? f : DEFAULT_FROM,
                m,
                rsm.getExpectedSerial(n != null ? n : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryOneShotStart(num, p, v, c);
    }

    // =========================================================
    // Job / Continue / Terminate
    // =========================================================

    @Override public ApiResult api_deliveryJobGet(String j) {
        return api_deliveryJobGet(j, DEFAULT_NODE);
    }

    @Override public ApiResult api_deliveryJobGet(String j, Integer n) {
        DeliveryController dc = selectcontrollerForJob(n);
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryJobGet(j);
    }

    @Override public ApiResult api_deliveryContinue(String j) {
        return api_deliveryContinue(j, DEFAULT_NODE);
    }

    @Override public ApiResult api_deliveryContinue(String j, Integer n) {
        DeliveryController dc = selectcontrollerForJob(n);
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryContinue(j);
    }

    @Override public ApiResult api_deliveryTerminate(String j) {
        return api_deliveryTerminate(j, DEFAULT_NODE);
    }

    @Override public ApiResult api_deliveryTerminate(String j, Integer n) {
        DeliveryController dc = selectcontrollerForJob(n);
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryTerminate(j);
    }

    // =========================================================
    // Register validate
    // =========================================================

    @Override
    public ApiResult api_registerValidate(String num, Integer n, String s, Integer p, String c) {
        return api_registerValidate(num, n, DEFAULT_FROM, s, p, c, "auto", "");
    }

    @Override
    public ApiResult api_registerValidate(String num, Integer n, Integer f, String s, Integer p, String c, String m, String b) {
        if (s != null) rsm.bindExpectedSerial(n != null ? n : DEFAULT_NODE, s);
        DeliveryController dc = selectController(
                n != null ? n : DEFAULT_NODE,
                f != null ? f : DEFAULT_FROM,
                m,
                s
        );
        if (dc == null) return ApiResult.fail("No register found", "ERR_NO_REGISTER_FOUND");
        return dc.api_registerValidate(num, n, s, p, c);
    }

    // =========================================================
    // Core orchestration (BT connect → validate → STOP)
    // =========================================================

    private DeliveryController selectController(int node, int from, String media, String expectedSerial) {

        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        // === BT FIRST =====================================================
        if (!"usb".equals(normMedia(media, "auto"))) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {
                if (snap == null || snap.key == null) continue;
                if (!snap.key.startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;

                mtm.activateExclusive(snap.key, "API_BT_SCAN");

                TransportIo io = mtm.getByKey(snap.key);
                if (io == null || !io.isOpen()) continue;

                DeliveryController dc = rsm.getOrCreate(io.getKey(), node, from, io);
                if (dc == null) continue;

                // ✅ CONNECTER LCP D’ABORD
                ApiResult cr = dc.api_connectLcp();
                if (cr == null || cr.code == 0) continue;

                // ✅ PUIS VALIDER LE REGISTRE
                String serial = probeSerial80(io, node, from);
                if (serial == null || (expectedSerial != null && !expectedSerial.equals(serial))) continue;

                dc.setActiveMedia("bt");
                return dc; // ✅ STOP IMMÉDIAT
            }
        }

        // === USB ==========================================================
        TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
        if (usb != null && usb.isOpen()) {

            DeliveryController dc = rsm.getOrCreate(usb.getKey(), node, from, usb);
            if (dc != null) {

                String serial = probeSerial80(usb, node, from);
                if (serial != null && (expectedSerial == null || expectedSerial.equals(serial))) {
                    dc.setActiveMedia("usb");
                    return dc;
                }
            }
        }

        return null;
    }

    private DeliveryController selectcontrollerForJob(Integer n) {
        int node = n != null ? n : DEFAULT_NODE;
        return selectController(node, DEFAULT_FROM, "auto", rsm.getExpectedSerial(node));
    }

    private String probeSerial80(TransportIo io, int node, int from) {
        try {
            LcpLink link = new LcpLink(io, node, from, true);
            byte[] b = link.opGetField(80, PROBE_SERIAL_TIMEOUT_MS);
            if (b == null || b.length == 0) return null;
            return new String(b, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }

    private MediaTransportManager getMtm() {
        try {
            Context ctx = rsm.getAppContext();
            return ctx != null ? MediaTransportManager.get(ctx) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normMedia(String m, String def) {
        return (m == null || m.trim().isEmpty()) ? def : m.toLowerCase(Locale.ROOT);
    }
}
