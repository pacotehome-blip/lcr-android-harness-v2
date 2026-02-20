
package com.pa.lcr.lcp;

import java.util.List;

/**
 * DeliveryControllerPort
 *
 * Contrat strict entre l'UI (MainActivity) et la couche protocolaire.
 *
 * RÈGLES :
 *  - L'UI n'envoie que des INTENTIONS.
 *  - AUCUNE méthode ici ne garantit une exécution immédiate.
 *  - Le controller décide du "quand" et du "comment" (poll, sync, queued, etc.).
 *
 * Aligné DSK / LCP multi-drop RS-232.
 */
public interface DeliveryControllerPort {

    /* ==========================================================
     * Cycle de vie
     * ========================================================== */

    /**
     * Initialise la session LCP.
     * Appelé UNE FOIS après ouverture du lien physique.
     *
     * Doit :
     *  - initialiser l'état interne
     *  - optionnellement faire un sync-first best effort
     *  - NE PAS lancer de polling agressif
     */
    void initialize();

    /**
     * Arrêt propre du controller.
     * Doit :
     *  - stopper tout polling
     *  - annuler toute commande en cours
     *  - libérer les ressources
     */
    void shutdown();

    /* ==========================================================
     * Produits
     * ========================================================== */

    /**
     * Rafraîchit le produit actif depuis le registre.
     *
     * Utilisé :
     *  - au connect
     *  - bouton A
     *
     * Doit :
     *  - lire Field #0 (index actif)
     *  - lire Field #1 (code produit)
     *  - publier la liste UI
     *
     * NE DOIT PAS :
     *  - poller 0x28
     *  - forcer une sync agressive
     */
    void refreshProducts();

    /**
     * Demande de bascule du produit actif.
     *
     * Appelée UNIQUEMENT suite à une action utilisateur.
     *
     * Doit :
     *  - suspendre tout polling
     *  - appliquer SET Field #0
     *  - gérer queued (0x26 → 0x7D)
     *  - confirmer via GET Field #0
     *
     * @param product1to16 produit demandé (1..16)
     */
    void selectProduct(int product1to16);

    /* ==========================================================
     * Livraison
     * ========================================================== */

    /**
     * Démarre une livraison (OPEN MODE).
     *
     * Doit :
     *  - garantir que le bon produit est actif
     *  - écrire le preset (Field #6)
     *  - envoyer RUN (CMD 0x00)
     *  - démarrer le monitor uniquement APRÈS FLOW_ACTIVE
     *
     * @param product1to16 produit à livrer
     * @param presetNet    quantité nette
     */
    void startDelivery(int product1to16, double presetNet);

    /**
     * Reprend une livraison en pause.
     *
     * Doit :
     *  - vérifier DELIVERY_ACTIVE && !FLOW_ACTIVE
     *  - envoyer RUN (CMD 0x00)
     */
    void resumeIfPaused();

    /**
     * Termine une livraison en cours.
     *
     * Doit :
     *  - envoyer END (CMD 0x02)
     *  - attendre clear DELIVERY/FLOW
     *  - arrêter le monitor
     */
    void endDelivery();

    /* ==========================================================
     * État / lecture
     * ========================================================== */

    /**
     * État courant du controller (pour l'UI).
     */
    DeliveryState getState();

    /**
     * Indique si une livraison est active (flow ou pause).
     */
    boolean isDeliveryActive();

    /**
     * Indique si la livraison est en pause.
     */
    boolean isPaused();

    /* ==========================================================
     * Événements UI
     * ========================================================== */

    void setListener(Listener listener);

    interface Listener {

        /**
         * Changement d'état global.
         */
        void onStateChanged(DeliveryState state);

        /**
         * Mise à jour de la liste des produits UI.
         *
         * @param products        liste 1..16
         * @param activeIndex0    index actif (0..15)
         */
        void onProductsUpdated(
                List<ProductUiItem> products,
                int activeIndex0
        );

        /**
         * Log protocolaire (diagnostic).
         */
        void onLog(String message);

        /**
         * Erreur fonctionnelle ou protocolaire.
         */
        void onError(String context, Throwable error);
    }
}
