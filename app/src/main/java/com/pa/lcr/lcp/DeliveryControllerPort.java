
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
 * ✅ Extension compatible: stabilité du FLOW_OFF exposée (default).
 * ✅ Extension compatible: snapshot NET/GROSS hors boucle (default).
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

    /**
     * ✅ Snapshot NET/GROSS (une seule lecture) hors boucle liveTick.
     * Utilisé après crash/reconnect/resync pour afficher ce qui a été livré.
     */
    default void requestLiveSnapshot() { /* no-op */ }

    /* ===== État ===== */
    DeliveryState getState();
    boolean isDeliveryActive();
    boolean isPaused();

    /**
     * ✅ FLOW_OFF stable (tampon côté controller).
     * Permet au SDK/UI de savoir si le flow est OFF depuis assez longtemps.
     * Default pour compatibilité.
     */
    default boolean isFlowOffStable() { return true; }
    default long getFlowOffAgeMs() { return 0L; }

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

        /**
         * ✅ Notification fine: flow actif / flow off stable / âge du off.
         */
        default void onFlowStability(boolean flowActive, boolean flowOffStable, long flowOffAgeMs) { /* no-op */ }
    }
}
