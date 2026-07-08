package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private CurrentOrganization organization;
    @Mock private StoreTaxRepository taxRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private FamilyRepository familyRepository;
    @Mock private SubfamilyRepository subfamilyRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductIdentifierRepository identifierRepository;
    @Mock private ProductPriceHistoryRepository priceHistoryRepository;
    @Mock private StockLevelRepository stockRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private Store store;

    private CatalogService service;
    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(store.getId()).thenReturn(storeId);
        lenient().when(organization.currentStore()).thenReturn(store);
        service = new CatalogService(
                organization, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                priceHistoryRepository, stockRepository, movementRepository,
                Clock.fixed(Instant.parse("2026-06-29T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void listsCatalogOnlyForAuthenticatedStoreWhenTwoStoresExist() {
        var authenticatedStore = org.mockito.Mockito.mock(Store.class);
        var firstStoreId = UUID.randomUUID();
        var authenticatedStoreId = UUID.randomUUID();
        when(authenticatedStore.getId()).thenReturn(authenticatedStoreId);
        when(organization.currentStore()).thenReturn(authenticatedStore);
        var firstStoreTax = new StoreTax(firstStoreId, new BigDecimal("21"), false);
        var authenticatedTax = new StoreTax(
                authenticatedStoreId, new BigDecimal("7"), false);
        when(taxRepository.findByStoreIdOrderByPorcentaje(any()))
                .thenAnswer(invocation -> firstStoreId.equals(invocation.getArgument(0))
                        ? List.of(firstStoreTax)
                        : List.of(authenticatedTax));

        assertThat(service.taxes()).containsExactly(authenticatedTax);
        verify(taxRepository).findByStoreIdOrderByPorcentaje(authenticatedStoreId);
    }

    @Test
    void onlyReturnsActiveTaxesForProductSelection() {
        var active = new StoreTax(storeId, new BigDecimal("7"), false);
        var inactive = new StoreTax(storeId, new BigDecimal("21"), false);
        inactive.deactivate();
        when(taxRepository.findByStoreIdOrderByPorcentaje(storeId)).thenReturn(List.of(active, inactive));

        assertThat(service.selectableTaxes()).containsExactly(active);
    }

    @Test
    void listsSubfamiliesForFamily() {
        var family = Family.general(storeId);
        var subfamily = new Subfamily(family.getId(), "Cafe");
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(subfamilyRepository.findByFamilyIdOrderByNombre(family.getId())).thenReturn(List.of(subfamily));

        assertThat(service.subfamilies(family.getId())).containsExactly(subfamily);
    }

    @Test
    void updatesTaxPercentageWithoutAllowingDuplicates() {
        var tax = new StoreTax(storeId, new BigDecimal("7"), false);
        when(taxRepository.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(taxRepository.findByStoreIdAndPorcentaje(storeId, new BigDecimal("10")))
                .thenReturn(Optional.empty());

        var updated = service.updateTax(tax.getId(), new BigDecimal("10"));

        assertThat(updated.getPercentage()).isEqualByComparingTo("10");
    }

    @Test
    void deletesOnlyNonDefaultTaxNotUsedByProducts() {
        var tax = new StoreTax(storeId, new BigDecimal("4"), false);
        when(taxRepository.findById(tax.getId())).thenReturn(Optional.of(tax));
        when(productRepository.existsByTaxId(tax.getId())).thenReturn(false);

        service.deleteTax(tax.getId());

        verify(taxRepository).delete(tax);
    }

    @Test
    void rejectsCrossIdentifierCollisionWhenCreatingProduct() {
        var request = productRequest(" ABC ", "EAN");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(identifierRepository.findByStoreIdAndValor(storeId, "ABC"))
                .thenReturn(Optional.of(new ProductIdentifier(
                        storeId, UUID.randomUUID(), IdentifierType.CODIGO_BARRAS, "ABC")));

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identificador");
    }

    @Test
    void createsProductWithPricesAndOffer() {
        var request = productRequest("ABC", "ABC");
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        when(productRepository.saveAndFlush(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.identifier(IdentifierType.CODIGO)).isEqualTo("ABC");
        assertThat(product.identifier(IdentifierType.CODIGO_BARRAS)).isEqualTo("ABC");
        assertThat(product.price(PriceTier.VENTA)).isEqualByComparingTo("2.50");
        assertThat(product.isOfferActive()).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPriceHistory>> history = ArgumentCaptor.forClass(List.class);
        verify(priceHistoryRepository).saveAll(history.capture());
        assertThat(history.getValue()).hasSize(3);
    }

    @Test
    void discountPriceRequiresActiveOfferData() {
        var base = productRequest("ABC", null);
        var request = new CatalogService.ProductRequest(
                base.familyId(), null, base.taxId(), ProductType.UNIT, DiscountType.DISCOUNT_PRICE,
                "Producto", null, null, BigDecimal.ZERO, true, "ABC", null,
                new BigDecimal("2.50"), null, null, null,
                false, null, null);
        when(familyRepository.findById(request.familyId())).thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));

        assertThatThrownBy(() -> service.createProduct(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message.product.discount_price_requires_offer");
    }

    @Test
    void updateStoresOnlyChangedPriceHistory() {
        var initial = productRequest("ABC", null);
        var product = new Product(
                storeId, initial.familyId(), null, initial.taxId(),
                "Producto", null, BigDecimal.ZERO, true);
        product.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        product.setPrice(PriceTier.VENTA, new BigDecimal("2.50"));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(initial.familyId()))
                .thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(initial.taxId()))
                .thenReturn(Optional.of(new StoreTax(storeId, new BigDecimal("7"), true)));
        var changed = new CatalogService.ProductRequest(
                initial.familyId(), null, initial.taxId(), ProductType.UNIT, DiscountType.NORMAL,
                "Producto", null, null, new BigDecimal("1.00"), true, "ABC", null,
                new BigDecimal("3.00"), null, null, null,
                false, null, null);

        service.updateProduct(product.getId(), changed);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ProductPriceHistory>> history = ArgumentCaptor.forClass(List.class);
        verify(priceHistoryRepository).saveAll(history.capture());
        assertThat(history.getValue()).hasSize(2);
    }

    @Test
    void updateRemovesBarcodeWhenItIsOmitted() {
        var request = productRequest("ABC", null);
        var product = new Product(
                storeId, request.familyId(), null, request.taxId(),
                "Producto", null, BigDecimal.ZERO, true);
        product.replaceIdentifier(IdentifierType.CODIGO, "ABC");
        product.replaceIdentifier(IdentifierType.CODIGO_BARRAS, "EAN");
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(familyRepository.findById(request.familyId()))
                .thenReturn(Optional.of(Family.general(storeId)));
        when(taxRepository.findById(request.taxId()))
                .thenReturn(Optional.of(new StoreTax(
                        storeId, new BigDecimal("7"), true)));

        service.updateProduct(product.getId(), request);

        assertThat(product.getBarcode()).isNull();
    }

    @Test
    void defaultWarehouseCannotBeDeleted() {
        var general = Warehouse.general(storeId);
        when(warehouseRepository.findById(general.getId())).thenReturn(Optional.of(general));

        assertThatThrownBy(() -> service.deleteWarehouse(general.getId()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deletesEmptySecondaryWarehouse() {
        var warehouse = new Warehouse(storeId, "SECUNDARIO");
        when(warehouseRepository.findById(warehouse.getId())).thenReturn(Optional.of(warehouse));
        when(stockRepository.sumQuantityByWarehouseId(warehouse.getId())).thenReturn(BigDecimal.ZERO);

        service.deleteWarehouse(warehouse.getId());

        verify(warehouseRepository).delete(warehouse);
    }

    private CatalogService.ProductRequest productRequest(String code, String barcode) {
        return new CatalogService.ProductRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), ProductType.UNIT, DiscountType.NORMAL,
                "Producto", null, null, BigDecimal.ZERO, true, code, barcode,
                new BigDecimal("2.50"), null, null, new BigDecimal("1.50"),
                true, java.time.LocalDate.of(2026, 6, 1), null);
    }
}
