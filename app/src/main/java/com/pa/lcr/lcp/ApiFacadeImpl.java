
package com.pa.lcr.lcp;

import org.json.JSONObject;

import java.util.Locale;

/**
 * ApiFacadeImpl
 *
 * Rôle :
 * - Orchestration API (media / node / from)
 * - Délégation de la résolution du registre à RegisterSessionManager
 * - Aucune décision métier sur le registre ici (#serial reste l’autorité)
 *
 * Règles clés :
 * - BT sans MAC accepté
 * - media = auto | usb | bt
 * - Une livraison = un registre
 */
public final class ApiFacadeImpl implements ApiFacade {

    private final RegisterSessionManager rsm;

    public ApiFacadeImpl(RegisterSessionManager rsm) {
        this.rsm = rsm;
    }

    // =========================================================
    // Media check
    // =========================================================

    @Override
    public ApiResult api_mediaCheck(String media, String btMac) {
        // La validation stricte est déjà correctement gérée dans MediaTransportManager
        // ApiServer décide quand ce check est bloquant
        try {
            return MediaChecks.check(media, btMac, rsm.getAppContext());
        } catch (Exception e) {
            return ApiResult.fail(
                    "MediaCheck: 0 - error",
                    "MEDIA_CHECK_ERROR",
                    new JSONObject().put("detail", e.getMessage())
            );
        }
    }

    // =========================================================
    // LCP Connect
    // =========================================================

    @Override
    public ApiResult api_connectLcp(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String btMac
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "Connect: 0 - Aucun registre valide (serial mismatch)",
                    "ERR_SERIAL_MISMATCH"
            );
        }

        JSONObject d = new JSONObject();
        d.put("node", node);
        d.put("from", from);
        d.put("media", normalize(media));

        return ApiResult.ok("Connect: 1 - OK", d);
    }

    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(250, 255, "auto", null);
    }

    // =========================================================
    // Delivery A (Align / Recover)
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String btMac
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "AlignA: 0 - Aucun registre valide",
                    "ERR_SERIAL_MISMATCH"
            );
        }

        dc.alignOrRecover();
        return ApiResult.ok("AlignA: 1 - OK");
    }

    // =========================================================
    // Delivery C (Start delivery)
    // =========================================================

    @Override
    public ApiResult api_deliveryStartC(
            Integer lcrnode_dec,
            Integer from_dec,
            int product1to16,
            double presetNet,
            String media,
            String btMac
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "DeliveryC: 0 - Aucun registre valide",
                    "ERR_SERIAL_MISMATCH"
            );
        }

        dc.startDelivery(product1to16, presetNet);
        return ApiResult.ok("DeliveryC: 1 - OK");
    }

    // =========================================================
    // OneShot
    // =========================================================

    @Override
    public ApiResult api_deliveryOneShotStart(
            Integer lcrnode_dec,
            Integer from_dec,
            String numeroLivraison,
            int product1to16,
            double preset,
            String compartment,
            String media,
            String btMac
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "OneShot: 0 - Aucun registre valide",
                    "ERR_SERIAL_MISMATCH"
            );
        }

        dc.startOneShot(numeroLivraison, product1to16, preset, compartment);
        return ApiResult.ok("OneShot: 1 - OK");
    }

    // =========================================================
    // Job handling (JOB-level, pas media-aware)
    // =========================================================

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = rsm.resolveOrCreateForNode(
                (lcrnode_dec != null) ? lcrnode_dec : 250,
                255
        );
        if (dc == null) {
            return ApiResult.fail("Continue: 0 - Aucun registre", "ERR_NO_REGISTER");
        }
        dc.continueJob(jobId);
        return ApiResult.ok("Continue: 1 - OK");
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = rsm.resolveOrCreateForNode(
                (lcrnode_dec != null) ? lcrnode_dec : 250,
                255
        );
        if (dc == null) {
            return ApiResult.fail("Terminate: 0 - Aucun registre", "ERR_NO_REGISTER");
        }
        dc.terminateJob(jobId);
        return ApiResult.ok("Terminate: 1 - OK");
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = rsm.resolveOrCreateForNode(
                (lcrnode_dec != null) ? lcrnode_dec : 250,
                255
        );
        if (dc == null) {
            return ApiResult.fail("JobGet: 0 - Aucun registre", "ERR_NO_REGISTER");
        }
        return dc.getJob(jobId);
    }

    // =========================================================
    // Register validate
    // =========================================================

    @Override
    public ApiResult api_registerValidate(
            String numeroLivraison,
            Integer expected_lcrnode_dec,
            Integer from_dec,
            String expected_serial,
            Integer expected_product,
            String expected_compartment,
            String media,
            String btMac
    ) {
        int node = (expected_lcrnode_dec != null) ? expected_lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail(
                    "RegisterValidate: 0 - Serial mismatch",
                    "ERR_SERIAL_MISMATCH"
            );
        }

        return dc.validateRegister(
                numeroLivraison,
                expected_serial,
                expected_product,
                expected_compartment
        );
    }

    // =========================================================
    // Ticket
    // =========================================================

    @Override
    public ApiResult api_ticketReprintCurrent(
            Integer lcrnode_dec,
            Integer from_dec,
            String media,
            String btMac
    ) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : 250;
        int from = (from_dec != null) ? from_dec : 255;

        DeliveryController dc = rsm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            return ApiResult.fail("Reprint: 0 - Aucun registre", "ERR_NO_REGISTER");
        }

        dc.reprintCurrentTicket();
        return ApiResult.ok("Reprint: 1 - OK");
    }

    // =========================================================
    // USB / DB / Tick passthrough
    // =========================================================

    @Override
    public ApiResult api_scanUsb() {
        return UsbServices.scan();
    }

    @Override
    public ApiResult api_openPingUsb() {
        return UsbServices.openPing();
    }

    @Override
    public ApiResult api_dbDump() {
        return DbServices.dump();
    }

    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, long sinceSeq, Integer waitMs) {
        DeliveryController dc = rsm.resolveOrCreateForNode(
                (lcrnode_dec != null) ? lcrnode_dec : 250,
                255
        );
        if (dc == null) {
            return ApiResult.fail("Tick: 0 - Aucun registre", "ERR_NO_REGISTER");
        }
        return dc.tickWait(sinceSeq, waitMs);
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static String normalize(String media) {
        if (media == null || media.isEmpty()) return "auto";
        return media.toLowerCase(Locale.ROOT);
    }
}
