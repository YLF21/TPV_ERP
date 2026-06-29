package com.tpverp.frontend.common.sales;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductCatalogTest {

    @Test
    void findsProductByCodeOrBarcode() {
        ProductCatalog catalog = new ProductCatalog(List.of(
                product("20", "8410000000200", "Articulo Mostrador"),
                product("30", "8410000000300", "Articulo Caja")
        ));

        assertEquals("20", catalog.findByCodeOrBarcode("20").orElseThrow().code());
        assertEquals("30", catalog.findByCodeOrBarcode("8410000000300").orElseThrow().code());
    }

    @Test
    void searchesByCodeBarcodeOrNameForDialog() {
        ProductCatalog catalog = new ProductCatalog(List.of(
                product("20", "8410000000200", "Articulo Mostrador"),
                product("30", "8410000000300", "Caja Especial")
        ));

        assertEquals(List.of("20"), catalog.search("mostrador").stream().map(ProductSnapshot::code).toList());
        assertEquals(List.of("30"), catalog.search("8410000000300").stream().map(ProductSnapshot::code).toList());
        assertEquals(2, catalog.search("").size());
        assertTrue(catalog.search("no existe").isEmpty());
    }

    private ProductSnapshot product(String code, String barcode, String name) {
        return new ProductSnapshot(code, barcode, name, new BigDecimal("1.00"), 1);
    }
}
