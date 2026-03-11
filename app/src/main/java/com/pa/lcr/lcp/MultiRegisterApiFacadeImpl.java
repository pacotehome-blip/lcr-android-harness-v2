
package com.pa.lcr.lcp;

import android.content.Context;
import android.hardware.usb.UsbManager;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcrdemo.UsbSession; // ✅ FIX: adapter si le package est différent

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class MultiRegisterApiFacadeImpl implements ApiFacade {

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
        UsbSerialPort p = UsbSession.getPort(); // ✅ FIX: UsbSession import
        if (p == null) return ApiResult.fail("Open/Ping USB: 0 - USB non prêt (port null).", "ERR_USB_PORT_NOT_READY");
        return ApiResult.ok("Open/Ping USB: 1 - USB prêt (port ouvert)");
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
        UsbSerialPort port = UsbSession.getPort(); // ✅ FIX: UsbSession import
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
    // Node-aware operations (B2: create if missing)
    // =========================
    @Override
    public ApiResult api_connectLcp(Integer lcrnode_dec, Integer from_dec) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec);
        if (dc == null) return ApiResult.fail("Connect LCP: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_connectLcp();
    }

    @Override
    public ApiResult api_deliveryAlignA(Integer lcrnode_dec, Integer from_dec) {
        DeliveryController dc = requireSession(lcrnode_dec, from_dec);
        if (dc == null) return ApiResult.fail("Align A: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        return dc.api_deliveryAlignA();
    }

    @Override
    public ApiResult api_deliveryStartC(Integer lcrnode_dec, Integer from_dec, int product1to16, double presetNet) {
        int node = normNode(lcrnode_dec);
        DeliveryController dc = requireSession(node, from_dec);
        if (dc == null) return ApiResult.fail("Delivery C: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        ApiResult r = dc.api_deliveryStartC(product1to16, presetNet);
        recordJobId(r, node);
        return r;
    }

    @Override
    public ApiResult api_deliveryOneShotStart(Integer lcrnode_dec, Integer from_dec,
                                             String numero_livraison, int product1to16, double presetNetL, String compartment) {
        int node = normNode(lcrnode_dec);
        DeliveryController dc = requireSession(node, from_dec);
        if (dc == null) return ApiResult.fail("OneShot: 0 - USB non prêt.", "ERR_USB_PORT_NOT_READY");
        ApiResult r = dc.api_deliveryOneShotStart(numero_livraison, product1to16, presetNetL, compartment);
        recordJobId(r, node);
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

    // =========================
    // ✅ FIX #1: implement legacy validateRegister (5 params) required by ApiFacade
    // =========================
    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        // Delegate to node-aware variant (from_dec null -> default 255)
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, null,
                expected_serial_id, expected_product_number, expected_compartment);
    }

    // Node-aware validate with from_dec (B2)
    @Override
    public ApiResult api_registerValidate(String numero_livraison,
                                         Integer expected_lcrnode_dec,
                                         Integer from_dec,
                                         String expected_serial_id,
                                         Integer expected_product_number,
                                         String expected_compartment) {
        DeliveryController dc = requireSession(expected_lcrnode_dec, from_dec);
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
