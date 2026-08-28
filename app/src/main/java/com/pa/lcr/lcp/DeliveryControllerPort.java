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

        /**
         * ✅ NEW (baseline-safe): Ticket info.
         * - ticketNo: TicketNumber registre (#23) en décimal (U32) si disponible, sinon null.
         * - deliveryUid: numero_livraison + "-" + ticketNo si disponible, sinon null.
         *
         * UI: afficher même si null (ex: "-"), sans bloquer.
         */
        default void onTicketInfo(String ticketNo, String deliveryUid) { /* no-op */ }

        // ✅ AJOUTÉ (28 août 2026, demande Paul — "juste le heartbeat de
        // la connexion") — isManualTrigger distingue un appel venant d'une
        // vraie action (Status B, entrée de tab) d'un simple ping
        // keep-alive automatique. Par défaut (si non redéfini), forwarde
        // vers la version 2-arg avec true — préserve exactement le
        // comportement actuel pour tout listener qui ne redéfinit que
        // l'ancienne version.
        default void onTicketInfo(String ticketNo, String deliveryUid, boolean isManualTrigger) {
            onTicketInfo(ticketNo, deliveryUid);
        }

        /**
         * ✅ NEW : notifie qu'un reset diagnostic (net/gross négatifs remis à zéro
         * sur le registre physique) vient d'avoir lieu — pour que l'UI persiste un
         * enregistrement d'audit (type=DIAGNOSTIC_RESET) et le mette en file pour
         * synchronisation Dataverse. woNum peut être vide si aucune livraison en
         * contexte (reset fait depuis le menu entretien/admin).
         */
        default void onDiagnosticReset(String woNum, double netBeforeL, double grossBeforeL) { /* no-op */ }

        /**
         * ✅ NEW (3 août 2026, demande Paul : "on est supposé avoir un backup automatique") —
         * notifie la FIN RÉELLE d'une livraison (événement DELIVERY_DONE), peu importe si
         * un bouton UI existe/est cliqué. Contrairement à retournerAuWorkOrder() (déclenché
         * uniquement par un clic explicite), ce callback est garanti à chaque livraison
         * terminée. netL/grossL sont les valeurs au moment exact de la fin (netAtDeliveryEnd/
         * grossAtDeliveryEnd), saleNo peut être vide si non lu.
         */
        default void onDeliveryFinished(String serialId, String ticketNo, String saleNo,
                                          double netL, double grossL) { /* no-op */ }
    }
}
