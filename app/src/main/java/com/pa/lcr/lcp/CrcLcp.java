

package com.pa.lcr.lcp;

import java.io.ByteArrayOutputStream;

public final class CrcLcp {
  public static final int ESC   = 0x1B;
  public static final int TILDE = 0x7E;
  public static final int SEED  = 0x7E7E;
  public static final int POLY  = 0x1021;

  private CrcLcp() {}

  public static int crcUpdate(int crc, int b) {
    for (int i = 7; i >= 0; i--) {
      boolean fb = (crc & 0x8000) != 0;
      crc = ((crc << 1) & 0xFFFF) | ((b >> i) & 1);
      if (fb) crc ^= POLY;
    }
    return crc & 0xFFFF;
  }

  /** CRC calculé sur les octets ESCAPÉS (varEsc) */
  public static int crcLcp(byte[] data) {
    int c = SEED;
    for (byte x : data) c = crcUpdate(c, x & 0xFF);
    return c & 0xFFFF;
  }

  /** Ajoute 0x1B avant 0x1B/0x7E */
  public static byte[] escape(byte[] data) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte x : data) {
      int xi = x & 0xFF;
      if (xi == ESC || xi == TILDE) out.write(ESC);
      out.write(xi);
    }
    return out.toByteArray();
  }
}
