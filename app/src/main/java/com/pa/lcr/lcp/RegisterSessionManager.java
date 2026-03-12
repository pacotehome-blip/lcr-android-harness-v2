
package com.pa.lcr.lcp;

import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Session manager multi-registre (clé = lcrnode_dec).
 *
 * Objectifs:
 *  - Unicité: 1 node -> 1 DeliveryController. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/RegisterSessionManager.java)
 *  - Multi-listener: UI tabs + sinks sans écraser.
 *  - Scheduler central par node: un seul polling Live/Status => réduit collisions (rc=0x26).
 */
public final class RegisterSessionManager {

    private static volatile RegisterSessionManager INSTANCE;

    public static RegisterSessionManager get(Context ctx) {
        if (INSTANCE == null) {
            synchronized (RegisterSessionManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RegisterSessionManager(ctx.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    private final Context appCtx;
    private final DeliveryLogStore store;

    // node -> session (controller + mux + scheduler)
    private final Map<Integer, NodeSession> sessions = new LinkedHashMap<>();

    private RegisterSessionManager(Context appCtx) {
        this.appCtx = appCtx;
        this.store = new DeliveryLogStore(appCtx);
        this.store.purgeOlderThanDaysAsync(7);
    }

    public DeliveryLogStore getStore() {
        return store;
    }

    /**
     * Retourne le controller si la session existe déjà.
     */
    public synchronized DeliveryController getController(int nodeDec) {
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        return (s != null) ? s.dc : null;
    }

    /**
     * Retourne une session existante ou en crée une headless sur UsbSerialPort.
     * - fromDec défaut: 255
     * - node range: 1..250 (mais on conserve le masquage &0xFF comme ton code base) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/RegisterSessionManager.java)
     */
    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        NodeSession existing = sessions.get(node);
        if (existing != null) return existing.dc;

        if (port == null) return null;

        // Transport + controller
        LcpLink link = new LcpLink(port, node, from, true);
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store);

        // Scheduler central par node
        NodeScheduler scheduler = new NodeScheduler(node);

        // MuxListener unique: un seul setListener() sur le controller, puis dispatch vers N listeners.
        MuxListener mux = new MuxListener();
        mux.addListener(new LogBusSink(node, scheduler)); // sink permanent logs + backoff rc=0x26
        mux.addListener(scheduler);                       // scheduler observe l’état (reset backoff etc.)

        dc.setListener(mux);

        // init best-effort
        try { dc.initialize(); } catch (Exception ignored) {}

        NodeSession s = new NodeSession(dc, mux, scheduler);
        sessions.put(node, s);

        // Démarrer la boucle scheduler (idle au départ, throttling interne)
        scheduler.bindController(dc);

        return dc;
    }

    // ---------------------------------------------------------------------
    // Multi-listener UI attach/detach
    // ---------------------------------------------------------------------

    /**
     * UI: attache un listener UI au node (sans écraser).
     * Active aussi le mode "UI subscribed" du scheduler.
     */
    public synchronized void attachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;

        s.mux.addListener(uiListener);
        s.scheduler.setUiSubscribed(true);
    }

    /**
     * UI: détache un listener UI du node.
     * On bascule uiSubscribed=false (simple); le scheduler reste actif si RUNNING.
     */
    public synchronized void detachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;

        s.mux.removeListener(uiListener);
        s.scheduler.setUiSubscribed(false);
    }

    /**
     * Nettoyage best-effort (USB detach).
     */
    public synchronized void clearAll(boolean closeTransport) {
        for (NodeSession s : sessions.values()) {
            try { s.scheduler.shutdown(); } catch (Exception ignored) {}
            try { s.dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    // ---------------------------------------------------------------------
    // Structures internes
    // ---------------------------------------------------------------------

    private static final class NodeSession {
        final DeliveryController dc;
        final MuxListener mux;
        final NodeScheduler scheduler;

        NodeSession(DeliveryController dc, MuxListener mux, NodeScheduler scheduler) {
            this.dc = dc;
            this.mux = mux;
            this.scheduler = scheduler;
        }
    }

    /**
     * MuxListener: dispatch vers N listeners (tabs UI + sinks + scheduler).
     */
    private static final class MuxListener implements DeliveryControllerPort.Listener {

        private final CopyOnWriteArrayList<DeliveryControllerPort.Listener> listeners =
                new CopyOnWriteArrayList<>();

        void addListener(DeliveryControllerPort.Listener l) {
            if (l == null) return;
            listeners.addIfAbsent(l);
        }

        void removeListener(DeliveryControllerPort.Listener l) {
            if (l == null) return;
            listeners.remove(l);
        }

        @Override public void onStateChanged(DeliveryState state) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onStateChanged(state); } catch (Exception ignored) {}
            }
        }

        @Override public void onProductsUpdated(java.util.List<ProductUiItem> products, int activeIndex0) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onProductsUpdated(products, activeIndex0); } catch (Exception ignored) {}
            }
        }

        @Override public void onLog(String message) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLog(message); } catch (Exception ignored) {}
            }
        }

        @Override public void onError(String context, Throwable error) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onError(context, error); } catch (Exception ignored) {}
            }
        }

        @Override public void onLiveQty(double net, double gross) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLiveQty(net, gross); } catch (Exception ignored) {}
            }
        }

        @Override public void onLiveStatus(String liveText) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onLiveStatus(liveText); } catch (Exception ignored) {}
            }
        }

        @Override public void onTicketInfo(String ticketNo, String deliveryUid) {
            for (DeliveryControllerPort.Listener l : listeners) {
                try { l.onTicketInfo(ticketNo, deliveryUid); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Scheduler central par node:
     *  - Un seul thread per-node (ScheduledExecutorService).
     *  - Deux cadences: Live (RUNNING) + Status (RUNNING ou UI subscribed/CONNECTED).
     *  - Backoff progressif sur rc=0x26 (busy/collision).
     */
    private static final class NodeScheduler implements DeliveryControllerPort.Listener {

        private final int node;

        // ✅ FIX: exec est créé dans le constructeur APRÈS l'init de node.
        private final ScheduledExecutorService exec;

        private DeliveryController dc;

        private volatile boolean uiSubscribed = false;

        private volatile long lastLiveMs = 0L;
        private volatile long lastStatusMs = 0L;

        // backoff (ms)
        private volatile long liveBackoffMs = 0L;
        private volatile long statusBackoffMs = 0L;

        // cadence base (réduites vs 200ms/1000ms)
        private static final long LIVE_RUNNING_MS = 500;
        private static final long STATUS_RUNNING_MS = 1500;
        private static final long STATUS_IDLE_MS = 2500;

        NodeScheduler(int node) {
            this.node = node;

            final int nodeId = this.node; // capture safe
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NodeScheduler-" + nodeId);
                t.setDaemon(true);
                return t;
            });
        }

        void bindController(DeliveryController dc) {
            this.dc = dc;
            // tick rapide, throttling interne via lastX + interval + backoff
            exec.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        }

        void setUiSubscribed(boolean v) {
            this.uiSubscribed = v;
        }

        void noteBusyRc26() {
            // backoff progressif, cap à 2000ms
            liveBackoffMs = Math.min(2000, Math.max(liveBackoffMs * 2, 400));
            statusBackoffMs = Math.min(2000, Math.max(statusBackoffMs * 2, 400));
        }

        void resetBackoff() {
            liveBackoffMs = 0L;
            statusBackoffMs = 0L;
        }

        private void tick() {
            DeliveryController c = dc;
            if (c == null) return;

            DeliveryState st = c.getState();
            long now = System.currentTimeMillis();

            boolean running = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
            boolean shouldPollIdle = uiSubscribed || (st == DeliveryState.CONNECTED);

            // 1) LIVE sample (RUNNING seulement)
            if (running) {
                long interval = LIVE_RUNNING_MS + liveBackoffMs;
                if (now - lastLiveMs >= interval) {
                    lastLiveMs = now;
                    try {
                        c.requestLiveSample();
                        if (liveBackoffMs > 0) liveBackoffMs = Math.max(0, liveBackoffMs - 200);
                    } catch (Exception ignored) {}
                }
            }

            // 2) STATUS (RUNNING ou UI subscribed/CONNECTED)
            if (running || shouldPollIdle) {
                long base = running ? STATUS_RUNNING_MS : STATUS_IDLE_MS;
                long interval = base + statusBackoffMs;
                if (now - lastStatusMs >= interval) {
                    lastStatusMs = now;
                    try {
                        c.requestStatus();
                        if (statusBackoffMs > 0) statusBackoffMs = Math.max(0, statusBackoffMs - 200);
                    } catch (Exception ignored) {}
                }
            }
        }

        @Override
        public void onStateChanged(DeliveryState state) {
            // reset backoff quand on repasse CONNECTED (souvent fin de livraison)
            if (state == DeliveryState.CONNECTED) resetBackoff();
        }

        @Override public void onProductsUpdated(java.util.List<ProductUiItem> products, int activeIndex0) { }
        @Override public void onLog(String message) { }
        @Override public void onError(String context, Throwable error) { }
        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }

        void shutdown() {
            try { exec.shutdownNow(); } catch (Exception ignored) {}
        }
    }

    /**
     * Sink permanent vers LogBus + backoff sur rc=0x26 détecté dans onError().
     * - LogBus formate [SRC][N=...] à l’affichage.
     */
    private static final class LogBusSink implements DeliveryControllerPort.Listener {

        private final int node;
        private final NodeScheduler scheduler;

        LogBusSink(int node, NodeScheduler scheduler) {
            this.node = node;
            this.scheduler = scheduler;
        }

        @Override public void onStateChanged(DeliveryState state) {
            LogBus.ui(node, "STATE=" + (state != null ? state.name() : "null"));
        }

        @Override public void onProductsUpdated(java.util.List<ProductUiItem> products, int activeIndex0) { }

        @Override public void onLog(String message) {
            if (message == null) return;
            String s = message.trim();

            boolean isIo  = s.startsWith("[IO ") || s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳");
            boolean isApi = s.startsWith("[API ") || s.startsWith("[API]");
            boolean isErr = s.startsWith("[ERR") || s.startsWith("ERR[");

            if (isApi) LogBus.api(node, s);
            else if (isIo) LogBus.io(node, s);
            else if (isErr) LogBus.err(node, s);
            else LogBus.ui(node, s);
        }

        @Override public void onError(String context, Throwable error) {
            String msg = (error != null && error.getMessage() != null) ? error.getMessage() : "";
            LogBus.err(node, "ERR[" + context + "] " + msg);

            // backoff sur rc=0x26 (busy / collision)
            if (msg.contains("rc=0x26") || msg.contains("rc=0X26")) {
                if (scheduler != null) scheduler.noteBusyRc26();
            }
        }

        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }
    }
}
