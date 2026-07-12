package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.party.DocumentType;
import com.tpverp.backend.party.Supplier;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
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
    void keepsLatestEntryWithGrossDiscountAndCalculatedNetPrice() {
        var link = new ProductSupplier(product, supplier, null);

        link.registerEntry(
                Instant.parse("2026-06-09T10:15:30Z"),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"));
        link.registerEntry(
                Instant.parse("2026-05-01T08:00:00Z"),
                new BigDecimal("8.00"),
                BigDecimal.ZERO);

        assertThat(link.isLastSupplier()).isTrue();
        assertThat(link.getLastEntryAt()).isEqualTo(Instant.parse("2026-06-09T10:15:30Z"));
        assertThat(link.getGrossPurchasePrice()).isEqualByComparingTo("10.00");
        assertThat(link.getPurchaseDiscount()).isEqualByComparingTo("15.00");
        assertThat(link.getNetPurchasePrice()).isEqualByComparingTo("8.50");
    }

    @Test
    void principalIsManualAndNetPriceIsNotPersisted() {
        var link = new ProductSupplier(product, supplier, null);
        link.makePrincipal();

        link.registerEntry(
                Instant.parse("2026-06-09T10:15:30Z"),
                new BigDecimal("10.00"),
                new BigDecimal("15.00"));

        assertThat(link.isPrincipal()).isTrue();
        assertThat(Arrays.stream(ProductSupplier.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .doesNotContain("netPurchasePrice");
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
