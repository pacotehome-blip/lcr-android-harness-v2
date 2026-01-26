
package com.lcr.protocol;

import java.io.ByteArrayOutputStream;

public class LcpEscape {

    public static byte[] escape(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte b : in) {
            if (b == 0x1B || b == 0x7E)
                out.write(0x1B);
            out.write(b);
        }
        return out.toByteArray();
    }
}
