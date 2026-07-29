package com.pa.lcr.lcp.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TransportIo pour un registre LCR-II/LC3 exposé en raw TCP passthrough
 * via un serveur de port série (ex: Moxa N-Port). Le protocole LCP au-dessus
 * (LcpLink/Lc3Link) ne fait aucune différence entre USB, BT SPP et TCP —
 * seuls read()/write()/isOpen()/close() changent de support physique.
 *
 * Clé stable: "TCP:ip:port" (ex: "TCP:192.168.1.50:4001").
 *
 * ✅ Compatibilité Android 9-15 (API 28-35) : java.net.Socket/InputStream/
 * OutputStream sont des API Java standard stables sur toute la plage —
 * aucune branche Build.VERSION.SDK_INT nécessaire dans ce fichier.
 *
 * ✅ Même précaution que BtSppTransportIo : un socket TCP peut geler
 * indéfiniment sur write() (ex: N-Port qui a perdu son port série sans
 * fermer le socket réseau) — write() passe donc par un thread dédié
 * borné par Future.get(timeout), jamais un write() direct bloquant.
 */
public final class TcpTransportIo implements TransportIo {

    private final String key;          // "TCP:ip:port"
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String description;
    private final long generationId;

    private volatile boolean closed = false;

    private final ExecutorService writeExec = Executors.newSingleThreadExecutor();

    private final AtomicInteger ioErrors   = new AtomicInteger(0);
    private final AtomicInteger ioTimeouts = new AtomicInteger(0);
    private final AtomicInteger ioSamples  = new AtomicInteger(0);

    // ✅ FIX (même correctif que BtSppTransportIo) : socket.isConnected() +
    // !isClosed() pour un java.net.Socket est AUSSI connu pour mentir sur une
    // vraie perte de connexion distante (ex: Wi-Fi hors de portée sans
    // FIN/RST TCP propre reçu) — le socket local reste "connecté" tant que
    // close() n'est pas appelé explicitement. Un read() qui timeout de façon
    // répétée (le cas exact de "Timeout waiting LCP response") ne fermait
    // jamais le transport, rendant isOpen() menteur indéfiniment.
    private final AtomicInteger consecutiveReadTimeouts = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_READ_TIMEOUTS = 4;

    public TcpTransportIo(String key,
                           Socket socket,
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

    /** Construit la clé stable "TCP:ip:port" (utilisée par MediaTransportManager). */
    public static String tcpKey(String ip, int port) {
        return "TCP:" + (ip != null ? ip.trim() : "") + ":" + port;
    }

    @Override public String getKey() { return key; }
    @Override public String describe() { return description; }

    @Override
    public boolean isOpen() {
        try {
            return !closed && socket != null && socket.isConnected() && !socket.isClosed();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public long getGenerationId() { return generationId; }

    public int getIoErrors()   { return ioErrors.get(); }
    public int getIoTimeouts() { return ioTimeouts.get(); }
    public int getIoSamples()  { return ioSamples.get(); }

    /** Extrait "ip:port" depuis la clé "TCP:ip:port" (pour l'UI/logs). */
    public String getIpPort() {
        if (key == null) return "";
        String k = key.trim();
        if (k.toUpperCase().startsWith("TCP:")) return k.substring(4).trim();
        return k;
    }

    @Override
    public int write(final byte[] data, int timeoutMs) throws Exception {
        if (closed || out == null) return -1;
        if (data == null || data.length == 0) return 0;

        final long boundMs = (timeoutMs > 0) ? timeoutMs : 5000L;

        Future<Integer> fut = writeExec.submit(() -> {
            out.write(data);
            out.flush();
            return data.length;
        });

        try {
            int n = fut.get(boundMs, TimeUnit.MILLISECONDS);
            ioSamples.incrementAndGet();
            consecutiveReadTimeouts.set(0);
            return n;
        } catch (java.util.concurrent.TimeoutException te) {
            ioTimeouts.incrementAndGet();
            ioErrors.incrementAndGet();
            fut.cancel(true);
            try { close(); } catch (Exception ignored) {}
            throw new java.io.IOException("TCP write timeout after " + boundMs + "ms — socket probablement mort (" + key + ")");
        } catch (java.util.concurrent.ExecutionException ee) {
            ioErrors.incrementAndGet();
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause);
        }
    }

    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || in == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        if (timeoutMs < 0) {
            try {
                int n = in.read(buffer);
                ioSamples.incrementAndGet();
                return n;
            } catch (Exception e) {
                ioErrors.incrementAndGet();
                throw e;
            }
        }

        final long deadline = System.currentTimeMillis() + timeoutMs;

        while (true) {
            int avail = 0;
            try { avail = in.available(); } catch (Exception ignored) {}

            if (avail > 0) {
                try {
                    int toRead = Math.min(avail, buffer.length);
                    int n = in.read(buffer, 0, toRead);
                    ioSamples.incrementAndGet();
                    consecutiveReadTimeouts.set(0);
                    return n;
                } catch (Exception e) {
                    ioErrors.incrementAndGet();
                    throw e;
                }
            }

            if (timeoutMs == 0) return 0;

            if (System.currentTimeMillis() >= deadline) {
                if (timeoutMs > 50) {
                    ioTimeouts.incrementAndGet();
                    int consec = consecutiveReadTimeouts.incrementAndGet();
                    android.util.Log.w("TcpTransportIo", "read timeout consécutif #" + consec + "/" + MAX_CONSECUTIVE_READ_TIMEOUTS + " sur " + key);
                    if (consec >= MAX_CONSECUTIVE_READ_TIMEOUTS) {
                        android.util.Log.w("TcpTransportIo", "read: seuil atteint sur " + key + " — fermeture réelle du transport");
                        try { close(); } catch (Exception ignored) {}
                    }
                }
                return 0;
            }

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
        try { writeExec.shutdownNow(); } catch (Exception ignored) {}
    }
}