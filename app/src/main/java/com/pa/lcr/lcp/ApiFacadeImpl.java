
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
 * - Essaye TOUS les BT READY (un par un)
 * - STOP immédiat dès qu’un registre est trouvé
 * - Ensuite USB
 * - STOP immédiat si USB valide
 * - Échec final seulement si rien trouvé
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
    // USB (global)
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail("USB Scan: not handled here", "USB_SCAN_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail("USB OpenPing: not handled here", "USB_OPENPING_NOT_SUPPORTED");
    }

    // =========================================================
    // Media check (diagnostic)
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
            if (io != null && io.isOpen()) {
                return ApiResult.ok("MediaCheck: usb OK", null);
            }
            return ApiResult.fail("MediaCheck: usb not ready", "ERR_USB_NOT_READY");
        }

        if ("bt".equals(m)) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {
                if (snap == null || snap.key == null) continue;
                if (!snap.key.toUpperCase(Locale.ROOT).startsWith("BT:")) continue;
                if (snap.status == TransportStatus.READY) {
                    return ApiResult.ok("MediaCheck: bt OK", null);
                }
            }
            return ApiResult.fail("MediaCheck: bt not ready", "ERR_BT_NOT_READY");
        }

        return ApiResult.fail("MediaCheck: invalid media", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // LCP connect
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        return api_connectLcp(lcrnode_dec, from_dec, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                   Integer from_dec,
                                   String media,
                                   String bt_mac) {

        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc =
                selectController(node, from, media, rsm.getExpectedSerial(node));

        if (dc == null) {
            return ApiResult.fail("Connect: aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_connectLcp();
        } catch (Exception e) {
            return ApiResult.fail("Connect: erreur", "ERR_CONNECT", errDetail(e, dc));
        }
    }

    // =========================================================
    // Align A
    // =========================================================

    @Override public ApiResult api_deliveryAlignA() {
        return api_deliveryAlignA(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override public ApiResult api_deliveryAlignA(Integer node, Integer from) {
        return api_deliveryAlignA(node, from, "auto", "");
    }

    @Override public ApiResult api_deliveryAlignA(Integer node, Integer from, String media, String bt) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media,
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("AlignA: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryAlignA();
    }

    // =========================================================
    // Delivery C
    // =========================================================

    @Override
    public ApiResult api_deliveryStartC(int prod, double preset) {
        return api_deliveryStartC(DEFAULT_NODE, DEFAULT_FROM, prod, preset, "auto", "");
    }

    @Override
    public ApiResult api_deliveryStartC(Integer node, Integer from, int prod, double preset, String media, String bt) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media,
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("DeliveryC: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryStartC(prod, preset);
    }

    // =========================================================
    // OneShot
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(String num, int prod, double preset, String comp) {
        return api_deliveryOneShotStart(DEFAULT_NODE, DEFAULT_FROM, num, prod, preset, comp, "auto", "");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer node, Integer from, String num, int prod, double preset, String comp, String media, String bt) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media,
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("OneShot: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryOneShotStart(num, prod, preset, comp);
    }

    // =========================================================
    // JobGet
    // =========================================================

    @Override
    public ApiResult api_deliveryJobGet(String jobId) {
        return api_deliveryJobGet(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer node) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                DEFAULT_FROM,
                "auto",
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("JobGet: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryJobGet(jobId);
    }

    // =========================================================
    // Continue
    // =========================================================

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        return api_deliveryContinue(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer node) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                DEFAULT_FROM,
                "auto",
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("Continue: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryContinue(jobId);
    }

    // =========================================================
    // Terminate
    // =========================================================

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        return api_deliveryTerminate(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer node) {
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                DEFAULT_FROM,
                "auto",
                rsm.getExpectedSerial(node != null ? node : DEFAULT_NODE)
        );
        if (dc == null) return ApiResult.fail("Terminate: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_deliveryTerminate(jobId);
    }

    // =========================================================
    // Register validate
    // =========================================================

    @Override
    public ApiResult api_registerValidate(String num, Integer node, String serial, Integer prod, String comp) {
        return api_registerValidate(num, node, DEFAULT_FROM, serial, prod, comp, "auto", "");
    }

    @Override
    public ApiResult api_registerValidate(String num, Integer node, Integer from, String serial, Integer prod, String comp, String media, String bt) {
        if (serial != null) rsm.bindExpectedSerial(node != null ? node : DEFAULT_NODE, serial);
        DeliveryController dc = selectController(
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                media,
                serial
        );
        if (dc == null) return ApiResult.fail("Validate: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        return dc.api_registerValidate(num, node, serial, prod, comp);
    }

    // =========================================================
    // Orchestration centrale
    // =========================================================

    private DeliveryController selectController(int node, int from, String media, String expectedSerial) {

        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        if (!"usb".equals(normMedia(media, "auto"))) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {
                if (snap == null || snap.key == null) continue;
                if (!snap.key.startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;

                mtm.activateExclusive(snap.key, "API_BT_SCAN");

                TransportIo io = mtm.getByKey(snap.key);
                if (io == null || !io.isOpen()) continue;

                String s = probeSerial80(io, node, from);
                if (s == null || (expectedSerial != null && !expectedSerial.equals(s))) continue;

                return rsm.getOrCreate(io.getKey(), node, from, io);
            }
        }

        TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
        if (usb != null && usb.isOpen()) {
            String s = probeSerial80(usb, node, from);
            if (s != null && (expectedSerial == null || expectedSerial.equals(s))) {
                return rsm.getOrCreate(usb.getKey(), node, from, usb);
            }
        }
        return null;
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

    private JSONObject errDetail(Exception e, DeliveryController dc) {
        JSONObject d = new JSONObject();
        try {
            d.put("detail", e.getMessage());
            d.put("transport_key", rsm.findTransportKeyForController(dc));
        } catch (Exception ignored) {}
        return d;
    }
}
