
package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;

public final class UsbTransportIo implements TransportIo {

    private final String key; // "USB"
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key = key;
        this.port = port;
        this.description = (description != null ? description : "USB");
        this.generationId = generationId;
    }

    @Override public String getKey() { return key; }
    @Override public String describe() { return description; }
    @Override public boolean isOpen() { return !closed && port != null; }
    @Override public long getGenerationId() { return generationId; }

    @Override
    public int write(byte[] data, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (data == null || data.length == 0) return 0;

        int to = Math.max(0, timeoutMs);

        // ⚠️ Dans ta version, write() retourne void => on retourne data.length
        port.write(data, to);
        return data.length;
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;
        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        int n = port.read(buffer, to);
        android.util.Log.d("UsbTransportIo", "read to=" + to + " n=" + n);
        return n;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { port.close(); } catch (Exception ignored) {}
    }
}
