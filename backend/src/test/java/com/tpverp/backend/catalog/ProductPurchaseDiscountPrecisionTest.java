package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ProductPurchaseDiscountPrecisionTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "100", "24.87531", "99.99999", "24.8753100"})
    void preservesFiveDecimalPurchaseDiscountThroughBulkSnapshot(String value) {
        Product product = product();
        product.configurePurchaseDiscount(new BigDecimal(value));

        var snapshot = ProductBulkEditContent.ProductData.fromProduct(product);
        var row = new ProductBulkEditContent.Row("row-1", true, "P-1", snapshot,
                ProductBulkEditContent.ProductData.empty(), List.of(), null);

        assertThat(ProductBulkEditContent.validateAndCopy(List.of(row)).getFirst()
                .effectiveProduct().purchaseDiscountPercent()).isEqualTo(value);
        assertThat(product.getPurchaseDiscountPercent()).isEqualByComparingTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.00001", "100.00001", "24.875311"})
    void rejectsOutOfRangeOrLossyPrecisionInProductAndBulk(String value) {
        Product product = product();
        assertThatThrownBy(() -> product.configurePurchaseDiscount(new BigDecimal(value)))
                .isInstanceOf(IllegalArgumentException.class);

        // Simulate a client snapshot independently of the entity's validation.
        ReflectionTestUtils.setField(product, "purchaseDiscountPercent", new BigDecimal(value));
        var snapshot = ProductBulkEditContent.ProductData.fromProduct(product);
        var row = new ProductBulkEditContent.Row("row-1", true, "P-1", snapshot,
                ProductBulkEditContent.ProductData.empty(), List.of(), null);
        assertThatThrownBy(() -> ProductBulkEditContent.validateAndCopy(List.of(row)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("purchaseDiscountPercent");
    }

    private static Product product() {
        var product = new Product(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                ProductType.UNIT, DiscountType.NORMAL, "Producto", null, null, new BigDecimal("10"), true);
        product.replaceIdentifier(IdentifierType.CODIGO, "P-1");
        product.setPrice(PriceTier.VENTA, new BigDecimal("20"));
        return product;
    }
}
