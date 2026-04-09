
package com.pa.lcr.lcp;

import android.content.Context;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ApiFacadeImpl
 *
 * ✅ Orchestration AUTO: BT -> probe registre (#80) -> fallback USB
 * ✅ Ne demande jamais un MAC au client (résolution via MediaTransportManager)
 * ✅ Le scan ici = "probe #80" sur le node demandé (registre présent / pas présent)
 * ✅ Le métier (validate/delivery) reste dans DeliveryController
 */
public final class ApiFacadeImpl implements ApiFacade {

    private static final int DEFAULT_NODE = 250;
    private static final int DEFAULT_FROM = 255;

    // Probe rapide (scan registre) : lecture #80
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
        // Dans ton projet, le scan USB est piloté par l'APK/UI.
        // On garde l’API stable: pas géré ici.
        return ApiResult.fail("USB Scan: not handled here", "USB_SCAN_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_openPingUsb() {
        // Dans ton projet, l’ouverture/ping USB est piloté par l’APK/UI.
        // On garde l’API stable: pas géré ici.
        return ApiResult.fail("USB OpenPing: not handled here", "USB_OPENPING_NOT_SUPPORTED");
    }

    // =========================================================
    // ✅ Media check (diagnostic adaptateur)
    // - Ne fait PAS de scan registre
    // - Se base sur TransportIo READY/OPEN côté APK (MediaTransportManager)
    // =========================================================
    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        String m = normMedia(media, "usb");

        MediaTransportManager mtm = getMtm();
        if (mtm == null) {
            return ApiResult.fail("MediaCheck: 0 - MediaTransportManager null", "ERR_MEDIA_MTM_NULL");
        }

        if ("usb".equals(m)) {
            TransportIo io = safeGetIo(mtm, MediaTransportManager.KEY_USB);
            if (io != null && io.isOpen()) {
                return ApiResult.ok("MediaCheck: 1 - usb OK", dataMedia("usb", io.getKey(), null));
            }
            return ApiResult.fail("MediaCheck: 0 - usb not ready", "ERR_USB_NOT_READY");
        }

        if ("bt".equals(m)) {
            String mac = (bt_mac != null) ? bt_mac.trim() : "";
            if (mac.isEmpty()) {
                // On ne bloque pas si l’APK a déjà un BT actif.
                String activeKey = MediaTransportManager.getActiveKeyStatic();
                if (activeKey != null && activeKey.toUpperCase(Locale.ROOT).startsWith("BT:")) {
                    TransportIo io = safeGetIo(mtm, activeKey);
                    if (io != null && io.isOpen()) {
                        return ApiResult.ok("MediaCheck: 1 - bt OK (active)", dataMedia("bt", io.getKey(), activeKey.substring(3)));
                    }
                }
                // Sinon, on conserve le comportement diagnostic classique
                return ApiResult.fail("MediaCheck: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED");
            }

            String key = MediaTransportManager.btKey(mac);
            TransportIo io = safeGetIo(mtm, key);
            if (io != null && io.isOpen()) {
                return ApiResult.ok("MediaCheck: 1 - bt OK", dataMedia("bt", io.getKey(), mac));
            }
            return ApiResult.fail("MediaCheck: 0 - bt not ready", "ERR_BT_NOT_READY");
        }

        return ApiResult.fail("MediaCheck: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    // =========================================================
    // LCP connect (legacy mono)
    // =========================================================
    @Override
    public ApiResult api_connectLcp() {
        return api_connectLcp(DEFAULT_NODE, DEFAULT_FROM, "auto", "");
    }

    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        return api_connectLcp(lcrnode_dec, from_dec, "auto", "");
    }

    // ✅ Media-aware (appelé par ApiServer)
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        // Orchestration BT -> probe #80 -> fallback USB
        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, null);
        if (dc == null) {
            return ApiResult.fail("Connect: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        // Fixer le media best-effort (utile pour payload/result et logs)
        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        // Connexion LCP: on délègue au controller (métier)
        try {
            // Dans ton code, DeliveryController expose normalement cette méthode.
            return dc.api_connectLcp();
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try {
                d.put("detail", safeMsg(e));
                d.put("transport_key", findTransportKey(dc));
            } catch (Exception ignored) {}
            return ApiResult.fail("Connect: 0 - erreur", "ERR_CONNECT", d);
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
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("AlignA: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        try {
            return dc.api_deliveryAlignA();
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("AlignA: 0 - erreur", "ERR_ALIGNA", d);
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
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        return api_deliveryStartC(lcrnode_dec, from_dec, product1to16, presetNet, "auto", "");
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

        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("DeliveryC: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        try {
            return dc.api_deliveryStartC(product1to16, presetNet);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("DeliveryC: 0 - erreur", "ERR_DELIVERY_C", d);
        }
    }

    // =========================================================
    // Delivery OneShot (media-aware)
    // =========================================================
    @Override
    public ApiResult api_deliveryOneShotStart(String numero_livraison,
                                             int product1to16,
                                             double presetNetL,
                                             String compartment) {
        // Legacy signature (sans node/from/media) — non utilisé dans ton orchestration Option B
        return ApiResult.fail("OneShot: 0 - Not supported in legacy facade", "ONESHOT_NOT_SUPPORTED");
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec,
                                             Integer from_dec,
                                             String numero_livraison,
                                             int product1to16,
                                             double presetNetL,
                                             String compartment,
                                             String media,
                                             String bt_mac) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("OneShot: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        try {
            return dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("OneShot: 0 - erreur", "ERR_ONESHOT", d);
        }
    }

    // =========================================================
    // Job (node-aware)
    // =========================================================
    @Override
    public ApiResult api_deliveryJobGet(String jobId) {
        return api_deliveryJobGet(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        // Pour jobget: on utilise le registre déjà associé (pin/expectedSerial) si possible.
        DeliveryController dc = selectControllerBtThenUsb(node, DEFAULT_FROM, "auto", "", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("JobGet: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryJobGet(jobId);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("JobGet: 0 - erreur", "ERR_JOBGET", d);
        }
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId) {
        return api_deliveryContinue(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        DeliveryController dc = selectControllerBtThenUsb(node, DEFAULT_FROM, "auto", "", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Continue: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryContinue(jobId);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("Continue: 0 - erreur", "ERR_CONTINUE", d);
        }
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId) {
        return api_deliveryTerminate(jobId, DEFAULT_NODE);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        DeliveryController dc = selectControllerBtThenUsb(node, DEFAULT_FROM, "auto", "", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Terminate: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try {
            return dc.api_deliveryTerminate(jobId);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("Terminate: 0 - erreur", "ERR_TERMINATE", d);
        }
    }

    // =========================================================
    // ✅ validateRegister (legacy signature) + media-aware
    // =========================================================
    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        // Legacy signature (sans media). On conserve un comportement AUTO.
        return api_registerValidate(numero_livraison,
                expected_lcrnode_dec,
                DEFAULT_FROM,
                expected_serial_id,
                expected_product_number,
                expected_compartment,
                "auto",
                "");
    }

    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         Integer from_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment,
                                         String media,
                                         String bt_mac) {
        int node = (expected_lcrnode_dec != null) ? expected_lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        String expectedSerial = (expected_serial_id != null) ? expected_serial_id.trim() : null;
        if (expectedSerial != null && expectedSerial.isEmpty()) expectedSerial = null;

        // ✅ important: si FieldService nous donne le serial attendu, on le mémorise
        // pour que l’attach/pin côté RegisterSessionManager devienne déterministe.
        if (expectedSerial != null) {
            try { rsm.bindExpectedSerial(node, expectedSerial); } catch (Exception ignored) {}
        }

        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, expectedSerial);
        if (dc == null) {
            return ApiResult.fail("Validate: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        try {
            // Signature connue dans ton DeliveryController
            return dc.api_registerValidate(numero_livraison, node, expectedSerial, expected_product_number, expected_compartment);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("Validate: 0 - erreur", "ERR_VALIDATE", d);
        }
    }

    // =========================================================
    // Ticket reprint (media-aware)
    // =========================================================
    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;
        int from = (from_dec != null) ? from_dec : DEFAULT_FROM;

        DeliveryController dc = selectControllerBtThenUsb(node, from, media, bt_mac, rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Reprint: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        try { dc.setActiveMedia(mediaFromTransportKey(findTransportKey(dc))); } catch (Exception ignored) {}

        try {
            return dc.api_ticketReprintCurrent();
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("Reprint: 0 - erreur", "ERR_REPRINT", d);
        }
    }

    // =========================================================
    // TickBus wait (B+) - cache-only
    // =========================================================
    @Override
    public ApiResult api_tickWait(Long since_seq, Integer wait_ms) {
        return ApiResult.fail("Tick: 0 - node requis", "ERR_NODE_REQUIRED");
    }

    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        int node = (lcrnode_dec != null) ? lcrnode_dec : DEFAULT_NODE;

        DeliveryController dc = selectControllerBtThenUsb(node, DEFAULT_FROM, "auto", "", rsm.getExpectedSerial(node));
        if (dc == null) {
            return ApiResult.fail("Tick: 0 - Aucun registre trouvé (BT/USB)", "ERR_NO_REGISTER_FOUND");
        }

        long since = (since_seq != null) ? since_seq : 0L;
        int wait = (wait_ms != null) ? wait_ms : 0;

        try {
            return dc.api_tickWait(since, wait);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", safeMsg(e)); d.put("transport_key", findTransportKey(dc)); } catch (Exception ignored) {}
            return ApiResult.fail("Tick: 0 - erreur", "ERR_TICK", d);
        }
    }

    // =========================================================
    // DB (global)
    // =========================================================
    @Override
    public ApiResult api_dbDump() {
        // Dans ton projet, le dump DB est géré ailleurs (UI/Store).
        // On garde l’API stable: pas géré ici.
        return ApiResult.fail("DB Dump: not handled here", "DB_DUMP_NOT_SUPPORTED");
    }

    // =========================================================
    // =============== ORCHESTRATION CORE (BT -> USB) ===========
    // =========================================================

    /**
     * Sélectionne un controller en essayant :
     * - BT d’abord (si demandé ou auto)
     * - probe #80 (serial) sur le node demandé
     * - si pas valide => fallback USB
     *
     * @param expectedSerial si non null, doit matcher le #80 lu; sinon considéré "pas valide"
     */
    private DeliveryController selectControllerBtThenUsb(int node, int from, String media, String btMac, String expectedSerial) {

        String m = normMedia(media, "auto");

        // Ordre: BT d'abord sauf si media=usb explicite
        boolean tryBtFirst = !"usb".equals(m);

        // Si media=bt explicite, on essaie BT puis fallback USB (mandat)
        // Si media=auto, BT puis USB
        // Si media=usb, USB seulement

        if (tryBtFirst) {
            DeliveryController dcBt = tryMediaBt(node, from, btMac, expectedSerial);
            if (dcBt != null) return dcBt;
        }

        DeliveryController dcUsb = tryMediaUsb(node, from, expectedSerial);
        if (dcUsb != null) return dcUsb;

        // Si media=usb explicite et USB a échoué, on ne tente pas BT (respect explicite)
        if ("usb".equals(m)) return null;

        // Cas résiduel: media=auto ou bt, et BT pas tenté en premier (rare) -> tenter BT
        if (!tryBtFirst) {
            return tryMediaBt(node, from, btMac, expectedSerial);
        }

        return null;
    }

    private DeliveryController tryMediaBt(int node, int from, String btMac, String expectedSerial) {
        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        // Résoudre un key BT: priorité
        // 1) btMac fourni => BT:<mac>
        // 2) sinon activeKey BT côté APK
        String key = null;
        String mac = (btMac != null) ? btMac.trim() : "";
        if (!mac.isEmpty()) {
            key = MediaTransportManager.btKey(mac);
        } else {
            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null && activeKey.toUpperCase(Locale.ROOT).startsWith("BT:")) {
                key = activeKey.trim();
            }
        }

        if (key == null || key.trim().isEmpty()) return null;

        TransportIo io = safeGetIo(mtm, key);
        if (io == null || !io.isOpen()) return null;

        // Probe #80 (serial) => valide ?
        String serial = probeSerial80(io, node, from);
        if (!isValidSerial(serial)) return null;

        // Si expectedSerial fourni, il doit matcher
        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            if (!expectedSerial.trim().equals(serial)) return null;
        }

        // Création/récupération session
        DeliveryController dc = rsm.getOrCreate(io.getKey(), node, from, io);
        if (dc != null) {
            try { dc.setActiveMedia("bt"); } catch (Exception ignored) {}
        }
        return dc;
    }

    private DeliveryController tryMediaUsb(int node, int from, String expectedSerial) {
        MediaTransportManager mtm = getMtm();
        if (mtm == null) return null;

        TransportIo io = safeGetIo(mtm, MediaTransportManager.KEY_USB);
        if (io == null || !io.isOpen()) return null;

        String serial = probeSerial80(io, node, from);
        if (!isValidSerial(serial)) return null;

        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            if (!expectedSerial.trim().equals(serial)) return null;
        }

        DeliveryController dc = rsm.getOrCreate(io.getKey(), node, from, io);
        if (dc != null) {
            try { dc.setActiveMedia("usb"); } catch (Exception ignored) {}
        }
        return dc;
    }

    private String probeSerial80(TransportIo io, int node, int from) {
        try {
            LcpLink link = new LcpLink(io, node, from, true);
            byte[] b = link.opGetField(80, PROBE_SERIAL_TIMEOUT_MS);
            if (b == null || b.length == 0) return null;
            String s = new String(b, StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return null;
            // Nettoyage léger (évite CR/LF/NULL)
            s = s.replace("\u0000", "").replace("\r", "").replace("\n", "").trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isValidSerial(String serial) {
        if (serial == null) return false;
        String s = serial.trim();
        return !s.isEmpty();
    }

    private MediaTransportManager getMtm() {
        try {
            Context ctx = (rsm != null) ? rsm.getAppContext() : null;
            if (ctx == null) return null;
            return MediaTransportManager.get(ctx);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static TransportIo safeGetIo(MediaTransportManager mtm, String key) {
        try {
            if (mtm == null || key == null) return null;
            return mtm.getByKey(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findTransportKey(DeliveryController dc) {
        try {
            if (rsm == null || dc == null) return null;
            return rsm.findTransportKeyForController(dc);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String mediaFromTransportKey(String key) {
        if (key == null) return "usb";
        String u = key.trim().toUpperCase(Locale.ROOT);
        if (u.startsWith("BT:") || u.contains("BT:")) return "bt";
        if (u.startsWith("USB") || u.contains("USB")) return "usb";
        return "usb";
    }

    private static String normMedia(String media, String def) {
        String m = (media != null) ? media.trim().toLowerCase(Locale.ROOT) : "";
        return m.isEmpty() ? def : m;
    }

    private static JSONObject dataMedia(String media, String transportKey, String btMac) {
        JSONObject d = new JSONObject();
        try { d.put("media", media); } catch (Exception ignored) {}
        try { d.put("transport_key", transportKey != null ? transportKey : JSONObject.NULL); } catch (Exception ignored) {}
        try { d.put("bt_mac", (btMac != null && !btMac.trim().isEmpty()) ? btMac.trim() : JSONObject.NULL); } catch (Exception ignored) {}
        return d;
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }
}
