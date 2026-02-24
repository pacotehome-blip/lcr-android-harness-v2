
package com.pa.lcr.lcp;

import java.util.List;

/**
 * Contrat strict entre MainActivity et la couche protocolaire.
 * UX figée:
 * - pas de lecture registre au connect
 * - pas de refresh automatique
 * - lectures uniquement sur action utilisateur (boutons)
 *
 * ✅ Extension compatible: LIVE ajouté en "default" (ne casse pas les implémentations existantes).
 */
public interface DeliveryControllerPort {

    /* ===== Cycle de vie ===== */
    void initialize();
    void shutdown();

    /* ===== Produits ===== */
    void refreshProducts(); // NO-OP volontaire (contrat Java)
    void selectProduct(int product1to16);

    /* ===== Livraison ===== */
    void startDelivery(int product1to16, double presetNet); // C = Start
    void resumeIfPaused(); // Continuer
    void endDelivery(); // A = End + Finish = End

    /**
     * B = Diagnostic global (action utilisateur)
     * Doit confirmer ce qui bloque ou ce qui ne va pas (delivery/ticket/printer).
     */
    void requestStatus();

    /**
     * ✅ LIVE tick (optionnel): par défaut no-op pour compatibilité.
     * Sert à mettre à jour NET/GROSS quand FLOW_ACTIVE est ON.
     */
    default void requestLiveSample() { /* no-op */ }

    /* ===== État ===== */
    DeliveryState getState();
    boolean isDeliveryActive();
    boolean isPaused();

    /* ===== Events UI ===== */
    void setListener(Listener listener);

    interface Listener {
        void onStateChanged(DeliveryState state);
        void onProductsUpdated(List<ProductUiItem> products, int activeIndex0);
        void onLog(String message);
        void onError(String context, Throwable error);

        /**
         * ✅ LIVE quantités (optionnel): par défaut no-op pour compatibilité.
         */
        default void onLiveQty(double net, double gross) { /* no-op */ }
    }
}
