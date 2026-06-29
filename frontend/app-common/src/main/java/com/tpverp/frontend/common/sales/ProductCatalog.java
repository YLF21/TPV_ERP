package com.tpverp.frontend.common.sales;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ProductCatalog {

    private final List<ProductSnapshot> products;

    public ProductCatalog(List<ProductSnapshot> products) {
        this.products = List.copyOf(products);
    }

    public Optional<ProductSnapshot> findByCodeOrBarcode(String value) {
        String needle = normalize(value);
        if (needle.isBlank()) {
            return Optional.empty();
        }
        return products.stream()
                .filter(product -> normalize(product.code()).equals(needle) || normalize(product.barcode()).equals(needle))
                .findFirst();
    }

    public List<ProductSnapshot> search(String query) {
        String needle = normalize(query);
        if (needle.isBlank()) {
            return products;
        }
        return products.stream()
                .filter(product -> normalize(product.code()).contains(needle)
                        || normalize(product.barcode()).contains(needle)
                        || normalize(product.name()).contains(needle))
                .toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
