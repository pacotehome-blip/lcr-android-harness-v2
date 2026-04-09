
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import java.util.Locale;

/**
 * ApiFacadeImpl — AUTOMATISATION MINIMALE ET SAINE
 *
 * Objectif UNIQUE :
 * - automatiser ce que le bouton BT fait manuellement
 * - NE RIEN CHANGER au reste du flux
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // LCP CONNECT — AUTOMATISE L’ACTIVATION BT SI NÉCESSAIRE
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

        // 1) Si aucun média actif → activer un BT comme l’UI
        String activeKey = MediaTransportManager.getActiveKeyStatic();
        if (activeKey == null || !activeKey.startsWith("BT:")) {
            activateFirstBt(mtm);
            activeKey = MediaTransportManager.getActiveKeyStatic();
        }

        if (activeKey == null) {
            return ApiResult.fail("No active media", "ERR_NO_ACTIVE_MEDIA");
        }

        TransportIo io = mtm.getByKey(activeKey);
        if (io == null || !io.isOpen()) {
            return ApiResult.fail("Active media not open", "ERR_MEDIA_NOT_OPEN");
        }

        DeliveryController dc = rsm.getOrCreate(
                activeKey,
                node != null ? node : DEFAULT_NODE,
                from != null ? from : DEFAULT_FROM,
                io
        );

        if (dc == null) {
            return ApiResult.fail("No controller", "ERR_NO_CONTROLLER");
        }

        // 2) LCP CONNECT COMME AVANT
        return dc.api_connectLcp();
    }

    // =========================================================
    // AUTOMATISATION DU BOUTON BT (ET RIEN D’AUTRE)
    // =========================================================

    private void activateFirstBt(MediaTransportManager mtm) {
        for (TransportSnapshot snap : mtm.listSnapshots()) {
            if (snap == null || snap.key == null) continue;
            if (!snap.key.startsWith("BT:")) continue;
            if (snap.status != TransportStatus.READY) continue;

            // EXACTEMENT comme le bouton BT de l’UI
            mtm.activateExclusive(snap.key, "API_BT_AUTO");
            return;
        }
    }

    // =========================================================
    // AUTRES APIS — INCHANGÉES
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

    private static String normMedia(String m, String def) {
        return (m == null || m.trim().isEmpty())
                ? def
                : m.toLowerCase(Locale.ROOT);
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
}
``
