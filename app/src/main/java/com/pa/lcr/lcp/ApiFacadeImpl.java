
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ApiFacadeImpl — MODE "UNE COMMANDE"
 *
 * /lcp/connect fait :
 *   - activation BT
 *   - connect LCP
 *   - scan registre
 *   - lecture serial #80
 *   - STOP dès succès
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
    // CONNECT = TOUT LE PARCOURS
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer nodeIn, Integer fromIn) {
        return api_connectLcp(nodeIn, fromIn, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer nodeIn, Integer fromIn, String media, String bt) {

        int node = nodeIn != null ? nodeIn : DEFAULT_NODE;
        int from = fromIn != null ? fromIn : DEFAULT_FROM;

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        }

        String m = normMedia(media, "auto");

        // =============================
        // BT D’ABORD (MODE AUTO / BT)
        // =============================
        if (!"usb".equals(m)) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {

                if (snap == null || snap.key == null) continue;
                if (!snap.key.startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;

                // 1️⃣ activer BT (comme UI)
                mtm.activateExclusive(snap.key, "API_BT_AUTO");

                TransportIo io = mtm.getByKey(snap.key);
                if (io == null || !io.isOpen()) continue;

                // 2️⃣ créer controller
                DeliveryController dc =
                        rsm.getOrCreate(io.getKey(), node, from, io);
                if (dc == null) continue;

                // 3️⃣ connect LCP
                ApiResult cr = dc.api_connectLcp();
                if (cr == null || cr.code == 0) continue;

                // 4️⃣ lire serial (#80)
                String serial = readSerial80(io, node, from);
                if (serial == null) continue;

                // ✅ SUCCÈS TOTAL
                dc.setActiveMedia("bt");
                return ApiResult.ok(
                        "CONNECTED BT serial=" + serial,
                        null
                );
            }
        }

        // =============================
        // USB (EN DERNIER)
        // =============================
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
                        return ApiResult.ok(
                                "CONNECTED USB serial=" + serial,
                                null
                        );
                    }
                }
            }
        }

        return ApiResult.fail(
                "No register found on BT or USB",
                "ERR_NO_REGISTER_FOUND"
        );
    }

    // =========================================================
    // UTILS
    // =========================================================

    private String readSerial80(TransportIo io, int node, int from) {
        try {
            LcpLink link = new LcpLink(io, node, from, true);
            byte[] b = link.opGetField(80, PROBE_SERIAL_TIMEOUT_MS);
            if (b == null || b.length == 0) return null;

            String s = new String(b, StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .replace("\r", "")
                    .replace("\n", "")
                    .trim();

            return s.isEmpty() ? null : s;
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
        if (m == null || m.trim().isEmpty()) return def;
        return m.toLowerCase(Locale.ROOT);
    }

    // =========================================================
    // Le reste est inchangé / passthrough
    // =========================================================

    @Override public ApiResult api_mediaCheck(String m, String b) {
        return ApiResult.fail("Use connect", "USE_CONNECT");
    }

    @Override public ApiResult api_scanUsb() {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override public ApiResult api_openPingUsb() {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override public ApiResult api_dbDump() {
        return ApiResult.fail("Not supported", "NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryAlignA() {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryAlignA()
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override public ApiResult api_deliveryStartC(int p, double v) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryStartC(p, v)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryOneShotStart(n, p, v, c)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override public ApiResult api_deliveryJobGet(String j) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryJobGet(j)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override public ApiResult api_deliveryContinue(String j) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryContinue(j)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override public ApiResult api_deliveryTerminate(String j) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null ? dc.api_deliveryTerminate(j)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_registerValidate(String num, Integer n, String s, Integer p, String c) {
        DeliveryController dc = rsm.getActiveController();
        return dc != null
                ? dc.api_registerValidate(num, n, s, p, c)
                : ApiResult.fail("No active", "ERR_NO_ACTIVE_MEDIA");
    }
}
