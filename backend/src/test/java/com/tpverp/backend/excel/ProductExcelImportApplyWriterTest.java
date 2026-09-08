package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.CatalogService;
import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductExcelImportApplyWriterTest {
    private final CatalogService catalog = mock(CatalogService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final UUID storeId = UUID.randomUUID();
    private final UUID firstId = UUID.randomUUID();
    private final UUID secondId = UUID.randomUUID();
    private ProductExcelImportApplyWriter writer;

    @BeforeEach
    void setup() {
        Store store = mock(Store.class);
        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        writer = new ProductExcelImportApplyWriter(catalog, products, organization);
    }

    @Test
    void locksStoreThenProductsAndUpdatesExistingRowsInOneBatch() {
        Product first = product(firstId, 2L); Product second = product(secondId, 4L);
        when(products.findAllByStoreIdAndIdInForUpdate(storeId, List.of(firstId, secondId))).thenReturn(List.of(first, second));
        when(catalog.updateProducts(any())).thenReturn(List.of(first, second));
        var items = List.of(item(firstId, 2L), item(secondId, 4L));
        var result = writer.write(items);
        assertThat(result).hasSize(2).allSatisfy(row -> assertThat(row.mutated()).isTrue());
        verify(catalog).updateProducts(any());
        verify(catalog, never()).createOrUpdateFromImport(any(), any());
        var order = inOrder(catalog, products);
        order.verify(catalog).lockStoreForCatalogMutation(storeId);
        order.verify(products).findAllByStoreIdAndIdInForUpdate(storeId, List.of(firstId, secondId));
    }

    @Test
    void staleVersionPreventsAnyCatalogWrite() {
        Product first = product(firstId, 9L);
        when(products.findAllByStoreIdAndIdInForUpdate(storeId, List.of(firstId))).thenReturn(List.of(first));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(List.of(item(firstId, 2L))))
                .isInstanceOf(ProductExcelImportApplyWriter.StaleVersionException.class);
        verify(catalog, never()).updateProducts(any());
        verify(catalog, never()).createOrUpdateFromImport(any(), any());
    }

    @Test
    void propagatesIntermediateFailureSoOuterTransactionCanRollback() {
        Product first = product(firstId, 2L);
        when(products.findAllByStoreIdAndIdInForUpdate(storeId, List.of(firstId))).thenReturn(List.of(first));
        doThrow(new IllegalStateException("forced rollback")).when(catalog).updateProducts(any());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> writer.write(List.of(item(firstId, 2L))))
                .hasMessage("forced rollback");
    }

    private Product product(UUID id, long version) {
        Product product = mock(Product.class); when(product.getId()).thenReturn(id); when(product.getVersion()).thenReturn(version); return product;
    }

    private ProductExcelImportApplyService.WriteItem item(UUID id, long version) {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of(), Map.of("id", id.toString()), version, Map.of("name", Map.of("before", "A", "after", "B")), List.of());
        return new ProductExcelImportApplyService.WriteItem(row,
                new ProductExcelImportApplyService.ExpectedProduct(id, version), mock(CatalogService.ProductRequest.class));
    }
}
