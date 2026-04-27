
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;
import com.pa.lcr.lcp.transport.MediaTransportManager;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ApiFacadeImpl (mono historique) — corrigé pour déléguer au multi-média lorsque disponible.
 *
 * Objectif:
 * - Conserver le comportement existant de BT activate + connectLcp sur media actif
 * - Mais ne plus bloquer les routes multi (connect-auto, validate, delivery, etc.)
 *   => délégation vers MultiRegisterApiFacadeImpl.
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;

    private final RegisterSessionManager rsm;

    // ✅ Délégation vers la façade multi-média
    private final MultiRegisterApiFacadeImpl multi;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;

        Context ctx = null;
        try { ctx = (rsm != null) ? rsm.getAppContext() : null; } catch (Exception ignored) {}
        this.multi = (ctx != null) ? new MultiRegisterApiFacadeImpl(ctx) : null;
    }

    // =========================================================
    // BT ACTIVATE (conservé tel quel)
    // =========================================================
    @Override
    public ApiResult api_btActivate() {
        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        }

        TransportSnapshot chosen = null;
        try {
            for (TransportSnapshot snap : mtm.listSnapshots()) {
                if (snap == null) continue;
                if (snap.key == null) continue;
                if (!snap.key.startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;
                chosen = snap;
                break;
            }
        } catch (Exception e) {
            JSONObject ed = new JSONObject();
            try { ed.put("detail", e.getMessage()); } catch (Exception ignored) {}
            return ApiResult.fail("BT enumerate failed", "ERR_BT_ENUM_FAILED", ed);
        }

        if (chosen == null || chosen.key == null) {
            return ApiResult.fail("No BT READY", "ERR_NO_BT_READY");
        }

        try {
            boolean ok = mtm.activateExclusive(chosen.key, "API_BT_AUTO");
            if (!ok) {
                return ApiResult.fail("BT activate failed", "ERR_BT_ACTIVATE_FAILED");
            }
        } catch (Exception e) {
            JSONObject ed = new JSONObject();
            try { ed.put("detail", e.getMessage()); } catch (Exception ignored) {}
            return ApiResult.fail("BT activate failed", "ERR_BT_ACTIVATE_FAILED", ed);
        }

        String activeKey = null;
        try { activeKey = MediaTransportManager.getActiveKeyStatic(); } catch (Exception ignored) {}

        JSONObject d = new JSONObject();
        try { d.put("transportKey", chosen.key); } catch (Exception ignored) {}
        try { d.put("activeKey", activeKey != null ? activeKey : JSONObject.NULL); } catch (Exception ignored) {}
        return ApiResult.ok("BT activate: OK", d);
    }

    // =========================================================
    // REGISTER CONNECT-AUTO  ✅ délégation multi
    // =========================================================
    @Override
    public ApiResult api_registerConnectAuto(String serialId, Integer lcrnode) {
        if (multi == null) {
            return ApiResult.fail("registerConnectAuto: 0 - multi facade null", "ERR_MULTI_FACADE_NULL");
        }
        return multi.api_registerConnectAuto(serialId, lcrnode);
    }

    // =========================================================
    // LCP CONNECT (conservé: connect sur le media actif)
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

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MTM null", "ERR_MEDIA_MTM_NULL");
        }

        String activeKey = MediaTransportManager.getActiveKeyStatic();
        if (activeKey == null || activeKey.trim().isEmpty()) {
            return ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
        }

        TransportIo io = mtm.getByKey(activeKey);
        if (io == null || !io.isOpen()) {
            return ApiResult.fail("Active media not open", "ERR_MEDIA_NOT_OPEN");
        }

        int n = (node != null) ? node : DEFAULT_NODE;
        int f = (from != null) ? from : DEFAULT_FROM;

        DeliveryController dc = rsm.getOrCreate(activeKey, n, f, io);
        if (dc == null) {
            return ApiResult.fail("No controller", "ERR_NO_CONTROLLER");
        }
        return dc.api_connectLcp();
    }

    // =========================================================
    // DELIVERY / JOB / VALIDATE ✅ délégation multi
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(int p, double v) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryStartC(p, v);
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryOneShotStart(n, p, v, c);
    }

    @Override
    public ApiResult api_deliveryJobGet(String j) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryJobGet(j);
    }

    @Override
    public ApiResult api_deliveryContinue(String j) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryContinue(j);
    }

    @Override
    public ApiResult api_deliveryTerminate(String j) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_deliveryTerminate(j);
    }

    @Override
    public ApiResult api_registerValidate(String n, Integer d, String s, Integer p, String c) {
        if (multi == null) return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
        return multi.api_registerValidate(n, d, s, p, c);
    }

    // =========================================================
    // Autres endpoints utiles (délégation multi)
    // =========================================================
    @Override
    public ApiResult api_ticketReprintCurrent() {
        if (multi == null) return ApiResult.fail("Not used", "NOT_USED");
        return multi.api_ticketReprintCurrent();
    }

    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        if (multi == null) return ApiResult.fail("Not used", "NOT_USED");
        return multi.api_tickWait(lcrnode_dec, since_seq, wait_ms);
    }

    @Override
    public ApiResult api_mediaCheck(String m, String b) {
        if (multi == null) return ApiResult.fail("Not used", "NOT_USED");
        return multi.api_mediaCheck(m, b);
    }

    @Override
    public ApiResult api_scanUsb() {
        if (multi == null) return ApiResult.fail("Not used", "NOT_USED");
        return multi.api_scanUsb();
    }

    @Override
    public ApiResult api_openPingUsb() {
        if (multi == null) return ApiResult.fail("Not used", "NOT_USED");
        return multi.api_openPingUsb();
    }

    @Override
    public ApiResult api_dbDump() {
        if (multi == null) return ApiResult.fail("Not supported", "NOT_SUPPORTED");
        return multi.api_dbDump();
    }

    // =========================================================
    // UTILS
    // =========================================================
    private MediaTransportManager getMtm() {
        try {
            Context ctx = rsm.getAppContext();
            return ctx != null ? MediaTransportManager.get(ctx) : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static String normMedia(String m, String def) {
        return (m == null || m.trim().isEmpty())
                ? def
                : m.toLowerCase(Locale.ROOT);
    }
}
