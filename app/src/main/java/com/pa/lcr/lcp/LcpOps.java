
package com.pa.lcr.lcp;

public class LcpOps {

    private final LcpLink link;

    public LcpOps(LcpLink link) {
        if (link == null) throw new IllegalArgumentException("link null");
        this.link = link;
    }

    public LcpLink getLink() {
        return link;
    }

    /* ============================================================
       CONSTANTES STATUTS DS / DC
       ============================================================ */
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;

    /* ============================================================
       UTIL i32 BE
       ============================================================ */
    public static byte[] i32be(int v) {
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8)  & 0xFF),
                (byte)(v & 0xFF)
        };
    }

    /* ============================================================
       GET_MACHINE (0x23)
       retourne [machineStatus, deviceStatus, deliveryCode]
       ============================================================ */
    public int[] opMachineStatusFull(int timeoutMs, int pauseMs) throws Exception {
        byte[] fr = link.sendRecv(new byte[]{ 0x23 }, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 8)
            throw new Exception("GET_MACHINE: payload invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("GET_MACHINE rc=" + rc);

        int ms = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
        int ds = (p[4] & 0xFF) | ((p[5] & 0xFF) << 8);
        int dc = (p[6] & 0xFF) | ((p[7] & 0xFF) << 8);

        if (pauseMs > 0) Thread.sleep(pauseMs);

        return new int[]{ ms, ds, dc };
    }

    /* ============================================================
       ISSUE COMMAND (0x24)
       ============================================================ */
    public void opIssueCommand(int code, int timeoutMs, int pauseMs) throws Exception {
        byte[] pl = new byte[]{ 0x24, (byte)(code & 0xFF) };
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("IssueCommand: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("IssueCommand rc=" + rc);

        if (pauseMs > 0) Thread.sleep(pauseMs);
    }

    /* ============================================================
       GET_FIELD (0x20)
       ============================================================ */
    public byte[] opGetField(int fieldId, int timeoutMs) throws Exception {
        int lo = fieldId & 0xFF;
        int hi = (fieldId >> 8) & 0xFF;

        byte[] pl = new byte[]{ 0x20, (byte)lo, (byte)hi };
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("GET_FIELD: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("GET_FIELD rc=" + rc);

        byte[] val = new byte[p.length - 1];
        System.arraycopy(p, 1, val, 0, val.length);
        return val;
    }

    /* ============================================================
       SET_FIELD (0x21)
       ============================================================ */
    public void opSetField(int fieldId, byte[] rawValue, int timeoutMs) throws Exception {
        int lo = fieldId & 0xFF;
        int hi = (fieldId >> 8) & 0xFF;

        byte[] pl = new byte[3 + rawValue.length];
        pl[0] = 0x21;
        pl[1] = (byte)lo;
        pl[2] = (byte)hi;
        System.arraycopy(rawValue, 0, pl, 3, rawValue.length);

        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("SET_FIELD: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("SET_FIELD rc=" + rc);
    }

    /* ============================================================
       GET_DEL_STATUS (0x28)
       retourne [ds, dc]
       ============================================================ */
    public int[] opDeliveryStatus(int timeoutMs, int pauseMs) throws Exception {
        byte[] fr = link.sendRecv(new byte[]{ 0x28 }, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 6)
            throw new Exception("DEL_STATUS: payload invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("DEL_STATUS rc=" + rc);

        int ds = ((p[3] & 0xFF) << 8) | (p[2] & 0xFF);
        int dc = ((p[5] & 0xFF) << 8) | (p[4] & 0xFF);

        if (pauseMs > 0) Thread.sleep(pauseMs);

        return new int[]{ ds, dc };
    }

    /* ============================================================
       WAIT DC
       ============================================================ */
    public int[] opWaitForStatus(int mask, int expected, int timeoutMs, int pollMs) throws Exception {
        long t0 = System.currentTimeMillis();

        while (true) {
            int[] dsdc = opDeliveryStatus(3000, pollMs);
            int dc = dsdc[1];

            if ((dc & mask) == expected)
                return dsdc;

            if (System.currentTimeMillis() - t0 > timeoutMs)
                throw new Exception("Timeout attente état DC");

            Thread.sleep(pollMs);
        }
    }
}
