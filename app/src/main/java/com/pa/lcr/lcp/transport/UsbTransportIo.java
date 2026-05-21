package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;

/**
 * UsbTransportIo — calqué sur BtSppTransportIo.
 *
 * port.read() retourne 0 immédiatement sur Android 9 / PL2303 quand aucun byte
 * n'est disponible (non-bloquant). On reproduit la même stratégie que BT :
 * polling avec in.available() → ici remplacé par port.read(1byte, 0) pour tester
 * la disponibilité, + Thread.sleep(5) si vide, jusqu'à deadline.
 */
public final class UsbTransportIo implements TransportIo {

    private final String key;
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key          = key;
        this.port         = port;
        this.description  = (description != null ? description : "USB");
        this.generationId = generationId;
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
     * Calqué sur BtSppTransportIo.read() :
     * - Tente port.read() avec timeout court (0ms = non-bloquant)
     * - Si n>0 → retourne les bytes
     * - Si n==0 → sleep(5) et réessaie jusqu'à deadline
     * - Retourne 0 si deadline atteinte sans bytes
     */
    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        if (timeoutMs == 0) {
            return port.read(buffer, 0);
        }

        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        final long deadline = System.currentTimeMillis() + to;

        while (true) {
            // Tentative non-bloquante
            int n = port.read(buffer, 0);
            if (n > 0) return n;

            if (timeoutMs == 0) return 0;

            if (System.currentTimeMillis() >= deadline) return 0;

            try { Thread.sleep(5); } catch (InterruptedException ie) { return 0; }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { port.close(); } catch (Exception ignored) {}
    }
}
