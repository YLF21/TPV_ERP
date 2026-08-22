package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseInputLine;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductBulkImportServiceTest {

    @Mock private WarehouseInputRepository documents;
    @Mock private ProductRepository products;
    @Mock private SupplierRepository suppliers;
    @Mock private CurrentOrganization organization;
    @Mock private Store store;

    private ProductBulkImportService service;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        storeId = UUID.randomUUID();
        when(organization.currentStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        service = new ProductBulkImportService(documents, products, suppliers, organization);
    }

    @Test
    void keepsTheLastIncomingLineForRepeatedProduct() {
        UUID invoiceId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        WarehouseInput invoice = input(invoiceId, WarehouseInputDocumentType.FACTURA_ENTRADA,
                line(productId, "10.00", "5.00"),
                line(otherProductId, "3.00", "0.00"),
                line(productId, "12.00", "10.00"));
        Product product = mock(Product.class);
        Product otherProduct = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(otherProduct.getId()).thenReturn(otherProductId);
        when(documents.findByIdAndStoreId(invoiceId, storeId)).thenReturn(Optional.of(invoice));
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of(product, otherProduct));

        var imported = service.purchaseInvoiceProducts(invoiceId);

        assertThat(imported).hasSize(2);
        assertThat(imported.getFirst())
                .extracting(
                        ProductBulkImportService.PurchaseDocumentProductView::productId,
                        ProductBulkImportService.PurchaseDocumentProductView::grossPurchasePrice,
                        ProductBulkImportService.PurchaseDocumentProductView::purchaseDiscount)
                .containsExactly(productId, new BigDecimal("12.00"), new BigDecimal("10.00"));
    }

    @Test
    void importsProductsFromIncomingDeliveryNotes() {
        UUID deliveryNoteId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        WarehouseInput deliveryNote = input(deliveryNoteId, WarehouseInputDocumentType.ALBARAN_ENTRADA,
                line(productId, "6.50", "4.00"));
        Product product = mock(Product.class);
        when(product.getId()).thenReturn(productId);
        when(documents.findByIdAndStoreId(deliveryNoteId, storeId)).thenReturn(Optional.of(deliveryNote));
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection())).thenReturn(List.of(product));

        var imported = service.purchaseDeliveryNoteProducts(deliveryNoteId);

        assertThat(imported).singleElement()
                .extracting(
                        ProductBulkImportService.PurchaseDocumentProductView::productId,
                        ProductBulkImportService.PurchaseDocumentProductView::grossPurchasePrice,
                        ProductBulkImportService.PurchaseDocumentProductView::purchaseDiscount)
                .containsExactly(productId, new BigDecimal("6.50"), new BigDecimal("4.00"));
    }

    @Test
    void doesNotOfferDraftsOrProductsOutsideTheCurrentStore() {
        WarehouseInput draft = input(UUID.randomUUID(), WarehouseInputDocumentType.FACTURA_ENTRADA,
                line(UUID.randomUUID(), "15.00", "0.00"));
        when(draft.getStatus()).thenReturn(WarehouseInputStatus.BORRADOR);
        when(documents.findByStoreIdOrderByFechaDesc(storeId)).thenReturn(List.of(draft));

        assertThat(service.purchaseInvoices()).isEmpty();
    }

    private WarehouseInput input(UUID id, WarehouseInputDocumentType type, WarehouseInputLine... lines) {
        var input = mock(WarehouseInput.class);
        lenient().when(input.getId()).thenReturn(id);
        lenient().when(input.getDocumentType()).thenReturn(type);
        lenient().when(input.getStatus()).thenReturn(WarehouseInputStatus.CONFIRMADA);
        lenient().when(input.getNumber()).thenReturn(type == WarehouseInputDocumentType.FACTURA_ENTRADA ? "FE-1" : "AE-1");
        lenient().when(input.getDate()).thenReturn(LocalDate.of(2026, 8, 22));
        lenient().when(input.getLines()).thenReturn(List.of(lines));
        lenient().when(input.getTotal()).thenReturn(BigDecimal.TEN);
        return input;
    }

    private WarehouseInputLine line(UUID productId, String price, String discount) {
        var line = mock(WarehouseInputLine.class);
        lenient().when(line.getProductId()).thenReturn(productId);
        lenient().when(line.getPurchaseUnitPrice()).thenReturn(new BigDecimal(price));
        lenient().when(line.getDiscount()).thenReturn(new BigDecimal(discount));
        return line;
    }
}
