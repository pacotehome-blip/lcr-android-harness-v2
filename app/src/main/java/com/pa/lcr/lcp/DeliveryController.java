
package com.pa.lcr.lcp;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

public final class DeliveryController {

    // --- État interne robuste ---
    private enum State { IDLE, STARTING, RUNNING, STOPPING, STOPPED, ERROR }
    private volatile State state = State.IDLE;

    // --- Protection de réentrance ---
    private final AtomicBoolean endCalled = new AtomicBoolean(false);
    private final ReentrantLock lock = new ReentrantLock();

    // --- Annulation live-loop ---
    private final AtomicInteger loopGen = new AtomicInteger(0);
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(
                    r -> new Thread(r, "DeliveryLoop"));

    // --- Flags internes ---
    private volatile boolean deliveryStarted = false;

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    public boolean startDelivery() {
        synchronized (this) {
            if (state == State.RUNNING || state == State.STARTING) {
                return false; // déjà en cours
            }
            if (state == State.STOPPING) {
                return false; // on attend la fin du stop précédent
            }
            state = State.STARTING;
            endCalled.set(false);
        }

        try {
            lock.lock();

            // TODO: envoyer commande LCP Start Open / Preset ici
            deliveryStarted = true;

            // démarrage de la loop
            startLiveLoopInternal();

            state = State.RUNNING;
            return true;

        } catch (Throwable t) {
            state = State.ERROR;
            return false;

        } finally {
            if (lock.isHeldByCurrentThread())
                lock.unlock();
        }
    }


    /** Appelé par l’UI ou par END */
    public void endGracefully() {
        // Empêche réentrance → FINI une seule fois
        if (!endCalled.compareAndSet(false, true)) {
            return; // déjà appelé → aucune action
        }

        // Invalider la boucle immédiatement
        stopLiveLoopInternal();

        // Verrou interne : ne jamais unlock si pas détenu
        if (lock.isHeldByCurrentThread()) {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException ignored) {
                // sécurité maximale
            }
        }

        // TODO: envoyer commande LCP END si applicable

        state = State.STOPPED;
        deliveryStarted = false;
    }


    public boolean isRunning() {
        return state == State.RUNNING;
    }


    // ========================================================================
    // LIVE LOOP
    // ========================================================================

    private void startLiveLoopInternal() {
        int gen = loopGen.incrementAndGet();
        executor.scheduleAtFixedRate(() -> tick(gen), 0, 200, TimeUnit.MILLISECONDS);
    }

    private void stopLiveLoopInternal() {
        // invalider la génération → toutes les ticks en cours deviennent NO-OP
        loopGen.incrementAndGet();
    }

    /** Tick live-loop sécurisé */
    private void tick(int gen) {
        // Annulé ? → on ne fait rien
        if (gen != loopGen.get()) return;
        if (endCalled.get()) return;

        // Si on veut absolument éviter les races avec unlock() :
        boolean locked = false;
        try {
            locked = lock.tryLock(5, TimeUnit.MILLISECONDS);
            if (!locked) return;

            // TODO: polling régulier → lire 0x28, GROSS/NET, DS/DC, etc.

            if (!deliveryStarted) return;

        } catch (Exception ignored) {

        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                try { lock.unlock(); } catch (IllegalMonitorStateException ignored) {}
            }
        }
    }


    // ========================================================================
    // SHUTDOWN SDK
    // ========================================================================

    public void shutdown() {
        stopLiveLoopInternal();
        executor.shutdownNow();
    }
}
