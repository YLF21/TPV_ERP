package com.tpverp.backend.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.Family;
import com.tpverp.backend.catalog.FamilyRepository;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.StoreTax;
import com.tpverp.backend.catalog.StoreTaxRepository;
import com.tpverp.backend.catalog.SubfamilyRepository;
import com.tpverp.backend.document.DocumentLineCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PromotionCatalogGatewayTest {

    @Mock ProductRepository products;
    @Mock StoreTaxRepository taxes;
    @Mock FamilyRepository families;
    @Mock SubfamilyRepository subfamilies;
    @Mock Product product;
    @Mock StoreTax tax;

    @Test
    void loadsProductAndTaxInBatchesAndBuildsAuthoritativeSnapshot() {
        var storeId = UUID.randomUUID();
        var productId = UUID.randomUUID();
        var taxId = UUID.randomUUID();
        when(product.getId()).thenReturn(productId);
        when(product.getTaxId()).thenReturn(taxId);
        when(product.getCode()).thenReturn("CAT-1");
        when(product.getName()).thenReturn("Nombre catalogo");
        when(product.isTaxesIncluded()).thenReturn(true);
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        when(products.findAllByStoreIdAndIdIn(storeId, Set.of(productId))).thenReturn(List.of(product));
        when(taxes.findAllById(Set.of(taxId))).thenReturn(List.of(tax));

        var snapshot = gateway().products(storeId, List.of(productId)).get(productId)
                .authoritativeSnapshot(new DocumentLineCommand(
                        productId, BigDecimal.ONE, "FALSO", "Falso", "VENTA",
                        new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                        new BigDecimal("21.00")));

        assertThat(snapshot.codigo()).isEqualTo("CAT-1");
        assertThat(snapshot.nombre()).isEqualTo("Nombre catalogo");
        assertThat(snapshot.porcentajeImpuesto()).isEqualByComparingTo("21.00");
        verify(products).findAllByStoreIdAndIdIn(storeId, Set.of(productId));
        verify(taxes).findAllById(Set.of(taxId));
    }

    @Test
    void rejectsTaxSnapshotMismatch() {
        var snapshot = new PromotionCatalogGateway.ProductSnapshot(product, tax);
        when(product.isTaxesIncluded()).thenReturn(true);
        when(tax.getPercentage()).thenReturn(new BigDecimal("7.00"));

        assertThatThrownBy(() -> snapshot.validateTaxSnapshot(
                true, new BigDecimal("21.00"), "IVA"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("porcentajeImpuesto");
    }

    @Test
    void rejectsTargetFromAnotherStore() {
        var storeId = UUID.randomUUID();
        var familyId = UUID.randomUUID();
        var family = org.mockito.Mockito.mock(Family.class);
        when(family.getId()).thenReturn(familyId);
        when(family.getStoreId()).thenReturn(UUID.randomUUID());
        when(families.findAllById(Set.of(familyId))).thenReturn(List.of(family));

        assertThatThrownBy(() -> gateway().validateTargets(
                storeId, List.of(new PromotionCatalogGateway.TargetReference(
                        PromotionTargetType.FAMILY, familyId))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tienda");
    }

    private PromotionCatalogGateway gateway() {
        return new PromotionCatalogGateway(products, taxes, families, subfamilies);
    }
}
