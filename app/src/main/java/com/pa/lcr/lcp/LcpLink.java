
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

/**
 * LcpLink
 *
 * - Encodage / décodage LCP
 * - RS-232 multi-drop SAFE
 * - Filtrage RX par adresse (TO == hostAddr)
 * - Gestion RC=0x26 (REQUEST QUEUED) + MSG_CHECK_REQUEST (0x7D)
 * - AUCUN sync agressif
 *
 * Aligné DSK + scripts terrain.
 */
public final class LcpLink {

    /* ==========================================================
     * Constantes LCP
     * ========================================================== */

    public static final byte SYNC = 0x7E;

    // Delivery Status flags
    public static final int LCRSc_DELIVERY_ACTIVE = 0x01;
    public static final int LCRSc_FLOW_ACTIVE     = 0x02;

    // Return codes (RC)
    private static final int RC_OK              = 0x00;
    private static final int RC_RESPONSE_OK     = 0x82;
    private static final int RC_REQUEST_QUEUED  = 0x26;

    // Messages
    private static final byte MSG_CHECK_REQUEST = 0x7D;

    /* ==========================================================
     * Port série + adresses
     * ========================================================== */

    private final UsbSerialPort port;
    private final int toAddr;     // LCR node ciblé
    private final int hostAddr;   // Adresse host (FROM)

    /* ==========================================================
     * Construction
     * ========================================================== */

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean verbose) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
    }

    /* ==========================================================
     * API publique (inchangée)
     * ========================================================== */

    public byte[] opGetField(int field) throws IOException {
        return sendRecv(buildGetField(field));
    }

    public void opSetField(int field, byte[] value) throws IOException {
        sendRecv(buildSetField(field, value));
    }

    public void opIssueCommand(int cmd) throws IOException {
        sendRecv(buildIssueCommand(cmd));
    }

    public int[] opDeliveryStatus() throws IOException {
        byte[] pl = sendRecv(new byte[]{0x28});
        int ds = pl.length > 0 ? pl[0] & 0xFF : 0;
        int dc = pl.length > 1 ? pl[1] & 0xFF : 0;
        return new int[]{ds, dc};
    }

    public void opGetProductId() throws IOException {
        sendRecv(new byte[]{0x00});
    }

    /* ==========================================================
     * CŒUR TX/RX — AVEC QUEUED (0x26) + 0x7D
     * ========================================================== */

    private synchronized byte[] sendRecv(byte[] payload) throws IOException {

        // 1) Envoi de la requête initiale
        byte[] frame = encodeFrame(payload);
        port.write(frame, 200);

        long deadline = System.currentTimeMillis() + 3000;
        boolean queued = false;

        while (System.currentTimeMillis() < deadline) {

            Frame rx = readFrame();
            if (rx == null) {
                // bruit / broadcast / sync / trame non pertinente
                continue;
            }

            // ✅ FILTRAGE MULTI-DROP
            if (rx.to != hostAddr) {
                // trame valide mais pas pour nous
                continue;
            }

            // 2) Analyse du code de retour
            int rc = rx.rc & 0xFF;

            switch (rc) {

                case RC_OK:
                case RC_RESPONSE_OK:
                    // ✅ Réponse finale OK
                    return rx.payload;

                case RC_REQUEST_QUEUED:
                    // ✅ Requête acceptée mais pas encore exécutée
                    queued = true;
                    break;

                default:
                    // ❌ Erreur réelle
                    throw new IOException("LCP error rc=0x"
                            + Integer.toHexString(rc));
            }

            // 3) Si queued → interroger via MSG_CHECK_REQUEST (0x7D)
            if (queued) {
                try {
                    // laisse respirer le bus multi-drop
                    Thread.sleep(80);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting queued response");
                }

                byte[] checkFrame = encodeFrame(new byte[]{MSG_CHECK_REQUEST});
                port.write(checkFrame, 200);
                queued = false;
            }
        }

        throw new IOException("Timeout waiting LCP response");
    }

    /* ==========================================================
     * Lecture RX (multi-drop SAFE)
     * ========================================================== */

    private Frame readFrame() throws IOException {

        int b;

        // Recherche SYNC SYNC
        do {
            b = readByte();
            if (b < 0) return null;
        } while (b != SYNC);

        if (readByte() != SYNC) return null;

        int to   = readByte();
        int from = readByte();
        int st   = readByte();
        int len  = readByte();

        if (len < 0 || len > 255) {
            discard(len);
            return null;
        }

        byte[] payload = readBytes(len);
        int crcHi = readByte();
        int crcLo = readByte();

        if (!crcOk(to, from, st, len, payload, crcHi, crcLo)) {
            // CRC invalide → ignorer silencieusement
            return null;
        }

        return new Frame(to, from, st, payload);
    }

    /* ==========================================================
     * Encodage frame
     * ========================================================== */

    private byte[] encodeFrame(byte[] payload) {

        int len = payload.length;
        byte[] frame = new byte[len + 8];

        frame[0] = SYNC;
        frame[1] = SYNC;
        frame[2] = (byte) toAddr;
        frame[3] = (byte) hostAddr;
        frame[4] = 0x02;            // status host → request
        frame[5] = (byte) len;

        System.arraycopy(payload, 0, frame, 6, len);

        int crc = crc16(frame, 2, len + 4);
        frame[len + 6] = (byte) ((crc >> 8) & 0xFF);
        frame[len + 7] = (byte) (crc & 0xFF);

        return frame;
    }

    /* ==========================================================
     * IO bas niveau
     * ========================================================== */

    private int readByte() throws IOException {
        byte[] b = new byte[1];
        int r = port.read(b, 200);
        return r == 1 ? (b[0] & 0xFF) : -1;
    }

    private byte[] readBytes(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            off += port.read(b, off, n - off);
        }
        return b;
    }

    private void discard(int n) throws IOException {
        if (n <= 0) return;
        readBytes(n);
    }

    /* ==========================================================
     * CRC
     * ========================================================== */

    private boolean crcOk(int to, int from, int st, int len,
                          byte[] pl, int hi, int lo) {

        byte[] tmp = new byte[len + 4];
        tmp[0] = (byte) to;
        tmp[1] = (byte) from;
        tmp[2] = (byte) st;
        tmp[3] = (byte) len;
        System.arraycopy(pl, 0, tmp, 4, len);

        int crc = crc16(tmp, 0, tmp.length);
        return ((crc >> 8) & 0xFF) == hi && (crc & 0xFF) == lo;
    }

    private int crc16(byte[] b, int off, int len) {
        int crc = 0x0000;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0
                        ? (crc << 1) ^ 0x1021
                        : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }

    /* ==========================================================
     * Frame interne
     * ========================================================== */

    private static final class Frame {
        final int to;
        final int from;
        final int rc;
        final byte[] payload;

        Frame(int to, int from, int rc, byte[] payload) {
            this.to = to;
            this.from = from;
            this.rc = rc;
            this.payload = payload;
        }
    }

    /* ==========================================================
     * Builders payload
     * ========================================================== */

    private byte[] buildGetField(int field) {
        return new byte[]{0x20, (byte) field};
    }

    private byte[] buildSetField(int field, byte[] val) {
        byte[] b = new byte[2 + val.length];
        b[0] = 0x21;
        b[1] = (byte) field;
        System.arraycopy(val, 0, b, 2, val.length);
        return b;
    }

    private byte[] buildIssueCommand(int cmd) {
        return new byte[]{0x24, (byte) cmd};
    }
}
