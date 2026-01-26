
package com.lcr.protocol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class LcpFrameReader {

    private final InputStream in;
    private final ByteArrayOutputStream rawBuffer = new ByteArrayOutputStream();

    public LcpFrameReader(InputStream in) {
        this.in = in;
    }

    private int readByteRaw() throws Exception {
        int b = in.read();
        if (b < 0) return -1;

        if (b == 0x1B) {
            int nxt = in.read();
            if (nxt < 0) return -1;
            rawBuffer.write((byte)0x1B);
            rawBuffer.write((byte)nxt);
            return nxt;
        } else {
            rawBuffer.write((byte)b);
            return b;
        }
    }

    public LcpFrame readFrame() throws Exception {

        // sync ~~ 
        int sync = 0;
        while (true) {
            int b = in.read();
            if (b < 0) throw new Exception("Sync timeout");
            if (b == 0x7E) sync++;
            else sync = 0;
            if (sync == 2) break;
        }

        rawBuffer.reset();

        byte[] hdr = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = readByteRaw();
            if (v < 0) throw new Exception("Header timeout");
            hdr[i] = (byte) v;
        }

        int plen = hdr[3] & 0xFF;
        byte[] data = new byte[plen];

        for (int i = 0; i < plen; i++) {
            int v = readByteRaw();
            if (v < 0) throw new Exception("Payload timeout");
            data[i] = (byte) v;
        }

        byte[] crcBytes = new byte[2];
        for (int i = 0; i < 2; i++) {
            int v = readByteRaw();
            if (v < 0) throw new Exception("CRC timeout");
            crcBytes[i] = (byte) v;
        }

        byte[] rawEsc = rawBuffer.toByteArray();
        int calc = LcpCrc.crcLcp(rawEsc);
        int recv = (crcBytes[1] & 0xFF) << 8 | (crcBytes[0] & 0xFF);

        if (calc != recv)
            throw new Exception(String.format("CRC mismatch recv=%04X calc=%04X", recv, calc));

        return new LcpFrame(hdr, data);
    }
}
