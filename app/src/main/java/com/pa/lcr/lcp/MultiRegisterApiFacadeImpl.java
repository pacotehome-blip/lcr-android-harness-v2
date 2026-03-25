
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
import com.pa.lcrdemo.UsbSession; // ✅ adapte si ton UsbSession est ailleurs

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

    // ✅ Option A: manager runtime multi-transport (USB/BT)
    private final MediaTransportManager mediaMgr;

    // jobId -> node (fallback)
    private final Map<String, Integer> jobToNode = new ConcurrentHashMap<>();
    // ✅ NEW: jobId -> from (best-effort)
    private final Map<String, Integer> jobToFrom = new ConcurrentHashMap<>();
    // ✅ NEW: dernier node/from observé (hint robuste)
    private volatile int lastNodeHint = 250;
    private volatile int lastFromHint = 255;

    public MultiRegisterApiFacadeImpl(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.usbManager = (UsbManager) this.appCtx.getSystemService(Context.USB_SERVICE);
        this.sessions = RegisterSessionManager.get(this.appCtx);

        // ✅ Option A init
        this.mediaMgr = MediaTransportManager.get(this.appCtx);
    }

    // =========================
    // ✅ Option A: Media check (USB/BT) - diagnostic simple
    // =========================
    @Override
    public ApiResult api_mediaCheck(String media, String bt_mac) {
        try {
            String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
            if (m.isEmpty()) m = "usb";

            JSONObject d = new JSONObject();
            d.put("media", m);

            if ("usb".equals(m)) {
                UsbSerialPort p = UsbSession.getPort();
                d.put("transportKey", "USB");
                d.put("connected", (p != null) ? 1 : 0);

                if (p != null) {
                    return ApiResult.ok("MediaCheck: 1 - USB connecté", d);
                }
                return ApiResult.fail("MediaCheck: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", d);
            }

            if ("bt".equals(m) || "bluetooth".equals(m)) {
                String mac = (bt_mac == null) ? "" : bt_mac.trim();
                if (mac.isEmpty()) {
                    return ApiResult.fail("MediaCheck: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED", d);
                }
                String key = MediaTransportManager.btKey(mac);
                d.put("transportKey", key);

                if (mediaMgr == null) {
                    d.put("connected", 0);
                    return ApiResult.fail("MediaCheck: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
                }

                TransportIo io = mediaMgr.getByKey(key);
                boolean ok = (io != null && io.isOpen());
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
     * - Si UsbSession port déjà prêt -> OK
     * - Sinon: ouvre port série, setParameters, UsbSession.set(dev, port) -> OK
     * - Broadcast UsbReceiver.ACTION_USB_READY si succès (tabs auto-attach).
     */
    @Override
    public ApiResult api_openPingUsb() {
        try {
            // 0) Déjà prêt ?
            UsbSerialPort existing = UsbSession.getPort();
            if (existing != null) {
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

                // publier la session globale
                UsbSession.set(dev, port);
                    // ✅ Publish USB transport to MediaTransportManager (so UI+API share the same TransportIo)
                    try {
                        if (mediaMgr != null) {
                            mediaMgr.onUsbReady(dev, port, "USB ready (API open-ping)");
                        }
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
        // update hints
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

    private DeliveryController requireSession(Integer nodeDec, Integer fromDec) {
        UsbSerialPort port = UsbSession.getPort();
        if (port == null) return null;
        int n = normNode(nodeDec);
        int f = normFrom(fromDec);
        notifyNodeSeen(n, f);
        return sessions.getOrCreate(n, f, port);
    }

    private void recordJobId(ApiResult r, int node, int from) {
        try {
            JSONObject j = r.toJson();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            String jobId = data.optString("jobId", "").trim();
            if (!jobId.isEmpty()) {
                jobToNode.put(jobId, node);
                jobToFrom.put(jobId, from);
            }
        } catch (Exception ignored) {}
    }

    /**
     * ✅ FIX NO_CONTROLLER:
     * - Priorité à nodeDec s'il est fourni (query param / body)
     * - Sinon fallback jobToNode (recordJobId)
     * - Sinon fallback lastNodeHint
     * - From: priorité à jobToFrom si disponible, sinon lastFromHint
     */
    private DeliveryController resolveJobController(String jobId, Integer nodeDec) {
        if (jobId == null || jobId.trim().isEmpty()) return null;

        // 1) node explicite -> priorité
        Integer node = nodeDec;
        if (node != null) {
            int n = normNode(node);
            int f = lastFromHint; // default/hint
            return requireSession(n, f);
        }

        // 2) mapping job->node
        Integer mappedNode = jobToNode.get(jobId);
        Integer mappedFrom = jobToFrom.get(jobId);
        if (mappedNode != null) {
            int n = normNode(mappedNode);
            int f = normFrom(mappedFrom != null ? mappedFrom : lastFromHint);
            return requireSession(n, f);
        }

        // 3) fallback sur hint
        int n = lastNodeHint;
        int f = lastFromHint;
        return requireSession(n, f);
    }

    // =========================
    // Legacy wrappers REQUIRED by ApiFacade (abstract methods)
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

    // ✅ NEW: legacy wrapper ticket reprint current
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
    // Node-aware operations (B2: create if missing)
    // =========================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Delivery C: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        ApiResult r = dc.api_deliveryStartC(product1to16, presetNet);
        recordJobId(r, node, from);
        return r;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("OneShot: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        ApiResult r = dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        recordJobId(r, node, from);
        return r;
    }

    @Override
    public ApiResult api_deliveryContinue(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
        if (dc == null) return ApiResult.fail("Continue: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryContinue(jobId);
    }

    @Override
    public ApiResult api_deliveryTerminate(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
        if (dc == null) return ApiResult.fail("Terminate: 0 - Controller introuvable (node/job).", "NO_CONTROLLER");
        return dc.api_deliveryTerminate(jobId);
    }

    @Override
    public ApiResult api_deliveryJobGet(String jobId, Integer lcrnode_dec) {
        DeliveryController dc = resolveJobController(jobId, lcrnode_dec);
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
        int node = normNode(expected_lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Validate: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_registerValidate(numero_livraison, expected_lcrnode_dec,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    // =========================================================
    // ✅ NEW: Ticket reprint current (node-aware)
    // =========================================================
    @Override
    public ApiResult api_ticketReprintCurrent(Integer lcrnode_dec, Integer from_dec) {
        int node = normNode(lcrnode_dec);
        int from = normFrom(from_dec);
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Reprint: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
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

    // =========================================================
    // ✅ NEW: Tick wait (B+): net/gross OR dev/prn OR delCode/delStatus OR state changes
    // =========================================================
    @Override
    public ApiResult api_tickWait(Integer lcrnode_dec, Long since_seq, Integer wait_ms) {
        int node = normNode(lcrnode_dec);
        int from = lastFromHint; // default/hint
        long since = (since_seq != null) ? since_seq : 0L;
        long wait = (wait_ms != null) ? wait_ms.longValue() : 25_000L;
        DeliveryController dc = requireSession(node, from);
        if (dc == null) {
            return ApiResult.fail("Tick: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        }
        return dc.api_tickWait(since, wait);
    }


// =========================
// ✅ A2: erreurs par niveau (MEDIA) pour transport non prêt
// =========================
private ApiResult failTransportLevel(String media, String btMac, String where) {
    String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
    JSONObject d = new JSONObject();
    try { d.put("level", "MEDIA"); } catch (Exception ignored) {}
    try { d.put("where", where); } catch (Exception ignored) {}
    try { d.put("media", m); } catch (Exception ignored) {}

    if ("usb".equals(m)) {
        return ApiResult.fail("Transport: 0 - USB non connecté", "ERR_USB_NOT_CONNECTED", d);
    }
    if ("bt".equals(m) || "bluetooth".equals(m)) {
        if (btMac == null || btMac.trim().isEmpty()) {
            return ApiResult.fail("Transport: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED", d);
        }
        try { d.put("bt_mac", btMac.trim()); } catch (Exception ignored) {}
        return ApiResult.fail("Transport: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED", d);
    }
    return ApiResult.fail("Transport: 0 - media invalide", "ERR_MEDIA_INVALID", d);
}


// =========================================================
// ✅ NEW: Delivery AlignA (media-aware) for API endpoint /v1/delivery/A
// =========================================================
@Override
public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec, String media, String bt_mac) {
    int node = normNode(lcrnode_dec);
    int from = normFrom(from_dec);
    String m = (media == null) ? "usb" : media.trim().toLowerCase(Locale.ROOT);
    if (m.isEmpty()) m = "usb";

    if ("usb".equals(m)) {
        DeliveryController dc = requireSession(node, from);
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }
    if ("bt".equals(m) || "bluetooth".equals(m)) {
        if (bt_mac == null || bt_mac.trim().isEmpty()) {
            return ApiResult.fail("Align A: 0 - bt_mac requis", "ERR_BT_MAC_REQUIRED");
        }
        String key = MediaTransportManager.btKey(bt_mac.trim());
        TransportIo io = (mediaMgr != null) ? mediaMgr.getByKey(key) : null;
        if (io == null || !io.isOpen()) {
            return ApiResult.fail("Align A: 0 - BT non connecté", "ERR_BT_NOT_CONNECTED");
        }
        DeliveryController dc = sessions.getOrCreate(key, node, from, io);
        if (dc == null) return ApiResult.fail("Align A: 0 - BT non prêt.", "ERR_BT_NOT_CONNECTED");
        return dc.api_deliveryAlignA();
    }
    return ApiResult.fail("Align A: 0 - media invalide", "ERR_MEDIA_INVALID");
}
}

