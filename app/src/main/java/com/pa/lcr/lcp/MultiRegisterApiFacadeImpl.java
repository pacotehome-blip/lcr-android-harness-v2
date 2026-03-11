
package com.pa.lcr.lcp;

import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.pa.lcrdemo.UsbSession;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MultiRegisterApiFacadeImpl implements ApiFacade {

    private static final String ACTION_NODE_SEEN = "com.pa.lcrdemo.ACTION_NODE_SEEN";

    private final Context appCtx;
    private final UsbManager usbManager;
    private final RegisterSessionManager sessions;

    // jobId -> node (fallback)
    private final Map<String, Integer> jobToNode = new ConcurrentHashMap<>();

    public MultiRegisterApiFacadeImpl(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
        this.usbManager = (UsbManager) this.appCtx.getSystemService(Context.USB_SERVICE);
        this.sessions = RegisterSessionManager.get(this.appCtx);
    }

    private void notifyNodeSeen(int node, int from) {
        try {
            Intent i = new Intent(ACTION_NODE_SEEN);
            i.setPackage(appCtx.getPackageName());
            i.putExtra("node", node);
            i.putExtra("from", from);
            appCtx.sendBroadcast(i);
        } catch (Exception ignored) {}
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

    @Override
    public ApiResult api_openPingUsb() {
        try {
            UsbSerialPort existing = UsbSession.getPort();
            if (existing != null) {
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)");
            }

            if (usbManager == null) {
                return ApiResult.fail("Open/Ping USB: 0 - USB manager null.", "ERR_USB_OPEN_FAILED");
            }

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

            if (!usbManager.hasPermission(dev)) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                d.put("deviceName", dev.getDeviceName());
                return ApiResult.fail("Open/Ping USB: 0 - Permission requise (accorde USB une fois via UI).",
                        "ERR_USB_PERMISSION_REQUIRED", d);
            }

            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null || driver.getPorts() == null || driver.getPorts().isEmpty()) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - Driver USB série introuvable.", "ERR_USB_DRIVER_NOT_FOUND", d);
            }

            UsbDeviceConnection conn = usbManager.openDevice(dev);
            if (conn == null) {
                JSONObject d = new JSONObject();
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.fail("Open/Ping USB: 0 - openDevice() a échoué (conn null).", "ERR_USB_OPEN_FAILED", d);
            }

            UsbSerialPort port = driver.getPorts().get(0);
            try {
                port.open(conn);
                port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                UsbSession.set(dev, port);

                JSONObject d = new JSONObject();
                d.put("usb_ready", 1);
                d.put("vid", dev.getVendorId());
                d.put("pid", dev.getProductId());
                return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)", d);

            } catch (Exception openEx) {
                try { port.close(); } catch (Exception ignored) {}
                try { conn.close(); } catch (Exception ignored) {}

                JSONObject d = new JSONObject();
                d.put("detail", (openEx.getMessage() != null) ? openEx.getMessage() : openEx.getClass().getSimpleName());
                return ApiResult.fail("Open/Ping USB: 0 - Échec ouverture port.", "ERR_USB_OPEN_FAILED", d);
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

    private DeliveryController requireSession(Integer nodeDec, Integer fromDec) {
        UsbSerialPort port = UsbSession.getPort();
        if (port == null) return null;
        return sessions.getOrCreate(normNode(nodeDec), normFrom(fromDec), port);
    }

    private void recordJobId(ApiResult r, int node) {
        try {
            JSONObject j = r.toJson();
            JSONObject data = j.optJSONObject("data");
            if (data == null) return;
            String jobId = data.optString("jobId", "").trim();
            if (!jobId.isEmpty()) jobToNode.put(jobId, node);
        } catch (Exception ignored) {}
    }

    private DeliveryController resolveJobController(String jobId, Integer nodeDec) {
        if (jobId == null || jobId.trim().isEmpty()) return null;
        Integer node = nodeDec;
        if (node == null) node = jobToNode.get(jobId);
        if (node == null) return null;
        return requireSession(node, 255);
    }

    // =========================
    // Legacy wrappers
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
    // Node-aware operations
    // =========================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        int n = normNode(lcrnode_dec);
        int f = normFrom(from_dec);
        notifyNodeSeen(n, f);

        DeliveryController dc = requireSession(n, f);
        if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        int n = normNode(lcrnode_dec);
        int f = normFrom(from_dec);
        notifyNodeSeen(n, f);

        DeliveryController dc = requireSession(n, f);
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        int n = normNode(lcrnode_dec);
        int f = normFrom(from_dec);
        notifyNodeSeen(n, f);

        DeliveryController dc = requireSession(n, f);
        if (dc == null) return ApiResult.fail("Delivery C: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");

        ApiResult r = dc.api_deliveryStartC(product1to16, presetNet);
        recordJobId(r, n);
        return r;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment) {
        int n = normNode(lcrnode_dec);
        int f = normFrom(from_dec);
        notifyNodeSeen(n, f);

        DeliveryController dc = requireSession(n, f);
        if (dc == null) return ApiResult.fail("OneShot: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");

        ApiResult r = dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        recordJobId(r, n);
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
        int n = normNode(expected_lcrnode_dec);
        int f = normFrom(from_dec);
        notifyNodeSeen(n, f);

        DeliveryController dc = requireSession(n, f);
        if (dc == null) return ApiResult.fail("Validate: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_registerValidate(numero_livraison, expected_lcrnode_dec,
                expected_serial_id, expected_product_number, expected_compartment);
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
}
