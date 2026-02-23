
package com.pa.lcr.lcp;

import java.util.List;

/**
 * DeliveryControllerPort
 *
 * Contrat strict entre l'UI (MainActivity) et la couche protocolaire.
 *
 * UX VERROUILLÉE:
 * - L'état = ce que l'utilisateur saisit.
 * - PAS de lecture registre au connect.
 * - PAS de sync/refresh "magique" automatique.
 *
 * Donc:
 * - refreshProducts() existe uniquement pour respecter le contrat Java,
 *   mais est volontairement NO-OP dans l'implémentation actuelle.
 */
public interface DeliveryControllerPort {

    /* ==========================================================
     * Cycle de vie
     * ========================================================== */
    void initialize();
    void shutdown();

    /* ==========================================================
     * Produits
     * ========================================================== */

    /**
     * NO-OP volontaire (UX figée). Méthode conservée pour compatibilité interface.
     */
    void refreshProducts();

    /**
     * Demande de bascule du produit actif (action utilisateur).
     * @param product1to16 produit demandé (1..16)
     */
    void selectProduct(int product1to16);

    /* ==========================================================
     * Livraison
     * ========================================================== */
    void startDelivery(int product1to16, double presetNet);
    void resumeIfPaused();
    void endDelivery();

    /* ==========================================================
     * État
     * ========================================================== */
    DeliveryState getState();
    boolean isDeliveryActive();
    boolean isPaused();

    /* ==========================================================
     * Événements UI
     * ========================================================== */
    void setListener(Listener listener);

    interface Listener {
        void onStateChanged(DeliveryState state);

        void onProductsUpdated(List<ProductUiItem> products, int activeIndex0);

        /**
         * Log protocolaire / diagnostic (incluant TX/RX enrichis).
         */
        void onLog(String message);

        void onError(String context, Throwable error);
    }
}
