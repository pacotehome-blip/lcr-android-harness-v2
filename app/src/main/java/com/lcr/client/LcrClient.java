package com.lcr.client;

import com.lcr.protocol.LcpFrame;
import com.lcr.protocol.LcpFrameBuilder;
import com.lcr.protocol.LcpFrameReader;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class LcrClient {

    private final InputStream in;
    private final OutputStream out;
    private final LcpFrameBuilder builder;
    private final LcpFrameReader reader;

    public LcrClient(InputStream in, OutputStream out, int toAddr, int fromAddr) {
        this.in = in;
        this.out = out;
        this.builder = new LcpFrameBuilder(toAddr, fromAddr, true);
        this.reader = new LcpFrameReader(in);
    }

    /* ------------------------------------------------------------
     *  SEND / RECEIVE LCP FRAME
     * ------------------------------------------------------------ */
    public byte[] sendRecv(byte[] payload) throws Exception {
        byte[] frame = builder.buildFrame(payload);
        out.write(frame);
        out.flush();
        LcpFrame rsp = reader.readFrame();
        return rsp.raw();
    }

    public byte extractStatus(byte[] frame) {
        return frame[4];
    }

    public byte[] extractPayload(byte[] frame) {
        int len = frame[5] & 0xFF;
        return Arrays.copyOfRange(frame, 6, 6 + len);
    }

    /* ------------------------------------------------------------
     *  WAIT QUEUED (#7D) — IDENTIQUE PYTHON
     * ------------------------------------------------------------ */
    public byte[] waitQueued(double timeout, double poll) throws Exception {

        long t0 = System.currentTimeMillis();
        byte[] last = null;

        while ((System.currentTimeMillis() - t0) < timeout * 1000) {

            byte[] rsp = sendRecv(new byte[]{(byte) 0x7D});
            byte[] p = extractPayload(rsp);

            if (p != null) last = p;

            int rc = p[0] & 0xFF;

            if (rc == 0x26 || rc == 0x27) {  // REQUEST_QUEUED / NO_REQUEST_ACTIVE
                Thread.sleep((long) (poll * 1000));
                continue;
            }

            if (rc == 0x28)  // REQUEST_ABORTED
                throw new RuntimeException("Queued aborted");

            if (rc == 0x00 && p.length >= 3 && p[1] == 0x00)
                return Arrays.copyOfRange(p, 1, p.length);

            return p;
        }

        throw new RuntimeException(
                "Queued timeout last=" + (last != null ? bytesHex(last) : "null")
        );
    }

    /* ------------------------------------------------------------
     *  GET FIELD
     * ------------------------------------------------------------ */
    public byte[] opGetField(int fieldNum) throws Exception {
        byte[] rsp = sendRecv(new byte[]{0x20, (byte) fieldNum});
        byte st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        // Alignement Python
        if ((st & 0x04) != 0 || (p.length > 0 && p[0] == 0x26)) {
            p = waitQueued(5.0, 0.2);
        }

        if (p[0] != 0x00) {
            throw new RuntimeException("GET field #" + fieldNum +
                    " rc=0x" + String.format("%02X", p[0]));
        }

        return Arrays.copyOfRange(p, 2, p.length);
    }

    /* ------------------------------------------------------------
     *  SET FIELD
     * ------------------------------------------------------------ */
    public void opSetField(int fieldNum, byte[] data) throws Exception {
        byte[] payload = new byte[data.length + 2];
        payload[0] = 0x21;
        payload[1] = (byte) fieldNum;
        System.arraycopy(data, 0, payload, 2, data.length);

        byte[] rsp = sendRecv(payload);
        byte st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        // Alignement Python
        if ((st & 0x04) != 0 || (p.length > 0 && p[0] == 0x26)) {
            p = waitQueued(5.0, 0.2);
        }

        if (p[0] != 0x00) {
            throw new RuntimeException("SET field #" + fieldNum +
                    " rc=0x" + String.format("%02X", p[0]));
        }
    }

    /* ------------------------------------------------------------
     *  ISSUE COMMAND
     * ------------------------------------------------------------ */
    public byte[] opIssueCommand(int cmd) throws Exception {
        byte[] rsp = sendRecv(new byte[]{0x24, (byte) cmd});
        byte st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        // Alignement Python
        if ((st & 0x04) != 0 || (p.length > 0 && p[0] == 0x26)) {
            p = waitQueued(5.0, 0.2);
        }

        if (p[0] != 0x00) {
            throw new RuntimeException("Issue cmd=0x" + String.format("%02X", cmd) +
                    " rc=0x" + String.format("%02X", p[0]));
        }

        return p;
    }

    /* ------------------------------------------------------------
     *  UTIL
     * ------------------------------------------------------------ */
    private String bytesHex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
