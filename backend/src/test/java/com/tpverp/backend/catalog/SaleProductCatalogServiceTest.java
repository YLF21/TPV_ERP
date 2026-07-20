package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SaleProductCatalogServiceTest {

    private final UUID storeId = UUID.randomUUID();
    private final UUID taxId = UUID.randomUUID();

    @Mock
    private CatalogService catalog;

    @Mock
    private StoreTaxRepository taxes;

    @Mock
    private LicenseRepository licenses;

    @Mock
    private CurrentOrganization organization;

    @Mock
    private Store store;

    @Mock
    private Product product;

    @Mock
    private StoreTax tax;

    @Mock
    private License license;

    @InjectMocks
    private SaleProductCatalogService service;

    @Test
    void exposesAuthoritativeTaxSnapshotForSaleProducts() {
        configuredSaleCatalog();

        var result = service.products();

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.taxPercentage()).isEqualByComparingTo("21.00");
            assertThat(view.taxRegime()).isEqualTo("IVA");
            assertThat(view.taxesIncluded()).isTrue();
        });
    }

    @Test
    void rejectsProductsWithoutAnAuthoritativeTax() {
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of());
        configuredActiveLicense(license, TaxRegime.IVA);

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Impuesto de producto no encontrado");
    }

    @Test
    void rejectsProductsFromAnotherStore() {
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(UUID.randomUUID());
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        configuredActiveLicense(license, TaxRegime.IVA);

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Producto no pertenece a la tienda actual");
    }

    @Test
    void rejectsTaxesFromAnotherStore() {
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(UUID.randomUUID());
        configuredActiveLicense(license, TaxRegime.IVA);

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("impuesto del producto no pertenece a la tienda");
    }

    @Test
    void rejectsInactiveTaxes() {
        StoreTax inactiveTax = new StoreTax(storeId, new BigDecimal("21.00"), false);
        inactiveTax.deactivate();
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(inactiveTax.getId());
        when(taxes.findAllById(List.of(inactiveTax.getId()))).thenReturn(List.of(inactiveTax));
        configuredActiveLicense(license, TaxRegime.IVA);

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("impuesto seleccionado no esta activo");
    }

    @Test
    void rejectsLicensesFromAnotherStore() {
        configuredStoreAndProduct();
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of(license));
        when(license.isActiva()).thenReturn(true);
        when(license.getTiendaId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("licencia no pertenece a la tienda actual");
    }

    @Test
    void skipsAInactiveNewestLicenseAndSelectsTheNextActiveLicense() {
        License olderLicense = org.mockito.Mockito.mock(License.class);
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        when(license.isActiva()).thenReturn(false);
        when(olderLicense.isActiva()).thenReturn(true);
        when(olderLicense.getTiendaId()).thenReturn(storeId);
        when(olderLicense.getRegimenImpuesto()).thenReturn(TaxRegime.IGIC);
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId))
                .thenReturn(List.of(license, olderLicense));

        assertThat(service.products()).singleElement()
                .extracting(SaleProductView::taxRegime)
                .isEqualTo("IGIC");
    }

    private void configuredSaleCatalog() {
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(product.isTaxesIncluded()).thenReturn(true);
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        configuredActiveLicense(license, TaxRegime.IVA);
    }

    private void configuredStoreAndProduct() {
        when(store.getId()).thenReturn(storeId);
        when(product.getTaxId()).thenReturn(taxId);
        when(organization.currentStore()).thenReturn(store);
        when(catalog.products()).thenReturn(List.of(product));
    }

    private void configuredActiveLicense(License value, TaxRegime taxRegime) {
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of(value));
        when(value.isActiva()).thenReturn(true);
        when(value.getTiendaId()).thenReturn(storeId);
        when(value.getRegimenImpuesto()).thenReturn(taxRegime);
    }
}
