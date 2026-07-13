package com.tpverp.backend.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.document.DocumentLineType;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.SupplierRepository;
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
class ProductBulkImportServiceTest {

    @Mock private CommercialDocumentRepository documents;
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
    void keepsTheLastPurchaseInvoiceLineForRepeatedProduct() {
        UUID invoiceId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID otherProductId = UUID.randomUUID();
        CommercialDocument invoice = mock(CommercialDocument.class);
        DocumentLine first = productLine(productId, "10.00", "5.00");
        DocumentLine other = productLine(otherProductId, "3.00", "0.00");
        DocumentLine last = productLine(productId, "12.00", "10.00");
        Product product = mock(Product.class);
        Product otherProduct = mock(Product.class);
        when(documents.findPurchaseInvoiceForBulkEdit(storeId, invoiceId))
                .thenReturn(Optional.of(invoice));
        when(invoice.getLineas()).thenReturn(List.of(first, other, last));
        when(product.getId()).thenReturn(productId);
        when(otherProduct.getId()).thenReturn(otherProductId);
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of(product, otherProduct));

        var products = service.purchaseInvoiceProducts(invoiceId);

        assertThat(products).hasSize(2);
        assertThat(products.getFirst())
                .extracting(
                        ProductBulkImportService.PurchaseDocumentProductView::productId,
                        ProductBulkImportService.PurchaseDocumentProductView::grossPurchasePrice,
                        ProductBulkImportService.PurchaseDocumentProductView::purchaseDiscount)
                .containsExactly(
                        productId,
                        new BigDecimal("12.00"),
                        new BigDecimal("10.00"));
    }

    @Test
    void ignoresProductsThatDoNotBelongToTheCurrentStore() {
        UUID invoiceId = UUID.randomUUID();
        UUID localProductId = UUID.randomUUID();
        CommercialDocument invoice = mock(CommercialDocument.class);
        DocumentLine localLine = productLine(localProductId, "8.00", "2.00");
        DocumentLine foreignLine = productLine(UUID.randomUUID(), "99.00", "0.00");
        Product localProduct = mock(Product.class);
        when(documents.findPurchaseInvoiceForBulkEdit(storeId, invoiceId))
                .thenReturn(Optional.of(invoice));
        when(invoice.getLineas()).thenReturn(List.of(localLine, foreignLine));
        when(localProduct.getId()).thenReturn(localProductId);
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of(localProduct));

        var imported = service.purchaseInvoiceProducts(invoiceId);

        assertThat(imported)
                .extracting(ProductBulkImportService.PurchaseDocumentProductView::productId)
                .containsExactly(localProductId);
    }

    @Test
    void importsProductsFromPurchaseDeliveryNotes() {
        UUID deliveryNoteId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        CommercialDocument deliveryNote = mock(CommercialDocument.class);
        DocumentLine line = productLine(productId, "6.50", "4.00");
        Product product = mock(Product.class);
        when(documents.findPurchaseDeliveryNoteForBulkEdit(storeId, deliveryNoteId))
                .thenReturn(Optional.of(deliveryNote));
        when(deliveryNote.getLineas()).thenReturn(List.of(line));
        when(product.getId()).thenReturn(productId);
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection()))
                .thenReturn(List.of(product));

        var imported = service.purchaseDeliveryNoteProducts(deliveryNoteId);

        assertThat(imported).singleElement()
                .extracting(
                        ProductBulkImportService.PurchaseDocumentProductView::productId,
                        ProductBulkImportService.PurchaseDocumentProductView::grossPurchasePrice,
                        ProductBulkImportService.PurchaseDocumentProductView::purchaseDiscount)
                .containsExactly(productId, new BigDecimal("6.50"), new BigDecimal("4.00"));
    }

    @Test
    void doesNotOfferInvoicesWithoutProductsFromTheCurrentStore() {
        CommercialDocument invoice = mock(CommercialDocument.class);
        DocumentLine foreignLine = productLine(UUID.randomUUID(), "15.00", "0.00");
        when(documents.findPurchaseInvoicesForBulkEdit(storeId)).thenReturn(List.of(invoice));
        when(invoice.getLineas()).thenReturn(List.of(foreignLine));
        when(products.findAllByStoreIdAndIdIn(eq(storeId), anyCollection())).thenReturn(List.of());

        assertThat(service.purchaseInvoices()).isEmpty();
    }

    @Test
    void rejectsInvoicesOutsideCurrentStoreOrNotAvailableForImport() {
        UUID invoiceId = UUID.randomUUID();
        when(documents.findPurchaseInvoiceForBulkEdit(storeId, invoiceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.purchaseInvoiceProducts(invoiceId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no encontrada");
    }

    private DocumentLine productLine(UUID productId, String price, String discount) {
        DocumentLine line = mock(DocumentLine.class);
        when(line.getLineType()).thenReturn(DocumentLineType.PRODUCT);
        when(line.getProductoId()).thenReturn(productId);
        org.mockito.Mockito.lenient()
                .when(line.getPrecioUnitario()).thenReturn(new BigDecimal(price));
        org.mockito.Mockito.lenient()
                .when(line.getDescuento()).thenReturn(new BigDecimal(discount));
        return line;
    }
}
