
package com.pa.lcr.lcp.transport;

import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;

public final class BtSppTransportIo implements TransportIo {

    private final String key; // "BT:MAC"
    private final BluetoothSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String description;
    private final long generationId;

    private volatile boolean closed = false;

    public BtSppTransportIo(String key,
                            BluetoothSocket socket,
                            InputStream in,
                            OutputStream out,
                            String description,
                            long generationId) {
        this.key = key;
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.description = (description != null ? description : key);
        this.generationId = generationId;
    }

    @Override public String getKey() { return key; }
    @Override public String describe() { return description; }

    @Override
    public boolean isOpen() {
        try {
            return !closed && socket != null && socket.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public long getGenerationId() { return generationId; }

    @Override
    public int write(byte[] data, int timeoutMs) throws Exception {
        if (closed || out == null) return -1;
        if (data == null || data.length == 0) return 0;
        out.write(data);
        out.flush();
        return data.length;
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || in == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        // timeoutMs: 0 => non-bloquant, <0 => bloquant, >0 => timeout
        if (timeoutMs < 0) {
            return in.read(buffer); // bloquant
        }

        final long deadline = System.currentTimeMillis() + timeoutMs;

        while (true) {
            int avail = 0;
            try { avail = in.available(); } catch (Exception ignored) {}

            if (avail > 0) {
                int toRead = Math.min(avail, buffer.length);
                return in.read(buffer, 0, toRead);
            }

            if (timeoutMs == 0) return 0;
            if (System.currentTimeMillis() >= deadline) return 0;

            try { Thread.sleep(5); } catch (InterruptedException ie) { return 0; }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }
}
