
package com.pa.lcr.lcp;

public final class ProductUiItem {

    public final int product1;   // 1..16
    public final String label;

    public ProductUiItem(int product1, String label) {
        this.product1 = product1;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
