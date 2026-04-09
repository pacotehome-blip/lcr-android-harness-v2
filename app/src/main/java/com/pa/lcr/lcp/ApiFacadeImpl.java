
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
 * ApiFacadeImpl — FINAL COMPLET
 *
 * Règles FIGÉES :
 * - Essayer TOUS les BT READY, un par un
 * - STOP immédiat dès qu’un registre est trouvé
 * - Ensuite essayer USB
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
    // Media check (diagnostic adaptateur uniquement)
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

        DeliveryController dc = selectController(node, from, media, rsm.getExpectedSerial(node));
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
    // Align / Recover (A)
    // =========================================================

    @Override
    public ApiResult api_deliveryAlignA() {
        return api_deliveryAlignA(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        return api_deliveryAlignA(lcrnode_dec, from_dec, "auto", "");
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec,
                                        Integer from_dec,
                                        String media,
                                        String bt_mac) {

        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc = selectController(node, from, media, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("AlignA: aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryAlignA();
        } catch (Exception e) {
            return ApiResult.fail("AlignA: erreur", "ERR_ALIGNA", errDetail(e, dc));
        }
    }

    // =========================================================
    // Delivery C
    // =========================================================

    @Override
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        return api_deliveryStartC(DEFAULT_NODE, DEFAULT_FROM, product1to16, presetNet, "auto", "");
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec,
                                        Integer from_dec,
                                        int product1to16,
                                        double presetNet,
                                        String media,
                                        String bt_mac) {

        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc = selectController(node, from, media, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("DeliveryC: aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryStartC(product1to16, presetNet);
        } catch (Exception e) {
            return ApiResult.fail("DeliveryC: erreur", "ERR_DELIVERY_C", errDetail(e, dc));
        }
    }

    // =========================================================
    // CONTINUE — signatures OBLIGATOIRES
    // =========================================================

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        return api_deliveryContinue(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {

        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        DeliveryController dc = selectController(node, DEFAULT_FROM, "auto", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Continue: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryContinue(jobId);
        } catch (Exception e) {
            return ApiResult.fail("Continue: erreur", "ERR_CONTINUE", errDetail(e, dc));
        }
    }

    // =========================================================
    // TERMINATE — signatures OBLIGATOIRES
    // =========================================================

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        return api_deliveryTerminate(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {

        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        DeliveryController dc = selectController(node, DEFAULT_FROM, "auto", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Terminate: aucun registre trouvé", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryTerminate(jobId);
        } catch (Exception e) {
            return ApiResult.fail("Terminate: erreur", "ERR_TERMINATE", errDetail(e, dc));
        }
    }

    // =========================================================
    // REGISTER VALIDATE — signatures OBLIGATOIRES
    // =========================================================

    @Override
    public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    ) {
        return api_registerValidate(
                numero_livraison,
                expected_lcrnode_dec,
                DEFAULT_FROM,
                expected_serial_id,
                expected_product_number,
                expected_compartment,
                "auto",
                ""
        );
    }

    @Override
    public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            Integer from_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment,
            String media,
            String bt_mac
    ) {

        int node = (expected_lcrnode_dec != null) ? expected_lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        String expectedSerial =
                (expected_serial_id != null && !expected_serial_id.trim().isEmpty())
                        ? expected_serial_id.trim()
                        : null;

        if (expectedSerial != null) {
            rsm.bindExpectedSerial(node, expectedSerial);
        }

        DeliveryController dc = selectController(node, from, media, expectedSerial);
        if (dc == null) {
            return ApiResult.fail("Validate: aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_registerValidate(
                    numero_livraison,
                    node,
                    expectedSerial,
                    expected_product_number,
                    expected_compartment
            );
        } catch (Exception e) {
            return ApiResult.fail("Validate: erreur", "ERR_VALIDATE", errDetail(e, dc));
        }
    }

    // =========================================================
    // ORCHESTRATION CENTRALE (BT* → USB)
    // =========================================================

    private DeliveryController selectController(int node,
                                               int from,
                                               String media,
                                               String expectedSerial) {

        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        String m = normMedia(media, "auto");

        // 1) TOUS les BT READY
        if (!"usb".equals(m)) {
            for (TransportSnapshot snap : mtm.listSnapshots()) {
                if (snap == null || snap.key == null) continue;
                if (!snap.key.toUpperCase(Locale.ROOT).startsWith("BT:")) continue;
                if (snap.status != TransportStatus.READY) continue;

                mtm.activateExclusive(snap.key, "API_BT_SCAN");

                TransportIo io = mtm.getByKey(snap.key);
                if (io == null || !io.isOpen()) continue;

                String serial = probeSerial80(io, node, from);
                if (serial == null) continue;
                if (expectedSerial != null && !expectedSerial.equals(serial)) continue;

                DeliveryController dc = rsm.getOrCreate(io.getKey(), node, from, io);
                if (dc != null) {
                    dc.setActiveMedia("bt");
                    return dc; // ✅ STOP IMMÉDIAT
                }
            }
        }

        // 2) USB
        TransportIo ioUsb = mtm.getByKey(MediaTransportManager.KEY_USB);
        if (ioUsb != null && ioUsb.isOpen()) {
            String serial = probeSerial80(ioUsb, node, from);
            if (serial != null && (expectedSerial == null || expectedSerial.equals(serial))) {
                DeliveryController dc = rsm.getOrCreate(ioUsb.getKey(), node, from, ioUsb);
                if (dc != null) {
                    dc.setActiveMedia("usb");
                    return dc; // ✅ STOP IMMÉDIAT
                }
            }
        }

        return null;
    }

    // =========================================================
    // Utils
    // =========================================================

    private String probeSerial80(TransportIo io, int node, int from) {
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
        } catch (Exception ignored) {
            return null;
        }
    }

    private MediaTransportManager getMtm() {
        try {
            Context ctx = rsm.getAppContext();
            return (ctx != null) ? MediaTransportManager.get(ctx) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normMedia(String media, String def) {
        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "";
        return m.isEmpty() ? def : m;
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
