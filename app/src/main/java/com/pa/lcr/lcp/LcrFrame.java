
package com.pa.lcr.lcp;

import java.util.Arrays;

public class LcrFrame {

    public static byte[] buildReadFrame(int reg, int length) {
        byte hi = (byte) ((reg >> 8) & 0xFF);
        byte lo = (byte) (reg & 0xFF);

        byte[] body = new byte[] {
                0x01,       // STX
                0x22,       // READ
                hi,
                lo,
                (byte) length
        };

        int crc = LcrChecksum.crc16Xmodem(body);
        byte[] frame = Arrays.copyOf(body, body.length + 2);

        frame[frame.length - 2] = (byte) ((crc >> 8) & 0xFF);
        frame[frame.length - 1] = (byte) (crc & 0xFF);

        return frame;
    }

    public static byte[] buildWriteFrame(int reg, byte[] payload) {
        byte hi = (byte) ((reg >> 8) & 0xFF);
        byte lo = (byte) (reg & 0xFF);

        byte[] body = new byte[4 + payload.length];
        body[0] = 0x01;
        body[1] = 0x20; // WRITE
        body[2] = hi;
        body[3] = lo;
        System.arraycopy(payload, 0, body, 4, payload.length);

        int crc = LcrChecksum.crc16Xmodem(body);
        byte[] frame = Arrays.copyOf(body, body.length + 2);
        frame[frame.length - 2] = (byte) ((crc >> 8) & 0xFF);
        frame[frame.length - 1] = (byte) (crc & 0xFF);

        return frame;
    }

    public static byte[] buildPollFrame() {
        return new byte[] { 0x01, 0x28 };
    }
}
