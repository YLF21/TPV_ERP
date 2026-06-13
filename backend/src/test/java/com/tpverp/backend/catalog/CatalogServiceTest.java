package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock private TiendaRepository storeRepository;
    @Mock private StoreTaxRepository taxRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private FamilyRepository familyRepository;
    @Mock private SubfamilyRepository subfamilyRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductIdentifierRepository identifierRepository;
    @Mock private StockLevelRepository stockRepository;
    @Mock private StockMovementRepository movementRepository;
    @Mock private Tienda store;

    private CatalogService service;
    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(store.getId()).thenReturn(storeId);
        when(storeRepository.findAll()).thenReturn(List.of(store));
        service = new CatalogService(
                storeRepository, taxRepository, warehouseRepository, familyRepository,
                subfamilyRepository, productRepository, identifierRepository,
                stockRepository, movementRepository);
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
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var product = service.createProduct(request);

        assertThat(product.identifier(IdentifierType.CODIGO)).isEqualTo("ABC");
        assertThat(product.identifier(IdentifierType.CODIGO_BARRAS)).isEqualTo("ABC");
        assertThat(product.price(PriceTier.VENTA)).isEqualByComparingTo("2.50");
        assertThat(product.isOfferActive()).isTrue();
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
        when(stockRepository.sumQuantityByWarehouseId(warehouse.getId())).thenReturn(0L);

        service.deleteWarehouse(warehouse.getId());

        verify(warehouseRepository).delete(warehouse);
    }

    private CatalogService.ProductRequest productRequest(String code, String barcode) {
        return new CatalogService.ProductRequest(
                UUID.randomUUID(), null, UUID.randomUUID(), "Producto", null,
                BigDecimal.ZERO, true, code, barcode,
                new BigDecimal("2.50"), null, null, new BigDecimal("1.50"),
                true, java.time.LocalDate.of(2026, 6, 1), null);
    }
}
