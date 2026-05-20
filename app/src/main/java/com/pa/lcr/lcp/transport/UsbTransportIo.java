package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * UsbTransportIo — lecture async via thread dédié.
 *
 * port.read() direct retourne 0 immédiatement sur Android 9 (Samsung SM-T397U)
 * avec adaptateur PL2303. SerialInputOutputManager échoue sur get_status avec ce chip.
 *
 * Solution : thread de lecture dédié qui appelle port.read() en boucle continue
 * et alimente un LinkedBlockingDeque. read() lit depuis ce buffer avec vrai blocage.
 */
public final class UsbTransportIo implements TransportIo {

    private final String key;
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    // Buffer async : le thread de lecture pousse les bytes ici
    private final LinkedBlockingDeque<Byte> rxBuffer = new LinkedBlockingDeque<>(65536);
    private Thread readerThread;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key          = key;
        this.port         = port;
        this.description  = (description != null ? description : "USB");
        this.generationId = generationId;
        startReaderThread();
    }

    private void startReaderThread() {
        if (port == null) return;
        readerThread = new Thread(() -> {
            byte[] buf = new byte[256];
            android.util.Log.i("UsbTransportIo", "reader thread démarré");
            while (!closed) {
                try {
                    int n = port.read(buf, 50);
                    if (n > 0) {
                        android.util.Log.d("UsbTransportIo", "reader n=" + n);
                        for (int i = 0; i < n; i++) {
                            rxBuffer.offerLast(buf[i]);
                        }
                    }
                } catch (Exception e) {
                    if (!closed) {
                        android.util.Log.w("UsbTransportIo", "reader err: " + e.getMessage());
                        try { Thread.sleep(100); } catch (Exception ignored) {}
                    }
                }
            }
            android.util.Log.i("UsbTransportIo", "reader thread arrêté");
        }, "UsbTransportIo-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
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
     * Lit depuis le buffer alimenté par le thread de lecture.
     * Bloque jusqu'à timeoutMs ms en attendant le premier byte.
     */
    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        long deadline = System.currentTimeMillis() + to;

        // Attend le premier byte
        int count = 0;
        while (count == 0 && System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            Byte b = rxBuffer.pollFirst(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (b != null) {
                buffer[count++] = b;
            }
        }

        // Ramasse le reste disponible immédiatement
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
        if (readerThread != null) readerThread.interrupt();
        try { port.close(); } catch (Exception ignored) {}
    }
}

