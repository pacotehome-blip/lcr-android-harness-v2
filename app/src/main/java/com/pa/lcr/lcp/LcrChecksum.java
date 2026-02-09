
package com.pa.lcr.lcp;

public class LcrChecksum {

    // CRC16/XMODEM pour COMMAND (0x22 READ, 0x20 WRITE)
    public static int crc16Xmodem(byte[] data) {
        int crc = 0x0000;
        for (byte b : data) {
            crc ^= ((b & 0xFF) << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0)
                    crc = (crc << 1) ^ 0x1021;
                else
                    crc <<= 1;
            }
        }
        return crc & 0xFFFF;
    }

    // Checksum additif READ legacy (avec SEED hiérarchique)
    public static int additive(byte[] data) {
        int sum = 0;
        for (byte b : data)
            sum = (sum + (b & 0xFF)) & 0xFF;
        return sum;
    }

    // XOR SEED FFFA pour page 03:00 (len=3)
    public static byte xorSeedFFFA(byte d0, byte d1) {
        return (byte) ((d0 ^ d1 ^ 0x04) & 0xFF);
    }
}
