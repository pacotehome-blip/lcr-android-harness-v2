
package com.pa.lcr.lcp;

import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Session manager multi-registre.
 *
 * ✅ Option B:
 * - sessions indexées par transportKey + ":" + lcrnode_dec
 * - getOrCreate(...) prend TransportIo
 *
 * ✅ Compat UI (legacy):
 * - getOrCreate(node, from, UsbSerialPort)
 * - attachUiListener(node, listener)
 * - detachUiListener(node, listener)
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
 // ✅ v7: identité registre (node + serial) et pin du média
 private final Map<Integer, String> expectedSerialByNode = new LinkedHashMap<>();
 private final Map<String, String> pinnedTransportByRegKey = new LinkedHashMap<>();


    // ✅ Option B: key = transportKey + ":" + node
    private final Map<String, NodeSession> sessions = new LinkedHashMap<>();

    private RegisterSessionManager(Context appCtx) {
        this.appCtx = appCtx;
        this.store = new DeliveryLogStore(appCtx);
        this.store.purgeOlderThanDaysAsync(7);
    }

    public DeliveryLogStore getStore() { return store; }

    // =========================================================
    // ✅ v7: Résolution média par registre (node + serial)
    // - Si serial attendu connu: on choisit le transport READY dont #80 match
    // - Sinon: on réutilise une session existante pour ce node (si unique)
    // =========================================================
    public synchronized DeliveryController resolveOrCreateForNode(int nodeDec, int fromDec) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        // 0) si un transport est déjà pinné pour node#serial, on le réutilise
        String expectedSerial = expectedSerialByNode.get(node);
        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            String rk = regKey(node, expectedSerial);
            String pinned = pinnedTransportByRegKey.get(rk);
            if (pinned != null) {
                TransportIo io = MediaTransportManager.get(appCtx).getByKey(pinned);
                if (io != null && io.isOpen()) {
                    return getOrCreate(pinned, node, from, io);
                }
            }
        }

        // 1) si on a déjà une session existante unique pour ce node, la réutiliser
        NodeSession one = null;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;
            if (one == null) one = s;
            else {
                one = null; // plusieurs sessions pour ce node
                break;
            }
        }
        if (one != null) {
            TransportIo io = MediaTransportManager.get(appCtx).getByKey(one.transportKey);
            if (io != null && io.isOpen()) return getOrCreate(one.transportKey, node, from, io);
        }

        // 2) si serial attendu connu: probe tous les transports READY et choisir celui dont #80 match
        if (expectedSerial != null && !expectedSerial.trim().isEmpty()) {
            String want = expectedSerial.trim();
            MediaTransportManager mgr = MediaTransportManager.get(appCtx);
            List<com.pa.lcr.lcp.transport.TransportSnapshot> snaps = mgr.listSnapshots();
            if (snaps != null) {
                for (com.pa.lcr.lcp.transport.TransportSnapshot s : snaps) {
                    if (s == null || s.key == null) continue;
                    if (s.status != com.pa.lcr.lcp.transport.TransportStatus.READY) continue;
                    TransportIo io = mgr.getByKey(s.key);
                    if (io == null || !io.isOpen()) continue;
                    String serial = probeSerial(io, node, from);
                    if (serial != null && serial.equalsIgnoreCase(want)) {
                        pinnedTransportByRegKey.put(regKey(node, want), s.key);
                        return getOrCreate(s.key, node, from, io);
                    }
                }
            }
        }

        // 3) fallback: pickReady (USB puis autre)
        try {
            MediaTransportManager mgr = MediaTransportManager.get(appCtx);
            ArrayList<String> pref = new ArrayList<>();
            pref.add(MediaTransportManager.KEY_USB);
            TransportIo io = mgr.pickReady(pref);
            if (io == null) io = mgr.pickReady(null);
            if (io != null && io.isOpen()) return getOrCreate(io.getKey(), node, from, io);
        } catch (Exception ignored) {}

        return null;
    }

    // Lecture best-effort du serial (#80) sur un transport donné
    private String probeSerial(TransportIo io, int nodeDec, int fromDec) {
        try {
            LcpLink tmp = new LcpLink(io, nodeDec, fromDec, true);
            byte[] b = tmp.opGetField(80, 500);
            if (b == null || b.length == 0) return null;
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            int nul = s.indexOf(' ');
            if (nul >= 0) s = s.substring(0, nul);
            s = s.trim();
            return s.isEmpty() ? null : s;
        } catch (Exception ignored) {
            return null;
        }
    }


    
    // ✅ v7: clé registre = node#serial (serial = #80)
    private static String regKey(int nodeDec, String serialId) {
        int node = nodeDec & 0xFF;
        String s = (serialId == null) ? "" : serialId.trim();
        return node + "#" + s;
    }

    /** Permet au scan/validate d'enregistrer le serial attendu pour un node. */
    public synchronized void bindExpectedSerial(int nodeDec, String serialId) {
        int node = nodeDec & 0xFF;
        if (serialId == null || serialId.trim().isEmpty()) return;
        expectedSerialByNode.put(node, serialId.trim());
    }

    public synchronized String getExpectedSerial(int nodeDec) {
        int node = nodeDec & 0xFF;
        return expectedSerialByNode.get(node);
    }

private static String key(String transportKey, int nodeDec) {
        int node = nodeDec & 0xFF;
        String k = (transportKey == null || transportKey.trim().isEmpty()) ? "?" : transportKey.trim();
        return k + ":" + node;
    }

    // =========================================================
    // ✅ Option B: API principale (TransportIo)
    // =========================================================
    public synchronized DeliveryController getController(String transportKey, int nodeDec) {
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        return (s != null) ? s.dc : null;
    }

    

    /** v7: retrouve le transportKey associé à un controller (si présent). */
    public synchronized String findTransportKeyForController(DeliveryController dc) {
        if (dc == null) return null;
        for (NodeSession s : sessions.values()) {
            if (s == null) continue;
            if (s.dc == dc) return s.transportKey;
        }
        return null;
    }

public synchronized DeliveryController getOrCreate(String transportKey, int nodeDec, int fromDec, TransportIo io) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        if (io == null || !io.isOpen()) return null;

        String tk = (transportKey == null || transportKey.trim().isEmpty()) ? io.getKey() : transportKey.trim();
        String k = key(tk, node);

        NodeSession existing = sessions.get(k);
        if (existing != null) {
            // ✅ Anti-mix: regen si génération transport différente
            if (existing.generationId == io.getGenerationId()) {
                return existing.dc;
            }
            // génération a changé -> on ferme l’ancienne session (logique seulement)
            try { existing.scheduler.shutdown(); } catch (Exception ignored) {}
            try { existing.dc.shutdown(false); } catch (Exception ignored) {}
            sessions.remove(k);
        }

        LcpLink link = new LcpLink(io, node, from, true);
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store);

        NodeScheduler scheduler = new NodeScheduler(node);
        MuxListener mux = new MuxListener();
        mux.addListener(new LogBusSink(node, scheduler));
        mux.addListener(scheduler);

        dc.setListener(mux);
        try { dc.initialize(); } catch (Exception ignored) {}

        NodeSession s = new NodeSession(dc, mux, scheduler, tk, io.getGenerationId());
        sessions.put(k, s);
        scheduler.bindController(dc);

        return dc;
    }

    public synchronized void attachUiListener(String transportKey, int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        if (s == null) return;
        s.mux.addListener(uiListener);
        s.scheduler.setUiSubscribed(true);
    }

    public synchronized void detachUiListener(String transportKey, int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        NodeSession s = sessions.get(key(transportKey, nodeDec));
        if (s == null) return;
        s.mux.removeListener(uiListener);
        s.scheduler.setUiSubscribed(false);
    }

    // =========================================================
    // ✅ LEGACY COMPAT (UI/RegisterTabFragment) — USB par défaut
    // =========================================================

    /**
     * Compat UI: signature legacy utilisée par RegisterTabFragment.
     * On ignore le port pour LCP et on résout TransportIo "USB" via MediaTransportManager.
     */
    @Deprecated
    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        // ✅ Legacy compat: choisir le transport READY (USB si dispo, sinon BT)
        TransportIo io = null;
        try {
            MediaTransportManager mgr = MediaTransportManager.get(appCtx);
            if (mgr != null) {
                io = mgr.getByKey(MediaTransportManager.KEY_USB);
                if (io == null) io = mgr.pickReady(null);
            }
        } catch (Exception ignored) {}
        if (io == null || !io.isOpen()) return null;
        return getOrCreate(io.getKey(), nodeDec, fromDec, io);
    }

    @Deprecated
    public synchronized void attachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        // ✅ Attach à TOUTES les sessions correspondant à ce node (USB ou BT)
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;
            s.mux.addListener(uiListener);
            s.scheduler.setUiSubscribed(true);
        }
    }

    @Deprecated
    public synchronized void detachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        for (Map.Entry<String, NodeSession> e : sessions.entrySet()) {
            if (e == null) continue;
            String k = e.getKey();
            if (k == null) continue;
            if (!k.endsWith(":" + node)) continue;
            NodeSession s = e.getValue();
            if (s == null) continue;
            s.mux.removeListener(uiListener);
            s.scheduler.setUiSubscribed(false);
        }
    }

    // =========================================================
    // Clear
    // =========================================================
    public synchronized void clearAll(boolean closeTransport) {
        for (NodeSession s : sessions.values()) {
            try { s.scheduler.shutdown(); } catch (Exception ignored) {}
            try { s.dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    // =========================================================
    // Internals
    // =========================================================
    private static final class NodeSession {
        final DeliveryController dc;
        final MuxListener mux;
        final NodeScheduler scheduler;
        final String transportKey;
        final long generationId;

        NodeSession(DeliveryController dc, MuxListener mux, NodeScheduler scheduler,
                    String transportKey, long generationId, String serialId) {
            this.dc = dc;
            this.mux = mux;
            this.scheduler = scheduler;
            this.transportKey = transportKey;
            this.generationId = generationId;
 this.serialId = serialId;
        }
    }

    /**
     * Multiplexeur de listeners : UI + LogBus + Scheduler.
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
     * Scheduler central par node.
     *
     * ✅ Règles:
     * - DISCONNECTED: rien
     * - CONNECTED: rien
     * - RUNNING_FLOWING: LIVE rapide + STATUS normal
     * - RUNNING_PAUSED: LIVE OFF (ou très lent) + STATUS ralenti
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

        private static final long LIVE_PAUSED_MS = 0;
        private static final long STATUS_PAUSED_MS = 4000;

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
            if (!uiSubscribed) return;
            if (c.getState() == DeliveryState.DISCONNECTED) return;

            long now = System.currentTimeMillis();

            // Cache-only: dernier delCode publié (TickBus)
            int delCode = 0;
            try {
                ApiResult snap = c.api_tickSnapshot();
                JSONObject d = (snap != null) ? snap.data : null;
                if (d != null) delCode = d.optInt("delCode", 0);
            } catch (Exception ignored) {}

            boolean deliveryActive = (delCode & 0x0008) != 0;
            boolean flowActive = (delCode & 0x0004) != 0;

            long liveInterval = deliveryActive ? (flowActive ? 250L : 450L) : 1200L;
            long statusInterval = deliveryActive ? 1500L : 3500L;

            if (now - lastLiveMs >= (liveInterval + liveBackoffMs)) {
                lastLiveMs = now;
                try {
                    c.requestLiveSample();
                    if (liveBackoffMs > 0) liveBackoffMs = Math.max(0, liveBackoffMs - 200);
                } catch (Exception ignored) {}
            }

            if (now - lastStatusMs >= (statusInterval + statusBackoffMs)) {
                lastStatusMs = now;
                try {
                    c.requestStatus();
                    if (statusBackoffMs > 0) statusBackoffMs = Math.max(0, statusBackoffMs - 200);
                } catch (Exception ignored) {}
            }
        }

        @Override public void onStateChanged(DeliveryState state) { if (state == DeliveryState.CONNECTED) resetBackoff(); }
        @Override public void onProductsUpdated(java.util.List<ProductUiItem> products, int activeIndex0) { }
        @Override public void onLog(String message) { }
        @Override public void onError(String context, Throwable error) { }
        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }

        void shutdown() { try { exec.shutdownNow(); } catch (Exception ignored) {} }
    }

    /**
     * Sink LogBus: route UI/API/IO et injecte backoff rc=0x26.
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
