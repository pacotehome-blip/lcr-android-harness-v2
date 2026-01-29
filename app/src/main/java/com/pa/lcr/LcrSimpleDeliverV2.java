
package com.pa.lcr;

import com.pa.lcr.lcp.*;
import java.util.*;
import com.hoho.android.usbserial.driver.UsbSerialPort;

public class LcrSimpleDeliverV2 {

    public static final class Params {
        public UsbSerialPort port;
        public int toAddr;
        public int fromAddr;
        public Integer product;
        public String productName;
        public String productCode;
        public String mode = "auto";
        public Double preset = 0.0;
        public String startCmd = "0x00";
        public boolean noStart = false;
        public double startTimeout = 20.0;
        public boolean startAcceptFlow = false;
        public boolean startAcceptCounts = false;
        public double poll = 0.2;
        public int ticketTimeout = 60;
        public String recoverActive = "off";
        public String ticketPost = "if-pending";
        public boolean verbose = false;
        public boolean dumpTx = false;
        public boolean dumpRx = false;

        public String unlockUserKey;
        public boolean unlockUserKeyEmpty = false;
        public String unlockUserKeyHex;
        public boolean unlockTry0000 = false;
        public boolean unlockSetSecurity = false;
    }

    private final Params p;
    private final LcpOps ops;
    private final LcpLink link;

    public LcrSimpleDeliverV2(Params p, LcpOps ops) {
        this.p = p;
        this.ops = ops;
        this.link = ops.getLink();
    }

    /* ============================================================
       DECIMAL DIGIT MAP
       ============================================================ */
    private static int dd(int idx){
        switch(idx){
            case 0: return 2;
            case 1: return 1;
            case 2: return 0;
            case 3: return 3;
            default: return 2;
        }
    }

    /* ============================================================
       UNLOCK (security key)
       ============================================================ */
    public void unlock() throws Exception {
        if (p.unlockUserKey == null && !p.unlockUserKeyEmpty && p.unlockUserKeyHex == null)
            return;

        byte[] data;

        if (p.unlockUserKeyHex != null) {
            String s = p.unlockUserKeyHex.replace(" ", "").replace("_", "");
            int len = s.length()/2;
            data = new byte[len];
            for (int i=0; i<len; i++)
                data[i] = (byte)Integer.parseInt(s.substring(2*i, 2*i+2), 16);

        } else if (p.unlockUserKeyEmpty) {
            data = new byte[]{ 0x00 };

        } else {
            data = (p.unlockUserKey + "\0").getBytes("US-ASCII");
        }

        ops.opSetField(72, data, 5000);
        if (p.unlockSetSecurity)
            ops.opSetField(73, new byte[]{ 1 }, 5000);
    }

    /* ============================================================
       PRESTART (produit, preset, security)
       ============================================================ */
    public void prestart() throws Exception {

        // Machine status
        int[] ms = ops.opMachineStatusFull(5000, 200);
        int dc = ms[2];

        // If delivery/flow active → unlock if recoverActive != off
        if ((dc & (LcpOps.LCRSc_DELIVERY_ACTIVE | LcpOps.LCRSc_FLOW_ACTIVE)) != 0) {
            if (!"off".equals(p.recoverActive)) {
                ops.opIssueCommand(0x02, 5000, 200); // END
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 10000) {
                    dc = ops.opMachineStatusFull(5000, 200)[2];
                    if ((dc & (LcpOps.LCRSc_DELIVERY_ACTIVE | LcpOps.LCRSc_FLOW_ACTIVE)) == 0)
                        break;
                    Thread.sleep(200);
                }
            } else {
                throw new IllegalStateException("Delivery/Flow actifs");
            }
        }

        // Ticket pending ?
        dc = ops.opMachineStatusFull(5000, 200)[2];
        if ((dc & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0) {
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < p.ticketTimeout * 1000L) {
                ops.opIssueCommand(0x06, 5000, 200); // CLEAR
                Thread.sleep(200);
                dc = ops.opMachineStatusFull(5000, 200)[2];
                if ((dc & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0)
                    break;
            }
        }

        // Product selection
        if (p.product != null)
            ops.opSetField(0, new byte[]{ (byte)(p.product - 1) }, 5000);

        if (p.productName != null)
            ops.opSetField(12, filterAscii(p.productName, 28).getBytes("US-ASCII"), 5000);

        if (p.productCode != null)
            ops.opSetField(22, filterDigits(p.productCode, 8).getBytes("US-ASCII"), 5000);

        // Preset
        if (p.preset == null || p.preset == 0.0) {
            ops.opSetField(5, LcpOps.i32be(0), 5000);
            ops.opSetField(6, LcpOps.i32be(0), 5000);
            return;
        }

        int decIdx = getU8(39, 0);
        int digits = dd(decIdx);
        int scale  = (int)Math.pow(10, digits);
        int pdata  = (int)Math.round(p.preset * scale);

        if ("gross".equals(p.mode)) {
            ops.opSetField(5, LcpOps.i32be(pdata), 5000);
            ops.opSetField(6, LcpOps.i32be(0),     5000);
        } else {
            try {
                ops.opSetField(6, LcpOps.i32be(pdata), 5000);
                ops.opSetField(5, LcpOps.i32be(0),     5000);
            } catch (Exception e) {
                ops.opSetField(5, LcpOps.i32be(pdata), 5000);
                ops.opSetField(6, LcpOps.i32be(0),     5000);
            }
        }
    }

    /* ============================================================
       START (0x00 ou 0x01)
       ============================================================ */
    public void start() throws Exception {
        if (!p.noStart) {
            if ("0x00".equals(p.startCmd))
                ops.opIssueCommand(0x00, 5000, 200);
            else
                ops.opIssueCommand(0x01, 5000, 200);
        }

        long t0 = System.currentTimeMillis();
        int  g0 = safeI32(44);

        while (System.currentTimeMillis() - t0 < (long)(p.startTimeout * 1000)) {
            int[] ms = ops.opMachineStatusFull(5000, 200);
            int dc   = ms[2];

            boolean flow   = (dc & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LcpOps.LCRSc_BEGIN_DELIVERY) != 0;

            if (active || begin) return;
            if (p.startAcceptFlow && flow) return;

            if (p.startAcceptCounts) {
                int g = safeI32(44);
                if (g > g0) return;
            }

            Thread.sleep((long)(p.poll * 1000));
        }

        throw new java.util.concurrent.TimeoutException("START_TIMEOUT");
    }

    /* ============================================================
       LIVE LOOP — attend fin de flow/delivery
       ============================================================ */
    public Map<String,Object> liveLoop() throws Exception {

        int decIdx = getU8(39, 0);
        int digits = dd(decIdx);

        long start = System.currentTimeMillis();
        int g0 = safeI32(44), n0 = safeI32(45);
        int lg = g0, ln = n0;

        while (true) {
            int[] ms = ops.opMachineStatusFull(5000, 200);
            int dc   = ms[2];

            boolean flow   = (dc & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;

            int g = safeI32(44), n = safeI32(45);
            if (g != lg || n != ln) { lg = g; ln = n; }

            if (!flow && !active) {
                Thread.sleep((long)(p.poll * 1000));
                int dc2 = ops.opMachineStatusFull(5000, 200)[2];
                if ((dc2 & (LcpOps.LCRSc_FLOW_ACTIVE | LcpOps.LCRSc_DELIVERY_ACTIVE)) == 0)
                    break;
            }

            Thread.sleep((long)(p.poll * 1000));
        }

        long end = System.currentTimeMillis();

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("gross_delta", lg - g0);
        out.put("net_delta",   ln - n0);
        out.put("gross_end",   lg);
        out.put("net_end",     ln);
        out.put("start_ms",    start);
        out.put("end_ms",      end);
        return out;
    }

    /* ============================================================
       FINISH
       ============================================================ */
    public Map<String,Object> finish(Map<String,Object> liveData, String ticket) {
        int gt = safeI32(17);
        int nt = safeI32(18);

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("gross_total", gt);
        out.put("net_total",   nt);
        out.put("inventory_written", null);
        out.put("ticket_printed_host", true);
        return out;
    }

    /* ============================================================
       UTIL : fields
       ============================================================ */
    private int getU8(int f, int d){
        try{
            byte[] b = ops.opGetField(f, 5000);
            return (b != null && b.length >= 1) ? (b[0] & 0xFF) : d;
        }catch(Exception e){ return d; }
    }

    private int safeI32(int f){
        try{
            byte[] d = ops.opGetField(f, 5000);
            if (d != null && d.length >= 4) {
                return ((d[0] & 0xFF) << 24) |
                       ((d[1] & 0xFF) << 16) |
                       ((d[2] & 0xFF) << 8 ) |
                        (d[3] & 0xFF);
            }
        }catch(Exception ignored){}
        return 0;
    }

    private static String filterAscii(String s, int max){
        StringBuilder b = new StringBuilder();
        for(char c : s.toCharArray()) { if (c >= 32 && c <= 126) b.append(c); }
        String o = b.toString();
        return (o.length() > max) ? o.substring(0, max) : o;
    }

    private static String filterDigits(String s, int max){
        String d = s.replaceAll("\\D", "");
        if (d.isEmpty()) d = "0";
        return d.length() > max ? d.substring(0, max) : d;
    }
}
