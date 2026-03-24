
package com.pa.lcr.lcp;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcrdemo.UsbReceiver;
import com.pa.lcrdemo.UsbSession;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MultiRegisterApiFacadeImpl implements ApiFacade {

    // Auto-tab: broadcast vers UI pour créer un tab si absent (no focus)
    private static final String ACTION_NODE_SEEN = "com.pa.lcrdemo.ACTION_NODE_SEEN";

    private final Context appCtx;
    private final UsbManager usbManager;
    private final RegisterSessionManager sessions;

    // Runtime transport manager (USB/BT)
    private final MediaTransportManager mediaMgr;

    // jobId -> node/from (fallback)
    private final Map<String, Integer> jobToNode = new ConcurrentHashMap<>();
    private final Map<String, Integer> jobToFrom = new ConcurrentHashMap<>();

    // dernier node/from observé (hint robuste)
    private volatile int lastNodeHint = 250;
    private volatile int lastFromHint = 255;

    public MultiRegisterApiFacadeImpl(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.usbManager = (UsbManager) this.appCtx.getSystemService(Context.USB_SERVICE);
        this.sessions = RegisterSessionManager.get(this.appCtx);
        this.mediaMgr = MediaTransportManager.get(this.appCtx);
    }

    // =========================
    // Media check (USB/BT)
    // =========================
    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        try {
            String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
            if (m.isEmpty()) m = "usb";

            JSONObject d = new JSONObject();
            d.put("media", m);

            if ("usb".equals(m)) {
                TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey("USB") : null;
                boolean ok = (io != null && io.isOpen());
                d.put("transportKey", "USB");
                d.put("connected", ok ? 1 : 0);
                if (ok) return ApiResult.ok("MediaCheck: 1 - USB connecté", d);
                return ApiResult.fail("MediaCheck: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", d);
            }

            if ("bt".equals(m) || "bluetooth".equals(m)) {
                String mac = (bt_mac == null) ? "" : bt_mac.trim();
                if (mac.isEmpty()) return ApiResult.fail("MediaCheck: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED", d);

                String key = MediaTransportManager.btKey(mac);
                TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
                boolean ok = (io != null && io.isOpen());
                d.put("transportKey", key);
                d.put("connected", ok ? 1 : 0);
                if (ok) return ApiResult.ok("MediaCheck: 1 - BT connecté", d);
                return ApiResult.fail("MediaCheck: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
            }

            if ("wifi".equals(m)) {
                d.put("connected", 0);
                return ApiResult.fail("MediaCheck: 0 - Wi-Fi non supporté (bientôt)", "ERR_WIFI_NOT_SUPPORTED", d);
            }

            d.put("connected", 0);
            return ApiResult.fail("MediaCheck: 0 - media invalide", "ERR_MEDIA_INVALID", d);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return ApiResult.fail("MediaCheck: 0 - Failed", "ERR_MEDIA_CHECK_FAILED", d);
        }
    }

    // =========================
    // USB global
    // =========================
    @Override
    public ApiResult api_scanUsb() {
        try {
            int n = (usbManager != null) ? usbManager.getDeviceList().size() : 0;
            JSONObject d = new JSONObject();
            d.put("usb_devices", n);
            return (n > 0)
                    ? ApiResult.ok("Scan USB: 1 - Registre détecté (USB device présent)", d)
                    : ApiResult.fail("Scan USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
        } catch (Exception e) {
            return ApiResult.fail("Scan USB: 0 - Failed", "ERR_MEDIA_NOT_PRESENT");
        }
    }

    /**
     * Open/Ping USB:
     * - Ouvre port série, setParameters
     * - UsbSession.set(dev, port) (utile UI legacy)
     * - ✅ Publie aussi dans MediaTransportManager (USB => TransportIo)
     * - Broadcast UsbReceiver.ACTION_USB_READY si succès
     */
    @Override
    public ApiResult api_openPingUsb() {
        try {
            // 0) Déjà prêt via UsbSession ? => s'assurer que le manager voit USB
            UsbSerialPort existing = UsbSession.getPort();
            if (existing != null) {
                try {
                    if (mediaMgr != null) mediaMgr.onUsbReady(UsbSession.getDevice(), existing, "USB prêt (UsbSession)");
                } catch (Exception ignored) {}
                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);
            }

            if (usbManager == null) {
                return ApiResult.fail("Open/Ping USB: 0 - USB manager null.", "ERR_USB_OPEN_FAILED");
            }

            // 1) Trouver un device
            Map<String, UsbDevice> devs = usbManager.getDeviceList();
            if (devs == null || devs.isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("usb_devices", 0);
                return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT", d);
            }

            Iterator<UsbDevice> it = devs.values().iterator();
            UsbDevice dev = it.hasNext() ? it.next() : null;
            if (dev == null) {
                return ApiResult.fail("Open/Ping USB: 0 - Aucun périphérique USB détecté.", "ERR_MEDIA_NOT_PRESENT");
            }

            // 2) Permission ?
            if (!usbManager.hasPermission(dev)) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.fail(
                        "Open/Ping USB: 0 - Permission requise (accorde USB une fois via UI).",
                        "ERR_USB_PERMISSION_REQUIRED",
                        d
                );
            }

            // 3) Driver ?
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null || driver.getPorts() == null || driver.getPorts().isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - Driver USB série introuvable.",
                        "ERR_USB_DRIVER_NOT_FOUND", d);
            }

            // 4) Ouvrir connexion + port
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            if (conn == null) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - openDevice() a échoué (conn null).",
                        "ERR_USB_OPEN_FAILED", d);
            }

            UsbSerialPort port = driver.getPorts().get(0);
            try {
                port.open(conn);
                port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                // publier la session globale (legacy UI)
                UsbSession.set(dev, port);

                // ✅ publier aussi dans le manager (USB => TransportIo)
                try {
                    if (mediaMgr != null) mediaMgr.onUsbReady(dev, port, "USB prêt (API open-ping)");
                } catch (Exception ignored) {}

                // signaler à l’UI que l’USB est prêt (tabs auto-attach)
                try {
                    Intent ready = new Intent(UsbReceiver.ACTION_USB_READY);
                    ready.setPackage(appCtx.getPackageName());
                    appCtx.sendBroadcast(ready);
                } catch (Exception ignored) {}

                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);

            } catch (Exception openEx) {
                try { port.close(); } catch (Exception ignored) {}
                try { conn.close(); } catch (Exception ignored) {}
                JSONObject d = new JSONObject();
                d.put("detail", (openEx.getMessage() != null) ? openEx.getMessage() : openEx.getClass().getSimpleName());
                return ApiResult.fail("Open/Ping USB: 0 - Échec ouverture port.",
                        "ERR_USB_OPEN_FAILED", d);
            }

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName()); } catch (Exception ignored) {}
            return ApiResult.fail("Open/Ping USB: 0 - Failed", "ERR_USB_OPEN_FAILED", d);
        }
    }

    // =========================
    // Helpers
    // =========================
    private static int normNode(Integer n) {
        if (n == null) return 250;
        int v = n;
        if (v < 1 || v > 250) return 250;
        return v;
    }

    private static int normFrom(Integer f) {
        if (f == null) return 255;
        int v = f;
        if (v < 0 || v > 255) return 255;
        return v;
    }

    private void notifyNodeSeen(int node, int from) {
        lastNodeHint = node;
        lastFromHint = from;
        try {
            Intent i = new Intent(ACTION_NODE_SEEN);
            i.setPackage(appCtx.getPackageName());
            i.putExtra("node", node);
            i.putExtra("from", from);
            appCtx.sendBroadcast(i);
        } catch (Exception ignored) {}
    }

    private static String normMedia(String media) {
        String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
        return m.isEmpty() ? "usb" : m;
    }

    private static final class Resolved {
        final String transportKey;
        final TransportIo io;
        final int node;
        final int from;
        Resolved(String transportKey, TransportIo io, int node, int from) {
            this.transportKey = transportKey;
            this.io = io;
            this.node = node;
            this.from = from;
        }
    }

    private Resolved resolveTransport(Integer nodeDec, Integer fromDec, String media, String btMac) {
        int node = normNode(nodeDec != null ? nodeDec : lastNodeHint);
        int from = normFrom(fromDec != null ? fromDec : lastFromHint);
        notifyNodeSeen(node, from);

        String m = normMedia(media);

        if ("usb".equals(m)) {
            String k = "USB";
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(k) : null;
            if (io == null || !io.isOpen()) return null;
            return new Resolved(k, io, node, from);
        }

        if ("bt".equals(m) || "bluetooth".equals(m)) {
            if (btMac == null || btMac.trim().isEmpty()) return null;
            String k = MediaTransportManager.btKey(btMac.trim());
            TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(k) : null;
            if (io == null || !io.isOpen()) return null;
            return new Resolved(k, io, node, from);
        }

        return null; // wifi futur
    }

    private ApiResult failTransport(String media, String btMac) {
        String m = normMedia(media);
        if ("usb".equals(m)) return ApiResult.fail("Transport: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED");
        if ("bt".equals(m) || "bluetooth".equals(m)) {
            if (btMac == null || btMac.trim().isEmpty()) {
                return ApiResult.fail("Transport: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED");
            }
            return ApiResult.fail("Transport: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
        }
        return ApiResult.fail("Transport: 0 - media invalide", "ERR_MEDIA_INVALID");
    }

    private DeliveryController requireSession(Integer nodeDec, Integer fromDec, String media, String btMac) {
        Resolved r = resolveTransport(nodeDec, fromDec, media, btMac);
        if (r == null) return null;
        return sessions.getOrCreate(r.transportKey, r.node, r.from, r.io);
    }

    private void recordJobId(ApiResult r, int node, int from, String transportKey) {
        try {
            JSONObject j = r.toJson();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            String jobId = data.optString("jobId", "").trim();
            if (!jobId.isEmpty()) {
                jobToNode.put(jobId, node);
                jobToFrom.put(jobId, from);
                // (Option B+) si tu veux: jobToTransport.put(jobId, transportKey);
            }
        } catch (Exception ignored) {}
    }

    private DeliveryController resolveJobController(String jobId, Integer nodeDec, String media, String btMac) {
        if (jobId == null || jobId.trim().isEmpty()) return null;

        Integer node = nodeDec;
        Integer from = null;

        if (node == null) {
            node = jobToNode.get(jobId);
            from = jobToFrom.get(jobId);
        }

        if (node == null) node = lastNodeHint;
        if (from == null) from = lastFromHint;

        return requireSession(node, from, media, btMac);
    }

    // =========================
    // Legacy wrappers REQUIRED by ApiFacade
    // =========================
    @Override public ApiResult api_connectLcp() { return api_connectLcp(null, null); }
    @Override public ApiResult api_deliveryAlignA() { return api_deliveryAlignA(null, null); }
    @Override public ApiResult api_deliveryStartC(int product1to16, double presetNet) { return api_deliveryStartC(null, null, product1to16, presetNet); }
    @Override public ApiResult api_deliveryJobGet(String jobId) { return api_deliveryJobGet(jobId, null); }
    @Override public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(null, null, numero_livraison, product1to16, presetNetL, compartment);
    }
    @Override public ApiResult api_deliveryContinue(String jobId) { return api_deliveryContinue(jobId, null); }
    @Override public ApiResult api_deliveryTerminate(String jobId) { return api_deliveryTerminate(jobId, null); }
    @Override public ApiResult api_ticketReprintCurrent() { return api_ticketReprintCurrent(null, null); }

    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, null,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    // =========================
    // Node-aware operations (B2) — legacy (USB par défaut)
    // =========================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        return api_connectLcp(lcrnode_dec, from_dec, "usb", null);
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        return api_deliveryAlignA(lcrnode_dec, from_dec, "usb", null);
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        return api_deliveryStartC(lcrnode_dec, from_dec, product1to16, presetNet, "usb", null);
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment) {
        return api_deliveryOneShotStart(lcrnode_dec, from_dec, numero_livraison, product1to16, presetNetL, compartment, "usb", null);
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        return api_deliveryContinue(jobId, lcrnode_dec, "usb", null);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        return api_deliveryTerminate(jobId, lcrnode_dec, "usb", null);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec, "usb", null);
        if (dc == null) return ApiResult.fail("Job: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryJobGet(jobId);
    }

    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         Integer from_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, from_dec,
                expected_serial_id, expected_product_number, expected_compartment, "usb", null);
    }

    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec) {
        return api_ticketReprintCurrent(lcrnode_dec, from_dec, "usb", null);
    }

    // =========================
    // ✅ Option B: Media-aware implementations
    // =========================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet,
                                        String media, String bt_mac) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);

        Resolved r = resolveTransport(lcrnode_dec, from_dec, media, bt_mac);
        ApiResult out = dc.api_deliveryStartC(product1to16, presetNet);
        if (r != null) recordJobId(out, r.node, r.from, r.transportKey);
        return out;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment,
                                             String media, String bt_mac) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);

        Resolved r = resolveTransport(lcrnode_dec, from_dec, media, bt_mac);
        ApiResult out = dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        if (r != null) recordJobId(out, r.node, r.from, r.transportKey);
        return out;
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec, String media, String bt_mac) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);
        return dc.api_deliveryContinue(jobId);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec, String media, String bt_mac) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);
        return dc.api_deliveryTerminate(jobId);
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
        DeliveryController dc = requireSession(expected_lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);

        return dc.api_registerValidate(numero_livraison, expected_lcrnode_dec,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec, media, bt_mac);
        if (dc == null) return failTransport(media, bt_mac);
        return dc.api_ticketReprintCurrent();
    }

    @Override
    public ApiResult api_dbDump() {
        try {
            String name = "lcr_delivery_" + DeliveryApiFacadeImpl.utcStampPublic() + ".json";
            boolean ok = sessions.getStore().dumpJsonToDownloads(appCtx, name);
            if (!ok) return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL");
            JSONObject d = new JSONObject();
            d.put("fileName", name);
            return ApiResult.ok("DB Dump: 1 - OK", d);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
            return ApiResult.fail("DB Dump: 0 - Failed", "DB_DUMP_FAIL", d);
        }
    }

    // Tick wait (cache-only) — pour l’instant via USB default/hint
    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        int node = normNode(lcrnode_dec);
        int from = lastFromHint;
        long since = (since_seq != null) ? since_seq : 0L;
        long wait = (wait_ms != null) ? wait_ms.longValue() : 25_000L;

        DeliveryController dc = requireSession(node, from, "usb", null);
        if (dc == null) {
            return ApiResult.fail("Tick: 0 - USB non prêt.", "ERR_USB_NOT_CONNECTED");
        }
        return dc.api_tickWait(since, wait);
    }
}
