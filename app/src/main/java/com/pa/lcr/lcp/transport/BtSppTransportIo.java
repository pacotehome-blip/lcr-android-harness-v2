package com.pa.lcr.lcp.transport;

import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class BtSppTransportIo implements TransportIo {

    private final String key; // "BT:MAC"
    private final BluetoothSocket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String description;
    private final long generationId;

    private volatile boolean closed = false;

    // ✅ FIX : OutputStream.write() sur un BluetoothSocket n'a AUCUN timeout natif —
    // un socket zombie peut bloquer write() indéfiniment, peu importe le timeoutMs
    // demandé. Ce thread dédié permet de borner réellement l'appel via Future.get().
    private final java.util.concurrent.ExecutorService writeExec =
        java.util.concurrent.Executors.newSingleThreadExecutor();

    // =========================================================
    // ✅ Compteurs IO pour qualité signal indirecte
    // =========================================================
    private final AtomicInteger ioErrors    = new AtomicInteger(0);
    private final AtomicInteger ioTimeouts  = new AtomicInteger(0);
    private final AtomicInteger ioSamples   = new AtomicInteger(0);
    private final AtomicLong    ioLatencySum = new AtomicLong(0L);
    // Timestamp de début de session (pour calcul durée)
    private final long sessionStartMs = System.currentTimeMillis();

    // ❌ RETIRÉ (2026-07-28) : consecutiveReadTimeouts + fermeture du
    // transport après 4 read() vides consécutifs.
    //
    // Raison 1 — le code ne s'exécutait jamais : LcpLink.readFrameUntil()
    // appelle rxReadSome(50) → read(tmp, 50), et la garde était
    // "if (timeoutMs > 50)". 50 n'est pas > 50.
    //
    // Raison 2 — même corrigée, la garde serait fausse : un read() vide est
    // le fonctionnement NOMINAL du frame reader, qui boucle par tranches de
    // 50ms en attendant une trame. Le registre a le droit d'être silencieux
    // (RC_REQUEST_QUEUED, calcul en cours, W&M). 4 lectures vides = 200ms de
    // silence — ce qui arrive en permanence pendant une livraison normale.
    //
    // Raison 3 — le compteur était de toute façon remis à zéro par write(),
    // et chaque requête LCP commence par un write (qui réussit toujours sur
    // un socket zombie).
    //
    // La détection de déconnexion appartient à la couche PROTOCOLE, seule à
    // connaître la notion de "requête envoyée sans réponse" :
    // DeliveryController.liveSoftSkip() / handleIoFailure(). Le transport ne
    // ferme que sur exception réelle (write timeout), comme le fait déjà
    // correctement UsbTransportIo.
    //
    // ioTimeouts reste incrémenté — statistique de qualité de signal, pas
    // un déclencheur de fermeture.

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

    // =========================================================
    // ✅ Accesseurs compteurs IO (lecture thread-safe)
    // =========================================================
    public int getIoErrors()   { return ioErrors.get(); }
    public int getIoTimeouts() { return ioTimeouts.get(); }
    public int getIoSamples()  { return ioSamples.get(); }

    public int getIoLatencyAvgMs() {
        int s = ioSamples.get();
        if (s <= 0) return 0;
        return (int)(ioLatencySum.get() / s);
    }

    public long getSessionStartMs() { return sessionStartMs; }

    /** Extrait la MAC depuis la clé "BT:AA:BB:CC:DD:EE:FF" */
    public String getMac() {
        if (key == null) return "";
        String k = key.trim();
        if (k.toUpperCase().startsWith("BT:")) return k.substring(3).trim();
        return k;
    }

    /**
     * Snapshot atomique des compteurs IO.
     * Utile pour persister en DB sans race condition.
     */
    public IoSnapshot snapshotCounters() {
        return new IoSnapshot(
                ioErrors.get(),
                ioTimeouts.get(),
                ioSamples.get(),
                getIoLatencyAvgMs(),
                sessionStartMs
        );
    }

    /** Remet les compteurs à zéro (ex. après persistance périodique) */
    public void resetCounters() {
        ioErrors.set(0);
        ioTimeouts.set(0);
        ioSamples.set(0);
        ioLatencySum.set(0L);
    }

    // =========================================================
    // Snapshot immuable
    // =========================================================
    public static final class IoSnapshot {
        public final int errors;
        public final int timeouts;
        public final int samples;
        public final int latencyAvgMs;
        public final long sessionStartMs;

        public IoSnapshot(int errors, int timeouts, int samples,
                          int latencyAvgMs, long sessionStartMs) {
            this.errors = errors;
            this.timeouts = timeouts;
            this.samples = samples;
            this.latencyAvgMs = latencyAvgMs;
            this.sessionStartMs = sessionStartMs;
        }
    }

    // =========================================================
    // write — avec mesure latence + compteurs
    // =========================================================
    @Override
    public int write(final byte[] data, int timeoutMs) throws Exception {
        if (closed || out == null) return -1;
        if (data == null || data.length == 0) return 0;

        // ✅ Un write vraiment illimité n'est jamais sûr sur un socket physique —
        // même timeoutMs<=0 obtient une borne de sécurité raisonnable.
        final long boundMs = (timeoutMs > 0) ? timeoutMs : 5000L;

        long t0 = System.currentTimeMillis();
        java.util.concurrent.Future<Integer> fut = writeExec.submit(() -> {
            out.write(data);
            out.flush();
            return data.length;
        });

        try {
            int n = fut.get(boundMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            long lat = System.currentTimeMillis() - t0;
            ioSamples.incrementAndGet();
            ioLatencySum.addAndGet(lat);
            return n;
        } catch (java.util.concurrent.TimeoutException te) {
            // ✅ Le write n'a pas abouti dans le délai — le socket est probablement
            // mort. On annule la tâche (le thread reste bloqué dans write() côté OS,
            // mais on ne laisse plus l'appelant geler indéfiniment) et on ferme le
            // transport pour forcer une reconnexion propre au prochain essai.
            ioTimeouts.incrementAndGet();
            ioErrors.incrementAndGet();
            fut.cancel(true);
            try { close(); } catch (Exception ignored) {}
            throw new java.io.IOException("Write timeout after " + boundMs + "ms — socket probablement mort");
        } catch (java.util.concurrent.ExecutionException ee) {
            ioErrors.incrementAndGet();
            Throwable cause = ee.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause);
        }
    }

    // =========================================================
    // read — avec détection timeout + compteurs
    // =========================================================
    @Override
    public int read(byte[] buffer, int timeoutMs) throws Exception {
        if (closed || in == null) return -1;
        if (buffer == null || buffer.length == 0) return 0;

        // bloquant
        if (timeoutMs < 0) {
            try {
                long t0 = System.currentTimeMillis();
                int n = in.read(buffer);
                long lat = System.currentTimeMillis() - t0;
                ioSamples.incrementAndGet();
                ioLatencySum.addAndGet(lat);
                return n;
            } catch (Exception e) {
                ioErrors.incrementAndGet();
                throw e;
            }
        }

        final long deadline = System.currentTimeMillis() + timeoutMs;
        long t0 = System.currentTimeMillis();

        while (true) {
            int avail = 0;
            try { avail = in.available(); } catch (Exception ignored) {}

            if (avail > 0) {
                try {
                    int toRead = Math.min(avail, buffer.length);
                    int n = in.read(buffer, 0, toRead);
                    long lat = System.currentTimeMillis() - t0;
                    ioSamples.incrementAndGet();
                    ioLatencySum.addAndGet(lat);
                    return n;
                } catch (Exception e) {
                    ioErrors.incrementAndGet();
                    throw e;
                }
            }

            if (timeoutMs == 0) return 0;

            if (System.currentTimeMillis() >= deadline) {
                // timeout non-bloquant: on ne compte pas comme erreur
                // sauf si on avait demandé des données (timeoutMs > 0)
                if (timeoutMs > 50) {
                    // Statistique seulement — AUCUNE fermeture.
                    // Un read vide est normal. Voir note en haut du fichier.
                    ioTimeouts.incrementAndGet();
                }
                return 0;
            }

            try { Thread.sleep(5); } catch (InterruptedException ie) { return 0; }
        }
    }

    // =========================================================
    // close
    // =========================================================
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
