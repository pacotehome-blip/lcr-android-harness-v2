
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import org.json.JSONObject;
import com.pa.lcr.lcp.transport.TransportIo;
import com.pa.lcr.lcp.transport.UsbTransportIo;

import java.nio.charset.StandardCharsets;

/**
 * Helper stateless pour la validation "Registre prêt".
 *
 * Utilisé par:
 * - DeliveryController.api_registerValidate(...)
 * - HeadlessApiFacade (API sans UI)
 *
 * Responsabilités:
 * - Lecture LCP (0x28, champs #23, #80, #0)
 * - Calcul ticket_pending / delivery_active
 * - Calcul delivery_uid = numero_livraison + "-" + ticket_no
 * - Retour ApiResult structuré (sans effet de bord)
 */
public final class RegisterValidator {

    // =========================
    // Champs LCR
    // =========================
    private static final int FIELD_ACTIVE_PRODUCT = 0;   // 0..15
    private static final int FIELD_TICKET_NUMBER  = 23;  // U32
    private static final int FIELD_SERIAL_ID      = 80;  // AZ

    // Bits delCode (GET_DELIVERY_STATUS / 0x28)
    private static final int DC_TICKET_PENDING   = 0x0001;
    private static final int DC_FLOW_ACTIVE      = 0x0004;
    private static final int DC_DELIVERY_ACTIVE  = 0x0008;

    private RegisterValidator() {}

    // =========================
    // Codes d’erreur officiels
    // =========================
    public static final class Codes {
        public static final String ERR_USB_PORT_NOT_READY   = "ERR_USB_PORT_NOT_READY";
        public static final String ERR_LCP_CONNECT_FAILED   = "ERR_LCP_CONNECT_FAILED";
        public static final String ERR_TICKET_PENDING       = "ERR_TICKET_PENDING";
        public static final String ERR_DELIVERY_ACTIVE      = "ERR_DELIVERY_ACTIVE";
        public static final String ERR_SERIAL_ID_MISMATCH   = "ERR_SERIAL_ID_MISMATCH";
        public static final String ERR_PRODUCT_MISMATCH     = "ERR_PRODUCT_MISMATCH";
        public static final String ERR_COMPARTMENT_MISMATCH = "ERR_COMPARTMENT_MISMATCH";
    }

    /**
     * Validation complète du registre via LCP.
     *
     * @param port                   Port USB série déjà ouvert
     * @param toAddr                 LCR node (TO)
     * @param fromAddr               Host addr (FROM, typiquement 255)
     * @param numeroLivraison        Work Order (peut être null)
     * @param expectedSerialId       serial_id attendu (FS)
     * @param expectedProduct1to16   produit attendu (1..16)
     * @param expectedCompartment    compartiment attendu (string, présence minimale)
     */
    public static ApiResult validateLcp(
            UsbSerialPort port,
            int toAddr,
            int fromAddr,
            String numeroLivraison,
            String expectedSerialId,
            Integer expectedProduct1to16,
            String expectedCompartment
    ) {
        if (port == null) {
            return ApiResult.fail(
                    "Validate: 0 - USB non prêt.",
                    Codes.ERR_USB_PORT_NOT_READY
            );
        }

        TransportIo tio = new UsbTransportIo("USB", port, "USB legacy (RegisterValidator)", System.currentTimeMillis());
 LcpLink link = new LcpLink(tio, toAddr, fromAddr, true);

        try {
            // =========================
            // 1) GET_DELIVERY_STATUS (0x28)
            // =========================
            int[] ds = link.opDeliveryStatus();
            int delCode = ds[1];

            boolean ticketPending  = (delCode & DC_TICKET_PENDING) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
            boolean flowActive     = (delCode & DC_FLOW_ACTIVE) != 0;

            // =========================
            // 2) Ticket number (#23)
            // =========================
            String ticketNo = readU32AsDec(link.opGetField(FIELD_TICKET_NUMBER));

            // =========================
            // 3) Serial ID (#80)
            // =========================
            String serialId = decodeAz(link.opGetField(FIELD_SERIAL_ID));

            // =========================
            // 4) Produit actif (#0)
            // =========================
            Integer activeProduct1to16 = null;
            try {
                byte[] p = link.opGetField(FIELD_ACTIVE_PRODUCT);
                if (p != null && p.length >= 1) {
                    activeProduct1to16 = (p[0] & 0xFF) + 1;
                }
            } catch (Exception ignored) {}

            // =========================
            // 5) delivery_uid
            // =========================
            String deliveryUid = null;
            if (numeroLivraison != null && !numeroLivraison.trim().isEmpty()
                    && ticketNo != null && !ticketNo.isEmpty()) {
                deliveryUid = numeroLivraison + "-" + ticketNo;
            }

            // =========================
            // 6) Validations
            // =========================
            boolean serialMatch = true;
            if (expectedSerialId != null && !expectedSerialId.trim().isEmpty()) {
                serialMatch = expectedSerialId.trim().equalsIgnoreCase(serialId);
            }

            boolean productOk = true;
            if (expectedProduct1to16 != null && activeProduct1to16 != null) {
                productOk = expectedProduct1to16.intValue() == activeProduct1to16.intValue();
            }

            boolean compartmentOk = true;
            if (expectedCompartment != null) {
                compartmentOk = !expectedCompartment.trim().isEmpty();
            }

            // =========================
            // Payload commun
            // =========================
            JSONObject data = new JSONObject();
            data.put("lcrnode_dec", toAddr & 0xFF);
            data.put("lcrnode_hex", String.format("0x%02X", toAddr & 0xFF));

            data.put("ticketPending", ticketPending ? 1 : 0);
            data.put("deliveryActive", deliveryActive ? 1 : 0);
            data.put("flowActive", flowActive ? 1 : 0);

            data.put("ticket_no", ticketNo);
            data.put("serial_id", serialId);
            data.put("delivery_uid", deliveryUid == null ? JSONObject.NULL : deliveryUid);

            data.put("active_product", activeProduct1to16 == null ? JSONObject.NULL : activeProduct1to16);
            data.put("expected_product_number",
                    expectedProduct1to16 == null ? JSONObject.NULL : expectedProduct1to16);
            data.put("expected_compartment",
                    expectedCompartment == null ? JSONObject.NULL : expectedCompartment);

            data.put("serial_match", serialMatch ? 1 : 0);
            data.put("product_ok", productOk ? 1 : 0);
            data.put("compartment_ok", compartmentOk ? 1 : 0);

            // =========================
            // Décisions bloquantes
            // =========================
            if (ticketPending) {
                return ApiResult.fail(
                        "Validate: 0 - Ticket pending.",
                        Codes.ERR_TICKET_PENDING,
                        data
                );
            }

            if (deliveryActive) {
                return ApiResult.fail(
                        "Validate: 0 - Delivery active.",
                        Codes.ERR_DELIVERY_ACTIVE,
                        data
                );
            }

            if (!serialMatch) {
                return ApiResult.fail(
                        "Validate: 0 - Serial mismatch.",
                        Codes.ERR_SERIAL_ID_MISMATCH,
                        data
                );
            }

            if (!productOk) {
                return ApiResult.fail(
                        "Validate: 0 - Product mismatch.",
                        Codes.ERR_PRODUCT_MISMATCH,
                        data
                );
            }

            if (!compartmentOk) {
                return ApiResult.fail(
                        "Validate: 0 - Compartment missing/invalid.",
                        Codes.ERR_COMPARTMENT_MISMATCH,
                        data
                );
            }

            return ApiResult.ok("Validate: 1 - READY", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try {
                d.put("detail", e.getMessage() != null ? e.getMessage() : "");
            } catch (Exception ignored) {}
            return ApiResult.fail(
                    "Validate: 0 - LCP error.",
                    Codes.ERR_LCP_CONNECT_FAILED,
                    d
            );
        }
    }

    // =========================
    // Utils
    // =========================
    private static String decodeAz(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    

// =========================
// ✅ A2: Tagging erreurs par niveau (data.level/where/detail)
// =========================
private static void tagLevel(JSONObject data, String level, String where, Exception e) {
    if (data == null) return;
    try { data.put("level", level); } catch (Exception ignored) {}
    try { data.put("where", where); } catch (Exception ignored) {}
    if (e != null) {
        String m = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
        try { data.put("detail", m); } catch (Exception ignored) {}
    }
}
private static String readU32AsDec(byte[] be4) {
        if (be4 == null || be4.length < 4) return "";
        long u = ((be4[0] & 0xFFL) << 24)
               | ((be4[1] & 0xFFL) << 16)
               | ((be4[2] & 0xFFL) << 8)
               |  (be4[3] & 0xFFL);
        return String.valueOf(u & 0xFFFFFFFFL);
    }
}
