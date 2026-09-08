package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import com.tpverp.backend.inventory.StockLevelRepository;
import com.tpverp.backend.inventory.StockMovementRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CatalogServiceImportBatchTest {
    @Mock private CurrentOrganization organization;
    @Mock private StoreTaxRepository taxes;
    @Mock private WarehouseRepository warehouses;
    @Mock private FamilyRepository families;
    @Mock private SubfamilyRepository subfamilies;
    @Mock private ProductRepository products;
    @Mock private ProductIdentifierRepository identifiers;
    @Mock private ProductPriceHistoryRepository history;
    @Mock private StockLevelRepository stock;
    @Mock private StockMovementRepository movements;
    private final UUID storeId = UUID.randomUUID();
    private final Family family = Family.general(storeId);
    private final StoreTax tax = new StoreTax(storeId, new BigDecimal("21"), true);
    private CatalogService service;

    @BeforeEach
    void setUp() {
        var store = org.mockito.Mockito.mock(com.tpverp.backend.organization.Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        lenient().when(families.findByStoreIdAndIdIn(storeId, java.util.Set.of(family.getId()))).thenReturn(List.of(family));
        lenient().when(taxes.findByStoreIdAndIdIn(storeId, java.util.Set.of(tax.getId()))).thenReturn(List.of(tax));
        lenient().when(identifiers.findAllByStoreIdAndValorLowerIn(any(), anyCollection())).thenReturn(List.of());
        lenient().when(products.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new CatalogService(organization, taxes, warehouses, families, subfamilies, products,
                identifiers, history, stock, movements, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    @Test
    void validatesAndPersistsFiveThousandRowsWithOneBatchPerResource() {
        List<CatalogService.ProductRequest> requests = new ArrayList<>();
        for (int index = 0; index < 5_000; index++) requests.add(request("C" + index));

        service.createProductsFromImport(requests);

        verify(families, times(1)).findByStoreIdAndIdIn(storeId, java.util.Set.of(family.getId()));
        verify(taxes, times(1)).findByStoreIdAndIdIn(storeId, java.util.Set.of(tax.getId()));
        verify(identifiers, times(1)).findAllByStoreIdAndValorLowerIn(any(), anyCollection());
        verify(products, times(1)).saveAllAndFlush(any());
        verify(history, times(1)).saveAll(any());
    }

    @Test
    void rejectsCrossRowCodeAndBarcodeCollisionBeforeSaving() {
        assertThatThrownBy(() -> service.createProductsFromImport(List.of(request("SAME"), requestWithBarcode("SAME"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Identificador duplicado");
        verify(products, org.mockito.Mockito.never()).saveAllAndFlush(any());
    }

    @Test
    void permitsCodeAndBarcodeEqualOnTheSameRow() {
        service.createProductsFromImport(List.of(requestWithCodeAndBarcode("SAME")));
        verify(products).saveAllAndFlush(any());
    }

    @Test
    void rejectsCanonicallyEquivalentUnicodeIdentifiersBeforeSaving() {
        for (List<String> pair : List.of(List.of("CAFÉ", "CAFE\u0301"), List.of("STRASSE", "Straße"),
                List.of("A1", "\u00a0A1\u2007"))) {
            assertThatThrownBy(() -> service.createProductsFromImport(List.of(request(pair.get(0)), request(pair.get(1)))))
                    .isInstanceOf(ProductImportConflictException.class).hasMessageContaining("Identificador duplicado");
        }
        verify(products, org.mockito.Mockito.never()).saveAllAndFlush(any());
    }

    private CatalogService.ProductRequest request(String code) {
        return new CatalogService.ProductRequest(family.getId(), null, tax.getId(), ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, "Producto " + code, null, null,
                BigDecimal.ZERO, true, code, null, null, BigDecimal.ZERO, null, null, null, null, null,
                false, null, null, null, null, null, null, null);
    }

    private CatalogService.ProductRequest requestWithBarcode(String barcode) {
        return new CatalogService.ProductRequest(family.getId(), null, tax.getId(), ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, "Producto " + barcode, null, null,
                BigDecimal.ZERO, true, null, barcode, null, BigDecimal.ZERO, null, null, null, null, null,
                false, null, null, null, null, null, null, null);
    }

    private CatalogService.ProductRequest requestWithCodeAndBarcode(String value) {
        return new CatalogService.ProductRequest(family.getId(), null, tax.getId(), ProductType.UNIT,
                DiscountType.NORMAL, PriceUseMode.NORMAL, "Producto " + value, null, null,
                BigDecimal.ZERO, true, value, value, null, BigDecimal.ZERO, null, null, null, null, null,
                false, null, null, null, null, null, null, null);
    }
}
