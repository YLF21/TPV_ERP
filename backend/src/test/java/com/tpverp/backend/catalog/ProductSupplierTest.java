package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductSupplierTest {

    private final Product product = new Product(
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            "Producto",
            null,
            BigDecimal.ZERO,
            true);
    private final Supplier supplier = new Supplier(
            new Company("B00000001", "Company", address()),
            "Proveedor",
            null,
            DocumentType.CIF,
            "B00000002",
            null,
            null,
            null,
            null);

    @Test
    void normalizesOptionalReference() {
        var link = new ProductSupplier(product, supplier, " ref-1 ");

        assertThat(link.getSupplierReference()).isEqualTo("REF-1");
        assertThat(link.getProductId()).isEqualTo(product.getId());
        assertThat(link.getSupplier()).isSameAs(supplier);

        link.changeReference(" ");

        assertThat(link.getSupplierReference()).isNull();
    }

    @Test
    void lastEntryDateNeverMovesBackwards() {
        var link = new ProductSupplier(product, supplier, null);

        link.registerEntry(LocalDate.of(2026, 6, 9));
        link.registerEntry(LocalDate.of(2026, 5, 1));

        assertThat(link.getLastEntryDate()).isEqualTo(LocalDate.of(2026, 6, 9));
    }

    @Test
    void acceptsSupplierReferenceWithExactly128Characters() {
        var link = new ProductSupplier(product, supplier, "a".repeat(128));

        assertThat(link.getSupplierReference()).hasSize(128);
    }

    @Test
    void rejectsSupplierReferenceWith129Characters() {
        var link = new ProductSupplier(product, supplier, null);

        assertThatThrownBy(() -> link.changeReference("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("128");
    }

    @Test
    void repositoryUsesExplicitAssociationNavigation() {
        assertThat(Arrays.stream(ProductSupplierRepository.class.getMethods())
                .map(Method::getName))
                .contains("findByProduct_IdAndSupplier_Id")
                .doesNotContain("findByProductIdAndSupplierId");
    }

    private static Map<String, String> address() {
        return Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
