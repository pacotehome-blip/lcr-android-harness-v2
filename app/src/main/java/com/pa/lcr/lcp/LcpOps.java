
package com.pa.lcr.lcp;

public class LcpOps {

    private final LcpLink link;

    public LcpOps(LcpLink link) {
        if (link == null) throw new IllegalArgumentException("link null");
        this.link = link;
    }

    /* ================================================================
       ISSUE COMMAND (0x24,X)
       Identique Python : envoie 0x24 + code
       ================================================================ */
    public void opIssueCommand(int code, int timeoutMs, int pauseMs) throws Exception {
        byte[] pl = new byte[]{ (byte)0x24, (byte)(code & 0xFF) };
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] rsp = LcpLink.extractPayload(fr);

        if (rsp == null || rsp.length < 1)
            throw new Exception("Réponse issue cmd invalide");

        int rc = rsp[0] & 0xFF;
        if (rc != 0)
            throw new Exception(String.format("rc=%d pour cmd=0x%02X", rc, code));

        if (pauseMs > 0)
            Thread.sleep(pauseMs);
    }

    /* ================================================================
       GET_FIELD / READ FIELD (0x20)
       ================================================================ */
    public byte[] opGetField(int fieldId, int timeoutMs) throws Exception {
        int lo = fieldId & 0xFF;
        int hi = (fieldId >> 8) & 0xFF;

        byte[] pl = new byte[]{ 0x20, (byte)lo, (byte)hi };
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] rsp = LcpLink.extractPayload(fr);

        if (rsp == null || rsp.length < 2)
            throw new Exception("GET_FIELD: payload trop court");

        int rc = rsp[0] & 0xFF;
        if (rc != 0)
            throw new Exception(String.format("GET_FIELD rc=%d", rc));

        // reste = valeur brute du registre, variable
        byte[] data = new byte[rsp.length - 1];
        System.arraycopy(rsp, 1, data, 0, data.length);
        return data;
    }

    /* ================================================================
       SET_FIELD / WRITE FIELD (0x21)
       ================================================================ */
    public void opSetField(int fieldId, byte[] rawValue, int timeoutMs) throws Exception {
        int lo = fieldId & 0xFF;
        int hi = (fieldId >> 8) & 0xFF;

        byte[] pl = new byte[3 + rawValue.length];
        pl[0] = 0x21;
        pl[1] = (byte)lo;
        pl[2] = (byte)hi;
        System.arraycopy(rawValue, 0, pl, 3, rawValue.length);

        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] rsp = LcpLink.extractPayload(fr);

        if (rsp == null || rsp.length < 1)
            throw new Exception("SET_FIELD réponse invalide");

        int rc = rsp[0] & 0xFF;
        if (rc != 0)
            throw new Exception(String.format("SET_FIELD rc=%d", rc));
    }

    /* ================================================================
       DELIVERY STATUS (0x28)
       Retourne [DS, DC]
       ================================================================ */
    public int[] opDeliveryStatus(int timeoutMs, int pauseMs) throws Exception {
        byte[] fr = link.sendRecv(new byte[]{ 0x28 }, timeoutMs);
        byte[] p = LcpLink.extractPayload(fr);

        if (p == null || p.length < 6)
            throw new Exception("DEL_STATUS: payload len invalide");

        int rc = p[0] & 0xFF;
        if (rc != 0)
            throw new Exception("DEL_STATUS rc=" + rc);

        int devStatus = (p[3] & 0xFF) << 8 | (p[2] & 0xFF);
        int delStatus = (p[5] & 0xFF) << 8 | (p[4] & 0xFF);

        if (pauseMs > 0)
            Thread.sleep(pauseMs);

        return new int[]{ devStatus, delStatus };
    }

    /* ================================================================
       WAIT FOR A DELIVERY STATE (ex: delivery active, ticket clear…)
       ================================================================ */
    public int[] opWaitForStatus(int mask, int expected, int timeoutTotalMs, int pollDelayMs) throws Exception {
        long t0 = System.currentTimeMillis();

        while (true) {
            int[] dsdc = opDeliveryStatus(3000, pollDelayMs);
            int dc = dsdc[1];

            if ((dc & mask) == expected)
                return dsdc;

            if (System.currentTimeMillis() - t0 > timeoutTotalMs)
                throw new Exception("Timeout attente état DC");

            Thread.sleep(pollDelayMs);
        }
    }
}
