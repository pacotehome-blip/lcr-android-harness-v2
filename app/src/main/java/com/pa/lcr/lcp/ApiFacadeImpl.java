
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.TransportSnapshot;
import com.pa.lcr.lcp.transport.TransportStatus;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * ApiFacadeImpl
 *
 * ✅ ORCHESTRATION MEDIA (CONTRAT FINAL)
 *
 * - Field Service fournit : lcrnode + expected_serial_id
 * - Aucun bt_mac requis (jamais)
 *
 * Algorithme :
 * 1) Essayez TOUS les BT READY (un par un)
 *    - activateExclusive()
 *    - api_registerValidate(...)
 *    - si OK : STOP
 * 2) Sinon, essayez USB
 *    - api_registerValidate(...)
 * 3) Sinon : ERR_REGISTER_NOT_FOUND
 *
 * ❗ La lecture #80 / validation série est FAITE dans DeliveryController
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

    // =========================================================
    // Helpers
    // =========================================================

    private MediaTransportManager mtm() {
        return (appCtx != null) ? MediaTransportManager.get(appCtx) : null;
    }

    private static ApiResult fail(String msg, String err) {
        return ApiResult.fail(msg, err);
    }

    // =========================================================
    // USB (contrat)
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return ApiResult.fail("USB scan not supported via API", "USB_SCAN_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        return ApiResult.fail("USB open-ping not supported via API", "USB_OPENPING_NOT_SUPPORTED");
    }

    // =========================================================
    // MEDIA CHECK (capacité uniquement, PAS de validation registre)
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        MediaTransportManager mtm = mtm();
        if (mtm == null) {
            return fail("MediaCheck: MTM absent", "ERR_NO_MTM");
        }

        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "bt";

        // BT : est-ce qu'il existe AU MOINS un BT READY ?
        if ("bt".equals(m)) {
            for (TransportSnapshot s : mtm.listSnapshots()) {
                if (s != null
                        && s.key.startsWith("BT:")
                        && s.status == TransportStatus.READY) {
                    return ApiResult.ok("MediaCheck: BT disponible");
                }
            }
            // fallback USB possible ?
            TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
            if (usb != null && usb.isOpen()) {
                return ApiResult.ok("MediaCheck: USB fallback disponible");
            }
            return fail("MediaCheck: aucun media disponible", "ERR_NO_MEDIA_READY");
        }

        // USB explicite
        if ("usb".equals(m)) {
            TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
            if (usb != null && usb.isOpen()) {
                return ApiResult.ok("MediaCheck: USB disponible");
            }
            return fail("MediaCheck: USB non prêt", "ERR_USB_NOT_READY");
        }

        return fail("MediaCheck: media invalide", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // LCP CONNECT (orchestration BT multi‑essais → USB)
    // =========================================================

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(null, null, "bt", null);
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec,
                                    Integer from_dec,
                                    String media,
                                    String bt_mac) {

        MediaTransportManager mtm = mtm();
        if (mtm == null) {
            return fail("ConnectLcp: MTM absent", "ERR_NO_MTM");
        }

        int node = (lcrnode_dec != null) ? (lcrnode_dec & 0xFF) : 250;
        int from = (from_dec != null) ? (from_dec & 0xFF) : 255;

        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "bt";

        // =====================================================
        // 1) BT : essayer TOUS les BT READY
        // =====================================================
        if ("bt".equals(m)) {

            List<TransportSnapshot> snaps = mtm.listSnapshots();
            for (TransportSnapshot s : snaps) {
                if (s == null) continue;
                if (!s.key.startsWith("BT:")) continue;
                if (s.status != TransportStatus.READY) continue;

                // Activer ce transport
                boolean activated = mtm.activateExclusive(s.key, "API_BT_SCAN");
                if (!activated) continue;

                // Tentative de validation du registre via le métier
                ApiResult vr = delivery.api_registerValidate(
                        null,                   // numero_livraison (optionnel ici)
                        node,
                        null,                   // expected_serial_id sera comparé DANS le métier
                        null,
                        null
                );

                // ✅ Le registre correspond
                if (vr != null && vr.code == 1) {
                    return ApiResult.ok("ConnectLcp: BT OK");
                }
                // ❌ Pas le bon registre → on continue avec le BT suivant
            }
        }

        // =====================================================
        // 2) Fallback USB
        // =====================================================
        TransportIo usb = mtm.getByKey(MediaTransportManager.KEY_USB);
        if (usb != null && usb.isOpen()) {

            boolean activated = mtm.activateExclusive(MediaTransportManager.KEY_USB, "API_USB_FALLBACK");
            if (activated) {

                ApiResult vr = delivery.api_registerValidate(
                        null,
                        node,
                        null,
                        null,
                        null
                );

                if (vr != null && vr.code == 1) {
                    return ApiResult.ok("ConnectLcp: USB OK");
                }
            }
        }

        // =====================================================
        // 3) Aucun registre trouvé
        // =====================================================
        return fail("ConnectLcp: registre non trouvé", "ERR_REGISTER_NOT_FOUND");
    }

    // =========================================================
    // DELIVERY A / C (utilisent la connexion déjà établie)
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        try {
            delivery.alignOrRecover();
            return ApiResult.ok("Align A: OK");
        } catch (Exception e) {
            return fail("Align A failed", "ALIGN_FAILED");
        }
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode,
                                       Integer from,
                                       String media,
                                       String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode, from, media, bt_mac);
        if (c == null || c.code != 1) return c;
        return api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        try {
            delivery.startDelivery(product1to16, presetNet);
            return ApiResult.ok("Start C: OK");
        } catch (Exception e) {
            return fail("Start C failed", "START_FAILED");
        }
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode,
                                       Integer from,
                                       int product1to16,
                                       double presetNet,
                                       String media,
                                       String bt_mac) {
        ApiResult c = api_connectLcp(lcrnode, from, media, bt_mac);
        if (c == null || c.code != 1) return c;
        return api_deliveryStartC(product1to16, presetNet);
    }

    // =========================================================
    // Required contract stubs
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison,
                                             int product1to16,
                                             double presetNetL,
                                             String compartment) {
        return fail("OneShot not supported", "ONESHOT_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryJobGet(String jobId) {
        return fail("Job get not supported", "JOB_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryContinue(String jobId) {
        return fail("Continue not supported", "CONTINUE_NOT_SUPPORTED");
    }

    @Override public ApiResult api_deliveryTerminate(String jobId) {
        return fail("Terminate not supported", "TERMINATE_NOT_SUPPORTED");
    }

    @Override public ApiResult api_dbDump() {
        return fail("DB dump not supported", "DB_DUMP_NOT_SUPPORTED");
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
            return fail("RegisterValidate failed", "REGISTER_VALIDATE_FAILED");
        }
    }
}
