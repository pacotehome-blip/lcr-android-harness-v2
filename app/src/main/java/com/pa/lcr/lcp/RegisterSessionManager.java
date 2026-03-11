
package com.pa.lcr.lcp;

import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Session manager multi-registre (clé = lcrnode_dec).
 * - Unicité: 1 node -> 1 DeliveryController.
 * - B2: création headless à la demande si port USB prêt.
 * - Multi-listener: un seul setListener() sur le controller (MuxListener),
 *   puis N listeners UI attach/detach sans écrasement.
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

    // node -> session (controller + mux)
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

    /**
     * Retourne une session existante ou en crée une headless sur UsbSerialPort.
     * - fromDec défaut: 255
     * - node range: 1..250
     */
    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        NodeSession existing = sessions.get(node);
        if (existing != null) return existing.dc;

        if (port == null) return null;

        LcpLink link = new LcpLink(port, node, from, true);
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store);

        // Mux + sink permanent vers LogBus
        MuxListener mux = new MuxListener(node);
        mux.addListener(new LogBusSink(node));
        dc.setListener(mux);

        // init une fois
        try { dc.initialize(); } catch (Exception ignored) {}

        sessions.put(node, new NodeSession(dc, mux));
        return dc;
    }

    // ---------------------------
    // Multi-listener UI
    // ---------------------------

    public synchronized void attachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;
        s.mux.addListener(uiListener);
    }

    public synchronized void detachUiListener(int nodeDec, DeliveryControllerPort.Listener uiListener) {
        if (uiListener == null) return;
        int node = nodeDec & 0xFF;
        NodeSession s = sessions.get(node);
        if (s == null) return;
        s.mux.removeListener(uiListener);
    }

    /** Nettoyage best-effort (USB detach). */
    public synchronized void clearAll(boolean closeTransport) {
        for (NodeSession s : sessions.values()) {
            try { s.dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    // ---------------------------
    // internal
    // ---------------------------

    private static final class NodeSession {
        final DeliveryController dc;
        final MuxListener mux;
        NodeSession(DeliveryController dc, MuxListener mux) {
            this.dc = dc;
            this.mux = mux;
        }
    }

    /**
     * MuxListener: dispatch vers N listeners (tabs UI + sinks).
     */
    private static final class MuxListener implements DeliveryControllerPort.Listener {

        private final int node;
        private final CopyOnWriteArrayList<DeliveryControllerPort.Listener> listeners =
                new CopyOnWriteArrayList<>();

        MuxListener(int node) { this.node = node; }

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
     * Sink permanent: route les logs/controller vers LogBus avec node.
     * Ça permet au tab node de voir les actions API même s’il n’est pas actif.
     */
    private static final class LogBusSink implements DeliveryControllerPort.Listener {

        private final int node;

        LogBusSink(int node) { this.node = node; }

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
            LogBus.err(node, "ERR[" + context + "] " + (error != null ? error.getMessage() : ""));
        }

        @Override public void onLiveQty(double net, double gross) { }
        @Override public void onLiveStatus(String liveText) { }
        @Override public void onTicketInfo(String ticketNo, String deliveryUid) { }
    }
}
