
package com.pa.lcr.lcp;

import java.util.List;

/**
 * Contrat strict entre MainActivity et la couche protocolaire.
 *
 * Règles métier (baseline):
 * - A = aligner / recover (ne démarre jamais)
 * - C = intention nouvelle livraison: validate, align si besoin, START auto quand clean
 * - UI inchangée: seule la logique controller évolue
 *
 * Extensions compatibles via "default" pour ne pas casser les implémentations existantes.
 */
public interface DeliveryControllerPort {

    /* ===== Cycle de vie ===== */
    void initialize();
    void shutdown();

    /** ✅ Compat: MainActivity appelle shutdown(true/false) */
    default void shutdown(boolean closeTransport) { shutdown(); }

    /* ===== Produits ===== */
    void refreshProducts(); // NO-OP volontaire (contrat Java)
    void selectProduct(int product1to16);

    /* ===== Livraison ===== */
    void startDelivery(int product1to16, double presetNet);
    default void alignOrRecover() { /* no-op (compat) */ }
    void resumeIfPaused();
    void endDelivery();
    void requestStatus();

    /* ===== LIVE ===== */
    default void requestLiveSample() { /* no-op */ }
    default void requestLiveSnapshot() { /* no-op */ }

    /* ===== État ===== */
    DeliveryState getState();
    boolean isDeliveryActive();
    boolean isPaused();
    default boolean isFlowOffStable() { return true; }
    default long getFlowOffAgeMs() { return 0L; }

    /* ===== Options ===== */
    default void setTxRxLoggingEnabled(boolean enabled) { /* no-op (compat) */ }
    default void setLogTimestampsEnabled(boolean enabled) { /* no-op (compat) */ }

    /* ===== Events UI ===== */
    void setListener(Listener listener);

    interface Listener {
        void onStateChanged(DeliveryState state);
        void onProductsUpdated(List<ProductUiItem> products, int activeIndex0);
        void onLog(String message);
        void onError(String context, Throwable error);

        default void onLiveQty(double net, double gross) { /* no-op */ }
        default void onFlowStability(boolean flowActive, boolean flowOffStable, long flowOffAgeMs) { /* no-op */ }
        default void onLiveStatus(String liveText) { /* no-op */ }
    }
}
