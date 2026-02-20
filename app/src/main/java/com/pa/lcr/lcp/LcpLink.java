
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;

public final class LcpLink {

    public static final byte SYNC = 0x7E;

    public static final int LCRSc_DELIVERY_ACTIVE = 0x01;
    public static final int LCRSc_FLOW_ACTIVE     = 0x02;

    private static final int RC_OK              = 0x00;
    private static final int RC_RESPONSE_OK     = 0x82;
    private static final int RC_REQUEST_QUEUED  = 0x26;

    private static final byte MSG_CHECK_REQUEST = 0x7D;

    private final UsbSerialPort port;
    private final int toAddr;
    private final int hostAddr;

    // ✅ NOUVEAU : dernier node qui a réellement répondu
    private volatile Integer lastResponderNode = null;

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean verbose) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
    }

    // ✅ NOUVEAU : accès lecture pour le controller / UI
    public Integer getLastResponderNode() {
        return lastResponderNode;
    }

    public byte[] opGetField(int field) throws IOException {
        return sendRecv(new byte[]{0x20, (byte) field});
    }

    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + value.length];
        pl[0] = 0x21;
        pl[1] = (byte) field;
        System.arraycopy(value, 0, pl, 2, value.length);
        sendRecv(pl);
    }

    public void opIssueCommand(int cmd) throws IOException {
        sendRecv(new byte[]{0x24, (byte) cmd});
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

    private synchronized byte[] sendRecv(byte[] payload) throws IOException {

        byte[] frame = encodeFrame(payload);
        port.write(frame, 200);

        long deadline = System.currentTimeMillis() + 3000;
        boolean queued = false;

        while (System.currentTimeMillis() < deadline) {

            Frame rx = readFrame();
            if (rx == null) continue;

            if (rx.to != hostAddr) continue;

            int rc = rx.rc & 0xFF;

            switch (rc) {
                case RC_OK:
                case RC_RESPONSE_OK:
                    // ✅ mémorise le node réel
                    lastResponderNode = rx.from;

                    System.out.println(String.format(
                            "[LCP] Réponse reçue : FROM=%d (0x%02X) → TO=%d (0x%02X)",
                            rx.from, rx.from, rx.to, rx.to
                    ));
                    return rx.payload;

                case RC_REQUEST_QUEUED:
                    queued = true;
                    break;

                default:
                    throw new IOException("LCP error rc=0x" +
                            Integer.toHexString(rc));
            }

            if (queued) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                byte[] chk = encodeFrame(new byte[]{MSG_CHECK_REQUEST});
                port.write(chk, 200);
                queued = false;
            }
        }

        throw new IOException("Timeout waiting LCP response");
    }

    private byte[] encodeFrame(byte[] payload) {
        int len = payload.length;
        byte[] frame = new byte[len + 8];
        frame[0] = SYNC;
        frame[1] = SYNC;
        frame[2] = (byte) toAddr;
        frame[3] = (byte) hostAddr;
        frame[4] = 0x02;
        frame[5] = (byte) len;
        System.arraycopy(payload, 0, frame, 6, len);
        int crc = crc16(frame, 2, len + 4);
        frame[len + 6] = (byte) ((crc >> 8) & 0xFF);
        frame[len + 7] = (byte) (crc & 0xFF);
        return frame;
    }

    private Frame readFrame() throws IOException {
        int b;
        do {
            b = readByte();
            if (b < 0) return null;
        } while (b != SYNC);

        if (readByte() != SYNC) return null;

        int to = readByte();
        int from = readByte();
        int rc = readByte();
        int len = readByte();

        byte[] payload = readBytes(len);
        int hi = readByte();
        int lo = readByte();

        if (!crcOk(to, from, rc, len, payload, hi, lo)) return null;
        return new Frame(to, from, rc, payload);
    }

    private int readByte() throws IOException {
        byte[] b = new byte[1];
        return port.read(b, 200) == 1 ? b[0] & 0xFF : -1;
    }

    private byte[] readBytes(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) off += port.read(b, off, n - off);
        return b;
    }

    private boolean crcOk(int to, int from, int rc, int len,
                          byte[] pl, int hi, int lo) {
        byte[] tmp = new byte[len + 4];
        tmp[0] = (byte) to;
        tmp[1] = (byte) from;
        tmp[2] = (byte) rc;
        tmp[3] = (byte) len;
        System.arraycopy(pl, 0, tmp, 4, len);
        int crc = crc16(tmp, 0, tmp.length);
        return ((crc >> 8) & 0xFF) == hi && (crc & 0xFF) == lo;
    }

    private int crc16(byte[] b, int off, int len) {
        int crc = 0;
        for (int i = off; i < off + len; i++) {
            crc ^= (b[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }

    private static final class Frame {
        final int to, from, rc;
        final byte[] payload;
        Frame(int to, int from, int rc, byte[] payload) {
            this.to = to; this.from = from; this.rc = rc; this.payload = payload;
        }
    }
}
