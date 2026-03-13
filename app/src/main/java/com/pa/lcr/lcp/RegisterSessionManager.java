
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
 * - Unicité: 1 node -> 1 DeliveryController.
 * - Multi-listener (mux).
 * - Scheduler central par node pour réduire rc=0x26 (poll collisions).
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
    private final Map<Integer, NodeSession> sessions = new LinkedHashMap<>();

    private RegisterSessionManager(Context appCtx) {
        this.appCtx = appCtx;
        this.store = new DeliveryLogStore(appCtx);
        this.store.purgeOlderThanDaysAsync(7);
    }

    public DeliveryLogStore getStore() { return store; }

    public synchronized DeliveryController getController(int nodeDec) {
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        return (s != null) ? s.dc : null;
    }

    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        NodeSession existing = sessions.get(node);
        if (existing != null) return existing.dc;
        if (port == null) return null;

        LcpLink link = new LcpLink(port, node, from, true);
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store);

        NodeScheduler scheduler = new NodeScheduler(node);

        MuxListener mux = new MuxListener();
        mux.addListener(new LogBusSink(node, scheduler));
        mux.addListener(scheduler);

        dc.setListener(mux);

        try { dc.initialize(); } catch (Exception ignored) {}

        NodeSession s = new NodeSession(dc, mux, scheduler);
        sessions.put(node, s);

        scheduler.bindController(dc);
        return dc;
    }

    public synchronized void attachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;

        s.mux.addListener(uiListener);
        s.scheduler.setUiSubscribed(true);
    }

    public synchronized void detachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;

        s.mux.removeListener(uiListener);
        s.scheduler.setUiSubscribed(false);
    }

    public synchronized void clearAll(boolean closeTransport) {
        for (NodeSession s : sessions.values()) {
            try { s.scheduler.shutdown(); } catch (Exception ignored) {}
            try { s.dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

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
     * Scheduler central par node.
     * ✅ FIX: après fin (state CONNECTED), ne plus poller STATUS automatiquement.
     * - RUNNING_* : live + status
     * - CONNECTED : rien (one-shot via UI uniquement)
     * - DISCONNECTED : rien
     */
    private static final class NodeScheduler implements DeliveryControllerPort.Listener {

        private final int node;
        private final ScheduledExecutorService exec;
        private DeliveryController dc;

        private volatile boolean uiSubscribed = false;

        private volatile long lastLiveMs = 0L;
        private volatile long lastStatusMs = 0L;

        private volatile long liveBackoffMs = 0L;
        private volatile long statusBackoffMs = 0L;

        private static final long LIVE_RUNNING_MS = 500;
        private static final long STATUS_RUNNING_MS = 1500;

        NodeScheduler(int node) {
            this.node = node;
            final int nodeId = node;
            this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NodeScheduler-" + nodeId);
                t.setDaemon(true);
                return t;
            });
        }

        void bindController(DeliveryController dc) {
            this.dc = dc;
            exec.scheduleWithFixedDelay(this::tick, 200, 200, TimeUnit.MILLISECONDS);
        }

        void setUiSubscribed(boolean v) { this.uiSubscribed = v; }

        void noteBusyRc26() {
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

            // ✅ STOP total en DISCONNECTED
            if (st == DeliveryState.DISCONNECTED) return;

            long now = System.currentTimeMillis();
            boolean running = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);

            // ✅ LIVE uniquement en RUNNING
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

            // ✅ STATUS uniquement en RUNNING (plus de polling automatique en CONNECTED)
            if (running) {
                long interval = STATUS_RUNNING_MS + statusBackoffMs;
                if (now - lastStatusMs >= interval) {
                    lastStatusMs = now;
                    try {
                        c.requestStatus();
                        if (statusBackoffMs > 0) statusBackoffMs = Math.max(0, statusBackoffMs - 200);
                    } catch (Exception ignored) {}
                }
            }
        }

        @Override public void onStateChanged(DeliveryState state) {
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

            if (s.startsWith("TX:") || s.startsWith("[TX]")) { LogBus.ioTx(node, s); return; }
            if (s.startsWith("RX:") || s.startsWith("[RX]")) { LogBus.ioRx(node, s); return; }
            if (s.startsWith("[API") || s.startsWith("[API]")) { LogBus.api(node, s); return; }

            LogBus.ui(node, s);
        }

        @Override public void onError(String context, Throwable error) {
            String msg = (error != null && error.getMessage() != null) ? error.getMessage() : "";
            LogBus.api(node, "[ERR][" + context + "] " + msg);

            if (msg.contains("rc=0x26") || msg.contains("rc=0X26")) {
                if (scheduler != null) scheduler.noteBusyRc26();
            }
        }

        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }
    }
}
