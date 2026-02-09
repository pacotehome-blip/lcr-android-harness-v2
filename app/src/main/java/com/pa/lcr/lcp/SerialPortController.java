
package com.example.lcr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialPortController {

    private final InputStream in;
    private final OutputStream out;

    private static final int READ_TIMEOUT_MS = 1500;
    private static final int MAX_RETRIES = 3;

    public SerialPortController(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public synchronized void write(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    public synchronized byte[] readExact(int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        long start = System.currentTimeMillis();

        while (offset < length) {
            if (System.currentTimeMillis() - start > READ_TIMEOUT_MS) {
                throw new IOException("Timeout: expected " + length + " bytes, got " + offset);
            }

            int n = in.read(buffer, offset, length - offset);
            if (n > 0) offset += n;
        }
        return buffer;
    }

    public synchronized byte[] transaction(byte[] request, int expectedResponseLength)
            throws IOException {

        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                write(request);
                return readExact(expectedResponseLength);

            } catch (IOException ex) {
                lastError = ex;

                if (attempt == MAX_RETRIES)
                    break;

                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }

        throw new IOException("Transaction failed after " + MAX_RETRIES + " attempts", lastError);
    }
}
