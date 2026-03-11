
package com.pa.lcr.lcp;

import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session manager multi-registre (clé = lcrnode_dec).
 * - Unicité: 1 node -> 1 DeliveryController.
 * - B2: création headless à la demande si port USB prêt.
 * - L'UI peut aussi enregistrer son controller (tab connecté) pour partager la session avec l'API.
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

    // node -> controller
    private final Map<Integer, DeliveryController> controllers = new LinkedHashMap<>();

    private RegisterSessionManager(Context appCtx) {
        this.appCtx = appCtx;
        this.store = new DeliveryLogStore(appCtx);
        this.store.purgeOlderThanDaysAsync(7);
    }

    public DeliveryLogStore getStore() {
        return store;
    }

    /** UI: injecte la session déjà connectée pour ce node. */
    public synchronized void setController(int node, DeliveryController controller) {
        if (controller == null) return;
        controllers.put(node & 0xFF, controller);
    }

    public synchronized DeliveryController getController(int node) {
        return controllers.get(node & 0xFF);
    }

    /**
     * B2: retourne une session existante ou en crée une headless sur UsbSerialPort.
     * - fromDec défaut: 255
     * - node range: 1..250
     */
    public synchronized DeliveryController getOrCreate(int nodeDec, int fromDec, UsbSerialPort port) {
        int node = nodeDec & 0xFF;
        int from = fromDec & 0xFF;

        DeliveryController existing = controllers.get(node);
        if (existing != null) return existing;

        if (port == null) return null;

        LcpLink link = new LcpLink(port, node, from, true);
        DeliveryController dc = new DeliveryController(link);
        dc.setLogStore(store); // ✅ DB active pour sessions API headless
        controllers.put(node, dc);
        return dc;
    }

    /** Nettoyage best-effort (USB detach) */
    public synchronized void clearAll(boolean closeTransport) {
        for (DeliveryController dc : controllers.values()) {
            try { dc.shutdown(closeTransport); } catch (Exception ignored) {}
        }
        controllers.clear();
    }
}
