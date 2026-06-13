package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CatalogDomainTest {

    private final UUID storeId = UUID.randomUUID();

    @Test
    void normalizesNamesAndIdentifiers() {
        var family = new Family(storeId, "  bebidas  ", false);
        var product = new Product(
                storeId, family.getId(), null, UUID.randomUUID(), "  refresco cola ",
                null, BigDecimal.ZERO, true);
        product.replaceIdentifier(IdentifierType.CODIGO, " abc-1 ");

        assertThat(family.getName()).isEqualTo("BEBIDAS");
        assertThat(product.getName()).isEqualTo("REFRESCO COLA");
        assertThat(product.identifier(IdentifierType.CODIGO)).isEqualTo("ABC-1");
    }

    @Test
    void validatesPricesAndOfferCoherence() {
        var product = product();

        product.setPrice(PriceTier.VENTA, BigDecimal.ZERO);
        product.setPrice(PriceTier.SOCIO, null);
        product.setPrice(PriceTier.OFERTA, new BigDecimal("1.25"));
        product.configureOffer(true, LocalDate.of(2026, 6, 1), null);

        assertThat(product.price(PriceTier.VENTA)).isEqualByComparingTo("0");
        assertThat(product.isOfferActive()).isTrue();
        assertThatThrownBy(() -> product.setPrice(PriceTier.SOCIO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> product.configureOffer(true, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultWarehouseCannotBeRenamedOrDisabled() {
        var warehouse = Warehouse.general(storeId);

        assertThatThrownBy(() -> warehouse.rename("OTRO"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> warehouse.deactivate(0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void secondaryWarehouseRequiresZeroStockToDeactivate() {
        var warehouse = new Warehouse(storeId, " tienda dos ");

        assertThatThrownBy(() -> warehouse.deactivate(-1))
                .isInstanceOf(IllegalStateException.class);
        warehouse.deactivate(0);

        assertThat(warehouse.isActive()).isFalse();
        assertThat(warehouse.getName()).isEqualTo("TIENDA DOS");
    }

    @Test
    void inactiveTaxCannotBeSelected() {
        var tax = new StoreTax(storeId, new BigDecimal("7.00"), false);
        tax.deactivate();

        assertThatThrownBy(tax::requireSelectable)
                .isInstanceOf(IllegalStateException.class);
    }

    private Product product() {
        return new Product(
                storeId, UUID.randomUUID(), null, UUID.randomUUID(), "PRODUCTO",
                null, BigDecimal.ZERO, true);
    }
}
