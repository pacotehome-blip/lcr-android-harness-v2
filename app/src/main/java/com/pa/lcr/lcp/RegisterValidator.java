
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class RegisterValidator {

    // LCR fields
    private static final int FIELD_ACTIVE_PRODUCT = 0;  // returns 0..15
    private static final int FIELD_TICKET_NUMBER  = 23; // U32
    private static final int FIELD_SERIAL_ID      = 80; // AZ

    // delCode bits (0x28)
    private static final int DC_TICKET_PENDING   = 0x0001;
    private static final int DC_FLOW_ACTIVE      = 0x0004;
    private static final int DC_DELIVERY_ACTIVE  = 0x0008;

    private RegisterValidator() {}

    public static final class Codes {
        public static final String ERR_NO_MEDIA            = "ERR_MEDIA_NOT_PRESENT";
        public static final String ERR_USB_NOT_READY       = "ERR_USB_PORT_NOT_READY";
        public static final String ERR_LCP_CONNECT_FAILED  = "ERR_LCP_CONNECT_FAILED";
        public static final String ERR_TICKET_PENDING      = "ERR_TICKET_PENDING";
        public static final String ERR_SERIAL_MISMATCH     = "ERR_SERIAL_ID_MISMATCH";
        public static final String ERR_PRODUCT_MISMATCH    = "ERR_PRODUCT_MISMATCH";
        public static final String ERR_COMPARTMENT_MISMATCH= "ERR_COMPARTMENT_MISMATCH";
        public static final String ERR_NODE_MISMATCH       = "ERR_LCP_NODE_MISMATCH";
    }

    /** Validation pure LCP (le média/USB est supposé déjà OK si port != null). */
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
            return ApiResult.fail("USB: 0 - Port non prêt.", Codes.ERR_USB_NOT_READY);
        }

        // Construire un link temporaire pour le TO demandé.
        LcpLink link = new LcpLink(port, toAddr, fromAddr, true);

        try {
            // 1) 0x28
            int[] ds = link.opDeliveryStatus();
            int delStatus = ds[0];
            int delCode = ds[1];

            boolean ticketPending  = (delCode & DC_TICKET_PENDING) != 0;
            boolean flowActive     = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

            // 2) ticket_no (#23)
            String ticketNo = readU32AsDec(link.opGetField(FIELD_TICKET_NUMBER));

            // 3) serial_id (#80)
            String serialId = decodeAz(link.opGetField(FIELD_SERIAL_ID));

            // 4) product active (#0)
            Integer activeProduct1to16 = null;
            try {
                byte[] p = link.opGetField(FIELD_ACTIVE_PRODUCT);
                if (p != null && p.length >= 1) {
                    int idx0 = p[0] & 0xFF;
                    activeProduct1to16 = idx0 + 1;
                }
            } catch (Exception ignored) {}

            // 5) delivery_uid si numero_livraison fourni
            String deliveryUid = null;
            if (numeroLivraison != null && !numeroLivraison.trim().isEmpty()
                    && ticketNo != null && !ticketNo.trim().isEmpty()) {
                deliveryUid = numeroLivraison + "-" + ticketNo;
            }

            // 6) checks
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
                // On ne peut pas “lire” un compartiment depuis le registre : on valide au moins la présence.
                compartmentOk = !expectedCompartment.trim().isEmpty();
            }

            // 7) Décision
            JSONObject data = new JSONObject();
            data.put("lcrnode_dec", toAddr);
            data.put("lcrnode_hex", String.format("0x%02X", toAddr & 0xFF));
            data.put("from_dec", fromAddr);
            data.put("from_hex", String.format("0x%02X", fromAddr & 0xFF));

            data.put("deliveryActive", deliveryActive ? 1 : 0);
            data.put("flowActive", flowActive ? 1 : 0);
            data.put("ticketPending", ticketPending ? 1 : 0);

            data.put("ticket_no", ticketNo);
            data.put("serial_id", serialId);
            data.put("delivery_uid", deliveryUid == null ? JSONObject.NULL : deliveryUid);

            data.put("active_product", activeProduct1to16 == null ? JSONObject.NULL : activeProduct1to16);
            data.put("expected_product", expectedProduct1to16 == null ? JSONObject.NULL : expectedProduct1to16);
            data.put("expected_compartment", expectedCompartment == null ? JSONObject.NULL : expectedCompartment);

            data.put("serial_match", serialMatch ? 1 : 0);
            data.put("product_ok", productOk ? 1 : 0);
            data.put("compartment_ok", compartmentOk ? 1 : 0);

            // READY si pas ticket pending et pas deliveryActive et serial match + product/compartment OK
            boolean ready = (!ticketPending && !deliveryActive && serialMatch && productOk && compartmentOk);

            if (ticketPending) {
                return ApiResult.fail("Validate: 0 - Ticket pending.", Codes.ERR_TICKET_PENDING, data);
            }
            if (!serialMatch) {
                return ApiResult.fail("Validate: 0 - Serial mismatch.", Codes.ERR_SERIAL_MISMATCH, data);
            }
            if (!productOk) {
                return ApiResult.fail("Validate: 0 - Product mismatch.", Codes.ERR_PRODUCT_MISMATCH, data);
            }
            if (!compartmentOk) {
                return ApiResult.fail("Validate: 0 - Compartment missing/invalid.", Codes.ERR_COMPARTMENT_MISMATCH, data);
            }

            return ready
                    ? ApiResult.ok("Validate: 1 - READY", data)
                    : ApiResult.fail("Validate: 0 - Not ready.", Codes.ERR_LCP_CONNECT_FAILED, data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            try { d.put("detail", (e.getMessage() != null) ? e.getMessage() : ""); } catch (Exception ignored) {}
            return ApiResult.fail("Validate: 0 - LCP connect failed.", Codes.ERR_LCP_CONNECT_FAILED, d);
        }
    }

    private static String decodeAz(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
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
