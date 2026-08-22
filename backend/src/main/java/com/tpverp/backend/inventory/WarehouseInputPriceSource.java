package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Product;
import java.math.BigDecimal;

public enum WarehouseInputPriceSource {
    PURCHASE,
    SALE,
    MEMBER,
    WHOLESALE,
    OFFER;

    public BigDecimal price(Product product) {
        var selected = switch (this) {
            case PURCHASE -> product.getPurchasePrice();
            case SALE -> product.getSalePrice();
            case MEMBER -> product.getMemberPrice();
            case WHOLESALE -> product.getWholesalePrice();
            case OFFER -> product.getOfferPrice();
        };
        return selected == null ? product.getPurchasePrice() : selected;
    }
}
