package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.shared.access.OperationalMode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
    private final UUID productId = UUID.randomUUID();

    @Mock
    private CatalogService catalog;

    @Mock
    private StoreTaxRepository taxes;

    @Mock
    private LicenseRepository licenses;

    @Mock
    private InstallationStatusService installationStatus;

    @Mock
    private CurrentOrganization organization;

    @Mock
    private ProductIdentifierRepository identifiers;

    @Mock
    private ProductRepository products;

    @Mock
    private Clock clock;

    @Mock
    private Store store;

    @Mock
    private ProductIdentifier identifier;

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
    void usesDemoFiscalRegimeWithoutLicenseOnlyInDevelopmentMode() {
        configuredStoreAndProduct();
        when(product.getStoreId()).thenReturn(storeId);
        when(product.isTaxesIncluded()).thenReturn(true);
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of(tax));
        when(tax.getId()).thenReturn(taxId);
        when(tax.getStoreId()).thenReturn(storeId);
        when(tax.getPercentage()).thenReturn(new BigDecimal("21.00"));
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of());
        configuredOperationalMode(OperationalMode.DEVELOPMENT);

        assertThat(service.products()).singleElement().satisfies(view -> {
            assertThat(view.taxPercentage()).isEqualByComparingTo("21.00");
            assertThat(view.taxRegime()).isEqualTo("IVA");
        });
    }

    @Test
    void rejectsMissingLicenseOutsideDevelopmentMode() {
        configuredStoreAndProduct();
        when(taxes.findAllById(List.of(taxId))).thenReturn(List.of());
        when(licenses.findByTiendaIdOrderByValidaDesdeDesc(storeId)).thenReturn(List.of());
        configuredOperationalMode(OperationalMode.UNLINKED);

        assertThatThrownBy(() -> service.products())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No hay licencia activa");
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

    @Test
    void returnsOnlyTheActiveMemberPriceAlongsideTheSalePrice() {
        configuredPriceLookup("MEMBER-1");
        when(product.getPriceUseMode()).thenReturn(PriceUseMode.MEMBER_PRICE);
        when(product.getMemberPrice()).thenReturn(new BigDecimal("8.50"));
        when(product.getImageId()).thenReturn(UUID.randomUUID());

        var result = service.priceByIdentifier(" MEMBER-1 ");

        assertThat(result.salePrice()).isEqualByComparingTo("10.00");
        assertThat(result.hasImage()).isTrue();
        assertThat(result.activePriceType()).isEqualTo(PriceUseMode.MEMBER_PRICE);
        assertThat(result.memberPrice()).isEqualByComparingTo("8.50");
        assertThat(result.offerPrice()).isNull();
        assertThat(result.offerDiscountPercent()).isNull();
        assertThat(result.offerUntil()).isNull();
        verify(identifiers).findAllByStoreIdAndValor(storeId, "MEMBER-1");
    }

    @Test
    void returnsTheCurrentOfferPriceAndItsOptionalEndDate() {
        configuredPriceLookup("OFFER-1");
        when(product.getPriceUseMode()).thenReturn(PriceUseMode.OFFER_PRICE);
        when(product.isOfferActive()).thenReturn(true);
        when(product.getOfferFrom()).thenReturn(LocalDate.of(2026, 7, 1));
        when(product.getOfferUntil()).thenReturn(LocalDate.of(2026, 7, 31));
        when(product.getOfferPrice()).thenReturn(new BigDecimal("7.50"));

        var result = service.priceByIdentifier("OFFER-1");

        assertThat(result.hasImage()).isFalse();
        assertThat(result.activePriceType()).isEqualTo(PriceUseMode.OFFER_PRICE);
        assertThat(result.offerPrice()).isEqualByComparingTo("7.50");
        assertThat(result.offerUntil()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(result.memberPrice()).isNull();
        assertThat(result.offerDiscountPercent()).isNull();
    }

    @Test
    void returnsTheCurrentOfferDiscountAndItsOptionalEndDate() {
        configuredPriceLookup("DISCOUNT-1");
        when(product.getPriceUseMode()).thenReturn(PriceUseMode.OFFER_DISCOUNT);
        when(product.isOfferActive()).thenReturn(true);
        when(product.getOfferFrom()).thenReturn(LocalDate.of(2026, 7, 1));
        when(product.getOfferUntil()).thenReturn(LocalDate.of(2026, 7, 31));
        when(product.getOfferDiscountPercent()).thenReturn(new BigDecimal("20.00"));

        var result = service.priceByIdentifier("DISCOUNT-1");

        assertThat(result.activePriceType()).isEqualTo(PriceUseMode.OFFER_DISCOUNT);
        assertThat(result.offerDiscountPercent()).isEqualByComparingTo("20.00");
        assertThat(result.offerUntil()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(result.memberPrice()).isNull();
        assertThat(result.offerPrice()).isNull();
    }

    @Test
    void hidesAnExpiredOffer() {
        configuredPriceLookup("EXPIRED-1");
        when(product.getPriceUseMode()).thenReturn(PriceUseMode.OFFER_PRICE);
        when(product.isOfferActive()).thenReturn(true);
        when(product.getOfferFrom()).thenReturn(LocalDate.of(2026, 6, 1));
        when(product.getOfferUntil()).thenReturn(LocalDate.of(2026, 6, 30));

        var result = service.priceByIdentifier("EXPIRED-1");

        assertThat(result.activePriceType()).isEqualTo(PriceUseMode.NORMAL);
        assertThat(result.offerPrice()).isNull();
        assertThat(result.offerUntil()).isNull();
    }

    @Test
    void acceptsTheSameValueInSeveralIdentifierTypesWhenTheyBelongToOneProduct() {
        configuredPriceLookup("2");
        ProductIdentifier secondIdentifier = org.mockito.Mockito.mock(ProductIdentifier.class);
        when(secondIdentifier.getProductId()).thenReturn(productId);
        when(identifiers.findAllByStoreIdAndValor(storeId, "2"))
                .thenReturn(List.of(identifier, secondIdentifier));

        var result = service.priceByIdentifier("2");

        assertThat(result.productId()).isEqualTo(productId);
        assertThat(result.activePriceType()).isEqualTo(PriceUseMode.NORMAL);
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

    private void configuredOperationalMode(OperationalMode mode) {
        when(installationStatus.status()).thenReturn(new InstallationStatusService.InstallationStatus(
                UUID.randomUUID(),
                "INST-TEST",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-31T00:00:00Z"),
                mode,
                null));
    }

    private void configuredPriceLookup(String lookupValue) {
        when(store.getId()).thenReturn(storeId);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(organization.currentStore()).thenReturn(store);
        when(identifiers.findAllByStoreIdAndValor(storeId, lookupValue))
                .thenReturn(List.of(identifier));
        when(identifier.getProductId()).thenReturn(productId);
        when(products.findById(productId)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getStoreId()).thenReturn(storeId);
        when(product.isActive()).thenReturn(true);
        when(product.getSalePrice()).thenReturn(new BigDecimal("10.00"));
        when(clock.withZone(ZoneId.of("Atlantic/Canary"))).thenReturn(
                Clock.fixed(Instant.parse("2026-07-15T12:00:00Z"), ZoneOffset.UTC));
    }
}
