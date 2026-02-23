
package com.pa.lcr.lcp;

import java.util.List;

/**
 * Contrat strict entre MainActivity et la couche protocolaire.
 * UX figée:
 * - pas de lecture registre au connect
 * - pas de refresh automatique
 * - toutes lectures uniquement sur action utilisateur (boutons)
 */
public interface DeliveryControllerPort {

    /* ===== Cycle de vie ===== */
    void initialize();
    void shutdown();

    /* ===== Produits ===== */
    void refreshProducts(); // NO-OP volontaire (contrat Java)
    void selectProduct(int product1to16);

    /* ===== Livraison ===== */
    void startDelivery(int product1to16, double presetNet); // C
    void resumeIfPaused();                                  // Continuer
    void endDelivery();                                     // A et Finish
    void requestStatus();                                   // B

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
    }
}
