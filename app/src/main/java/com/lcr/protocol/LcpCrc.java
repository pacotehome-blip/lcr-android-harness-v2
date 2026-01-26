
package com.lcr.protocol;

public class LcpCrc {

    private static final int POLY = 0x1021;
    private static final int SEED = 0x7E7E;

    private static int crcUpdate(int crc, int b) {
        for (int i = 7; i >= 0; i--) {
            boolean fb = (crc & 0x8000) != 0;
            crc = ((crc << 1) & 0xFFFF) | ((b >> i) & 1);
            if (fb) crc ^= POLY;
        }
        return crc;
    }

    public static int crcLcp(byte[] data) {
        int crc = SEED;
        for (byte x : data)
            crc = crcUpdate(crc, x & 0xFF);
        return crc;
    }
}
