
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

    /* ===== Produits ===== */
    void refreshProducts(); // NO-OP volontaire (contrat Java)
    void selectProduct(int product1to16);

    /* ===== Livraison ===== */

    /**
     * C = intention de démarrer une NOUVELLE livraison.
     * Le controller valide 0x28:
     * - si clean -> START immédiat
     * - sinon -> alignOrRecover + START auto quand clean
     */
    void startDelivery(int product1to16, double presetNet);

    /**
     * A = aligner / recover (ticket pending, reprise après crash), sans intention start.
     */
    default void alignOrRecover() { /* no-op (compat) */ }

    /**
     * Continuer (resume) si une livraison est pausée (RUNNING_PAUSED).
     */
    void resumeIfPaused();

    /**
     * Terminer livraison (END). Réservé au bouton "Terminer" (pas A).
     */
    void endDelivery();

    /**
     * B = Diagnostic global (action utilisateur)
     */
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

    /* ===== Option support: afficher TX/RX ===== */
    default void setTxRxLoggingEnabled(boolean enabled) { /* no-op (compat) */ }

    /* ===== Events UI ===== */
    void setListener(Listener listener);

    interface Listener {
        void onStateChanged(DeliveryState state);
        void onProductsUpdated(List<ProductUiItem> products, int activeIndex0);
        void onLog(String message);
        void onError(String context, Throwable error);

        default void onLiveQty(double net, double gross) { /* no-op */ }
        default void onFlowStability(boolean flowActive, boolean flowOffStable, long flowOffAgeMs) { /* no-op */ }

        /**
         * ✅ Ajout: texte Live métier (CONNECTED — Ticket_pending (recovering), CONNECTED — Prêt à livrer, etc.)
         * Default no-op pour compatibilité.
         */
        default void onLiveStatus(String liveText) { /* no-op */ }
    }
}
