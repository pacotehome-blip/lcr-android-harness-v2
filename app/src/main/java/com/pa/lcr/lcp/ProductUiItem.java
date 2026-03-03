
package com.pa.lcr.lcp;

/**
 * Représentation UI d’un produit LCR.
 *
 * - product1 : index produit 1..16 (convention registre)
 * - label    : libellé affiché à l’opérateur
 *
 * Utilisé par :
 * - DeliveryControllerPort.Listener.onProductsUpdated(...)
 * - UI (Spinner / List / affichage simple)
 */
public final class ProductUiItem {

    public final int product1; // 1..16
    public final String label;

    public ProductUiItem(int product1, String label) {
        this.product1 = product1;
        this.label = label;
    }

    @Override
    public String toString() {
        // Important : utilisé directement par l’UI
        return label;
    }
}
