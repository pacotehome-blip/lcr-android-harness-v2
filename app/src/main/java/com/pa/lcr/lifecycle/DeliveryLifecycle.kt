
package com.yourcompany.lcr.lifecycle

/**
 * DeliveryLifecycle
 *
 * Source unique de vérité côté APK pour l'état d'une livraison.
 * Ne doit jamais être déduit implicitement depuis DS/DC/flow.
 */
enum class DeliveryLifecycle {
    IDLE,        // aucune livraison
    PRESTART,    // configuration produit / preset
    STARTING,    // Cmd#0 START envoyé
    ACTIVE,      // begin=true confirmé
    PAUSED,      // livraison active mais flow arrêté
    ENDING,      // Cmd#2 END envoyé
    ENDED        // livraison terminée, ticket traité
}
