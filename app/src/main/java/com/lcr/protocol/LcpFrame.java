
package com.lcr.protocol;

public class LcpFrame {

    private final byte[] header;
    private final byte[] payload;

    public LcpFrame(byte[] header, byte[] payload) {
        this.header = header;
        this.payload = payload;
    }

    public byte[] header() {
        return header;
    }

    public byte[] payload() {
        return payload;
    }

    /**
     * Reconstruit la trame logique brute (~ sans CRC et sans escape)
     * Utile pour l'API comme ton python LCPLink.extract_payload().
     */
    public byte[] raw() {
        byte[] out = new byte[6 + payload.length];
        // ~~ déjà retirés par reader
        // header
        System.arraycopy(header, 0, out, 0, 4);
        // length = header[3]
        out[4] = header[2]; // st
        out[5] = header[3]; // len
        // payload
        System.arraycopy(payload, 0, out, 6, payload.length);
        return out;
    }
}
