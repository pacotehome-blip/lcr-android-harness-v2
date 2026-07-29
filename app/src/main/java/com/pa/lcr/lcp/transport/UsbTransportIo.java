package com.pa.lcr.lcp.transport;

import com.hoho.android.usbserial.driver.UsbSerialPort;

public final class UsbTransportIo implements TransportIo {

    private final String key;
    private final UsbSerialPort port;
    private final String description;
    private final long generationId;
    private volatile boolean closed = false;

    // ✅ FIX (même cause racine que BT) : isOpen() ne vérifiait QUE si l'objet
    // port existait encore en mémoire — jamais si le câble était réellement
    // encore branché. Un débranchement physique laissait isOpen()=true
    // indéfiniment, rendant toute la détection en amont inopérante. On
    // ferme maintenant réellement le transport après des échecs répétés.
    private final java.util.concurrent.atomic.AtomicInteger consecutiveFailures =
        new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_FAILURES = 4;

    public UsbTransportIo(String key, UsbSerialPort port, String description, long generationId) {
        this.key = key;
        this.port = port;
        this.description = (description != null ? description : "USB");
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
        try {
            port.write(data, Math.max(0, timeoutMs));
            consecutiveFailures.set(0);
            return data.length;
        } catch (Exception e) {
            // ✅ Toute exception d'écriture = signal fort de déconnexion réelle
            // (contrairement au BT, une exception USB indique presque toujours
            // un vrai débranchement, pas un simple délai) — fermeture immédiate.
            android.util.Log.w("UsbTransportIo", "write exception sur " + key + " — fermeture immédiate: " + e.getMessage());
            try { close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || port == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;
        int to = (timeoutMs < 0) ? 60_000 : timeoutMs;
        try {
            int n = port.read(buffer, to);
            // ✅ Un retour à 0 (pas de données dans le délai) est NORMAL et
            // fréquent en USB pendant une attente légitime — ce n'est PAS un
            // signal fiable de déconnexion, contrairement au BT. On ne
            // ferme PAS sur ce cas seul (évite un faux positif comme celui
            // qu'on a dû corriger côté RegisterTabFragment). Seule une vraie
            // EXCEPTION (ci-dessous) est un signal fiable de déconnexion USB.
            return n;
        } catch (Exception e) {
            android.util.Log.w("UsbTransportIo", "read exception sur " + key + " — fermeture immédiate: " + e.getMessage());
            try { close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { port.close(); } catch (Exception ignored) {}
    }
}

