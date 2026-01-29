
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
       CONSTANTES STATUTS DS / DC (Delivery Status/Code)
       ============================================================ */
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;

    /* ============================================================
       RETURN CODES (queued handling)
       ============================================================ */
    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26; // 38
    public static final int RC_NO_REQUEST_ACTIVE = 0x27; // 39
    public static final int RC_REQUEST_ABORTED   = 0x28; // 40

    /* ============================================================
       UTIL i32 BE (signed big-endian)
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
       CHECK_REQUEST (0x7D) — gestion des requêtes différées (rc=0x26)
       - Boucle jusqu'à obtenir la réponse réelle (rc=0x00) ou erreur.
       - Normalisation "double 0x00" (ex.: [0x00, 0x00, ...] → [0x00, ...]).
       ============================================================ */
    public byte[] opCheckRequest(int timeoutMs, int pollMs) throws Exception {
        long t0 = System.currentTimeMillis();
        int wait = Math.max(100, pollMs);

        while (System.currentTimeMillis() - t0 < timeoutMs) {
            byte[] fr = link.sendRecv(new byte[]{ (byte)0x7D }, timeoutMs);
            byte[] rep = LcpLink.extractPayload(fr);
            if (rep == null || rep.length == 0) { Thread.sleep(wait); continue; }

            int rc0 = rep[0] & 0xFF;
            if (rc0 == RC_REQUEST_QUEUED) { Thread.sleep(wait); continue; }
            if (rc0 == RC_NO_REQUEST_ACTIVE) throw new Exception("CheckRequest: 0x27 NO_REQUEST_ACTIVE");
            if (rc0 == RC_REQUEST_ABORTED)   throw new Exception("CheckRequest: 0x28 REQUEST_ABORTED");

            // Normalisations : certains FW renvoient [0x00, 0x00, ...]
            if (rep.length == 2 && rc0 == RC_OK) {
                return rep; // [0x00, ...]
            }
            if (rc0 == RC_OK && rep.length >= 3 && (rep[1] & 0xFF) == RC_OK) {
                byte[] norm = new byte[rep.length - 1];
                System.arraycopy(rep, 1, norm, 0, norm.length);
                return norm;
            }
            return rep;
        }
        throw new Exception("Timeout CHECK_REQUEST (queued handling).");
    }

    /* ============================================================
       GET_MACHINE (0x23) → [ms, ds, dc] — queued-handling + big-endian
       ============================================================ */
    public int[] opMachineStatusFull(int timeoutMs, int pauseMs) throws Exception {
        byte[] fr = link.sendRecv(new byte[]{ 0x23 }, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("GET_MACHINE: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc == RC_REQUEST_QUEUED) {
            p = opCheckRequest(timeoutMs, Math.max(100, pauseMs));
            rc = p[0] & 0xFF;
        }
        if (rc != RC_OK)
            throw new Exception("GET_MACHINE rc=" + rc);

        if (p.length < 8)
            throw new Exception("GET_MACHINE: payload invalide (<8)");

        // Big-endian: [rc, dev(1), MS(2), DS(2), DC(2)] → MS/DS/DC en BE
        int ms = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int ds = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
        int dc = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);

        if (pauseMs > 0) Thread.sleep(pauseMs);
        return new int[]{ ms, ds, dc };
    }

    /* ============================================================
       ISSUE COMMAND (0x24, cmd) — gère rc=0x26 via CHECK_REQUEST
       ============================================================ */
    public void opIssueCommand(int code, int timeoutMs, int pauseMs) throws Exception {
        byte[] pl = new byte[]{ 0x24, (byte)(code & 0xFF) };
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("IssueCommand: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc == RC_REQUEST_QUEUED) {
            byte[] rep = opCheckRequest(timeoutMs, Math.max(100, pauseMs));
            rc = rep[0] & 0xFF;
        }

        if (rc != RC_OK)
            throw new Exception(String.format("IssueCommand rc=0x%02X (cmd 0x%02X)", rc, code));

        if (pauseMs > 0) Thread.sleep(pauseMs);
    }

    /* ============================================================
       GET_FIELD (0x20) — ID 1 octet (format SR260/LCR-II)
       ============================================================ */
    public byte[] opGetField(int fieldId, int timeoutMs) throws Exception {
        if (fieldId < 0 || fieldId > 0xFF)
            throw new IllegalArgumentException("fieldId must be 0..255 for SR260/LCR-II");

        byte[] pl = new byte[]{ 0x20, (byte)(fieldId & 0xFF) }; // 1 OCTET d'ID
        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("GET_FIELD: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc != RC_OK)
            throw new Exception("GET_FIELD rc=" + rc);

        byte[] val = new byte[p.length - 1];
        System.arraycopy(p, 1, val, 0, val.length);
        return val;
    }

    /* ============================================================
       SET_FIELD (0x21) — ID 1 octet (format SR260/LCR-II)
       ============================================================ */
    public void opSetField(int fieldId, byte[] rawValue, int timeoutMs) throws Exception {
        if (fieldId < 0 || fieldId > 0xFF)
            throw new IllegalArgumentException("fieldId must be 0..255 for SR260/LCR-II");

        if (rawValue == null) rawValue = new byte[0];

        byte[] pl = new byte[2 + rawValue.length];
        pl[0] = 0x21;
        pl[1] = (byte)(fieldId & 0xFF); // 1 OCTET d'ID
        System.arraycopy(rawValue, 0, pl, 2, rawValue.length);

        byte[] fr = link.sendRecv(pl, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("SET_FIELD: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc != RC_OK)
            throw new Exception("SET_FIELD rc=" + rc);
    }

    /* ============================================================
       GET_DEL_STATUS (0x28) → [ds, dc] — queued-handling + big-endian
       ============================================================ */
    public int[] opDeliveryStatus(int timeoutMs, int pauseMs) throws Exception {
        byte[] fr = link.sendRecv(new byte[]{ 0x28 }, timeoutMs);
        byte[] p  = LcpLink.extractPayload(fr);

        if (p == null || p.length < 1)
            throw new Exception("DEL_STATUS: réponse invalide");

        int rc = p[0] & 0xFF;
        if (rc == RC_REQUEST_QUEUED) {
            p = opCheckRequest(timeoutMs, Math.max(100, pauseMs));
            rc = p[0] & 0xFF;
        }
        if (rc != RC_OK)
            throw new Exception("DEL_STATUS rc=" + rc);

        if (p.length < 6)
            throw new Exception("DEL_STATUS: payload trop court (<6)");

        // Big-endian: DS=(p[2]<<8)|p[3], DC=(p[4]<<8)|p[5]
        int ds = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int dc = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);

        if (pauseMs > 0) Thread.sleep(pauseMs);
        return new int[]{ ds, dc };
    }

    /* ============================================================
       WAIT DC (mask/expected) avec polling 0x28
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
