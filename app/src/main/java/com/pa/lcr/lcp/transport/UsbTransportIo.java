package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * UsbTransportIo — mode async via SerialInputOutputManager.
 *
 * port.read() direct retourne 0 immédiatement sur certains devices Android
 * (USB partagé MTP/ADB). On utilise SerialInputOutputManager (callback onNewData)
 * et on bufferise dans une LinkedBlockingDeque pour que read() soit bloquant.
 */
public final class UsbTransportIo implements TransportIo {

    private final String key;
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    // Buffer async : SerialInputOutputManager pousse les bytes ici via onNewData
    private final LinkedBlockingDeque<Byte> rxBuffer = new LinkedBlockingDeque<>(65536);
    private SerialInputOutputManager ioManager;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key         = key;
        this.port        = port;
        this.description = (description != null ? description : "USB");
        this.generationId = generationId;
        startIoManager();
    }

    private void startIoManager() {
        if (port == null) return;
        try {
            ioManager = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
                @Override
                public void onNewData(byte[] data) {
                    if (data == null) return;
                    for (byte b : data) {
                        rxBuffer.offerLast(b);   // drop si buffer plein (65536)
                    }
                }
                @Override
                public void onRunError(Exception e) {
                    android.util.Log.w("UsbTransportIo", "IoManager error: " + e.getMessage());
                }
            });
            ioManager.start();
            android.util.Log.i("UsbTransportIo", "SerialInputOutputManager démarré");
        } catch (Exception e) {
            android.util.Log.w("UsbTransportIo", "startIoManager failed: " + e.getMessage());
            ioManager = null;
        }
    }

    @Override public String  getKey()          { return key; }
    @Override public String  describe()        { return description; }
    @Override public boolean isOpen()          { return !closed && port != null; }
    @Override public long    getGenerationId() { return generationId; }

    @Override
    public int write(byte[] data, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (data == null || data.length == 0) return 0;
        port.write(data, Math.max(0, timeoutMs));
        return data.length;
    }

    /**
     * Lit jusqu'à buffer.length bytes depuis le buffer async.
     * Bloque jusqu'à timeoutMs ms en attendant le premier byte,
     * puis ramasse tous les bytes disponibles sans délai supplémentaire.
     *
     * Retourne 0 si timeout écoulé sans aucun byte (jamais -1 sauf fermé).
     */
    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        long deadline = System.currentTimeMillis() + to;

        // Attend le premier byte (bloquant avec deadline)
        int count = 0;
        while (count == 0 && System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            Byte b = rxBuffer.pollFirst(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (b != null) {
                buffer[count++] = b;
            }
        }

        // Ramasse les bytes restants disponibles immédiatement
        while (count < buffer.length) {
            Byte b = rxBuffer.pollFirst();
            if (b == null) break;
            buffer[count++] = b;
        }

        return count;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (ioManager != null) ioManager.stop();
        } catch (Exception ignored) {}
        try { port.close(); } catch (Exception ignored) {}
    }
}
