package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;
import com.pa.lcr.lcp.transport.MediaTransportManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // BT ACTIVATE
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
        try {
            activeKey = MediaTransportManager.getActiveKeyStatic();
        } catch (Exception ignored) {}

        JSONObject d = new JSONObject();
        try { d.put("transportKey", chosen.key); } catch (Exception ignored) {}
        try { d.put("activeKey", activeKey != null ? activeKey : JSONObject.NULL); } catch (Exception ignored) {}

        return ApiResult.ok("BT activate: OK", d);
    }

    // =========================================================
    // LCP CONNECT
    // =========================================================

@Override
public ApiResult api_registerConnectAuto(String serialId, Integer lcrnode) {
    return ApiResult.fail(
        "registerConnectAuto: 0 - Not supported (mono-registre)",
        "NOT_SUPPORTED"
    );
}

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
    // DELIVERY STUBS
    // =========================================================

@Override
public ApiResult api_deliveryAlignA() {
    return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
}

    @Override
    public ApiResult api_deliveryStartC(int p, double v) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(String n, int p, double v, String c) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryJobGet(String j) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryContinue(String j) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_deliveryTerminate(String j) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    @Override
    public ApiResult api_registerValidate(String n, Integer d, String s, Integer p, String c) {
        return ApiResult.fail("Call after connect", "NO_ACTIVE_MEDIA");
    }

    // =========================================================
    // OTHER STUBS
    // =========================================================

    @Override
    public ApiResult api_ticketReprintCurrent() {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override
    public ApiResult api_mediaCheck(String m, String b) {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail("Not used", "NOT_USED");
    }

    @Override
    public ApiResult api_dbDump() {
        return ApiResult.fail("Not supported", "NOT_SUPPORTED");
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
