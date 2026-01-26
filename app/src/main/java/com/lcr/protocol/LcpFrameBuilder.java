
package com.lcr.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public class LcpFrameBuilder {

    private final int toAddr;
    private final int fromAddr;
    private final boolean syncFirst;
    private byte msgId = 0;
    private boolean syncUsed = false;

    public LcpFrameBuilder(int toAddr, int fromAddr, boolean syncFirst) {
        this.toAddr = toAddr;
        this.fromAddr = fromAddr;
        this.syncFirst = syncFirst;
    }

    private byte nextStatus() {
        byte st = (byte)(msgId & 0x01);
        if (syncFirst && !syncUsed) {
            st |= 0x02;
            syncUsed = true;
        }
        msgId ^= 0x01;
        return st;
    }

    private byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public byte[] buildFrame(byte[] payload) throws Exception {

        byte st = nextStatus();

        byte[] header = new byte[]{
                (byte) toAddr,
                (byte) fromAddr,
                st,
                (byte) payload.length
        };

        byte[] var = concat(header, payload);

        byte[] varEsc = LcpEscape.escape(var);

        int crc = LcpCrc.crcLcp(varEsc);

        byte lo = (byte) (crc & 0xFF);
        byte hi = (byte) ((crc >> 8) & 0xFF);

        byte[] crcEsc = LcpEscape.escape(new byte[]{lo, hi});

        return concat(
                new byte[]{0x7E, 0x7E},
                varEsc,
                crcEsc
        );
    }
}
