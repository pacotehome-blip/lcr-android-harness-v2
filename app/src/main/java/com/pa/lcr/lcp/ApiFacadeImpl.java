
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ApiFacadeImpl — COPY/PASTE TESTABLE
 *
 * TEST INTÉGRÉ :
 *  - Si ce fichier est exécuté, la réponse contiendra :
 *    "API_FACADE_IMPL_HIT"
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;
    private static final int PROBE_SERIAL_TIMEOUT_MS = 700;

    private final RegisterSessionManager rsm;

    // État local (pas de méthode inventée dans RSM)
    private volatile DeliveryController activeController;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // CONNECT — UNE COMMANDE BOUT-EN-BOUT + TEST
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer n, Integer f) {
        return api_connectLcp(n, f, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer nodeIn, Integer fromIn, String media, String bt) {

        int node = nodeIn != null ? nodeIn : DEFAULT_NODE;
        int from = fromIn != null ? fromIn : DEFAULT_FROM;

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("API_FACADE_IMPL_HIT - MTM null", "ERR_MEDIA_MTM_NULL");
        }

        StringBuilder trace = new StringBuilder();
        trace.append("API_FACADE_IMPL_HIT | ");

        // ==== BT d’abord ====
        for (TransportSnapshot snap : mtm.listSnapshots()) {

            if (snap == null || snap.key == null) continue;
            if (!snap.key.startsWith("BT:")) continue;
            if (snap.status != TransportStatus.READY) continue;

            trace.append("TRY ").append(snap.key).append(" | ");

            mtm.activateExclusive(snap.key, "API_BT_AUTO");

            TransportIo io = mtm.getByKey(snap.key);
            if (io == null || !io.isOpen()) continue;

            DeliveryController dc =
                    rsm.getOrCreate(io.getKey(), node, from, io);
            if (dc == null) continue;

            ApiResult cr = dc.api_connectLcp();
            if (cr == null || cr.code == 0) continue;

            String serial = readSerial80(io, node, from);
            if (serial == null) continue;

            dc.setActiveMedia("bt");
            activeController = dc;

            return ApiResult.ok(
                    trace + "SUCCESS BT serial=" + serial,
                    null
            );
        }

        // ==== USB en dernier ====
        TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
        if (usb != null && usb.isOpen()) {

            DeliveryController dc =
                    rsm.getOrCreate(usb.getKey(), node, from, usb);

            if (dc != null) {
                ApiResult cr = dc.api_connectLcp();
                if (cr != null && cr.code == 1) {
                    String serial = readSerial80(usb, node, from);
                    if (serial != null) {
                        dc.setActiveMedia("usb");
                        activeController = dc;
                        return ApiResult.ok(
                                trace + "SUCCESS USB serial=" + serial,
                                null
                        );
                    }
                }
            }
        }

        return ApiResult.fail(
                trace + "NO_REGISTER_FOUND",
                "ERR_NO_REGISTER_FOUND"
        );
    }

    // =========================================================
    // APIs DÉPENDANTES DU CONTROLLER ACTIF
    // =========================================================

    private DeliveryController requireActive() {
        return activeController;
    }

    @Override
    public ApiResult api_deliveryAlignA() {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryAlignA()
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryStartC(int p, double v) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryStartC(p, v)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryOneShotStart(n, p, v, c)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryJobGet(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryJobGet(j)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryContinue(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryContinue(j)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    // ✅ MÉTHODE MANQUANTE — CAUSE DU BUILD FAIL
    @Override
    public ApiResult api_deliveryTerminate(String j) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_deliveryTerminate(j)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_registerValidate(String num, Integer n, String s, Integer p, String c) {
        DeliveryController dc = requireActive();
        return dc != null
                ? dc.api_registerValidate(num, n, s, p, c)
                : ApiResult.fail("API_FACADE_IMPL_HIT - no active", "ERR_NO_ACTIVE_MEDIA");
    }

    // =========================================================
    // UTILS
    // =========================================================

    private String readSerial80(TransportIo io, int node, int from) {
        try {
            LcpLink link = new LcpLink(io, node, from, true);
            byte[] b = link.opGetField(80, PROBE_SERIAL_TIMEOUT_MS);
            if (b == null || b.length == 0) return null;
            return new String(b, StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();
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
        return (m == null || m.trim().isEmpty())
                ? def
                : m.toLowerCase(Locale.ROOT);
    }

    // =========================================================
    // NON UTILISÉS
    // =========================================================

    @Override public ApiResult api_mediaCheck(String m, String b) {
        return ApiResult.fail("API_FACADE_IMPL_HIT - use connect", "USE_CONNECT");
    }

    @Override public ApiResult api_scanUsb() {
        return ApiResult.fail("API_FACADE_IMPL_HIT - not supported", "NOT_SUPPORTED");
    }

    @Override public ApiResult api_openPingUsb() {
        return ApiResult.fail("API_FACADE_IMPL_HIT - not supported", "NOT_SUPPORTED");
    }

    @Override public ApiResult api_dbDump() {
        return ApiResult.fail("API_FACADE_IMPL_HIT - not supported", "NOT_SUPPORTED");
    }
}
