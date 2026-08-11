package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.party.SupplierRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class DocumentReportServiceTest {

    @Test
    void appliesTheAggregatedRefundTotalToTheRectificationPendingAmount() {
        var storeId = UUID.randomUUID();
        var documentId = UUID.randomUUID();
        var store = mock(Store.class);
        var document = mock(CommercialDocument.class);
        var documents = mock(CommercialDocumentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var customers = mock(CustomerRepository.class);
        var suppliers = mock(SupplierRepository.class);
        var warehouses = mock(WarehouseRepository.class);
        var attributions = mock(DocumentAttributionResolver.class);
        var refundTenders = mock(RefundTenderRepository.class);
        var refundTotal = mock(RefundTenderRepository.RefundDocumentTotal.class);

        when(store.getId()).thenReturn(storeId);
        when(organization.currentStore()).thenReturn(store);
        when(document.getId()).thenReturn(documentId);
        when(document.getTipo()).thenReturn(CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(document.getEstado()).thenReturn(DocumentStatus.CONFIRMADO);
        when(document.getTotal()).thenReturn(new BigDecimal("-1000000.00"));
        when(document.getPendingTotal()).thenReturn(new BigDecimal("-1000000.00"));
        when(document.getLineas()).thenReturn(List.of());
        when(document.getPagos()).thenReturn(List.of());
        when(documents.findReportDocuments(eq(storeId), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(document));
        when(customers.findAllById(any())).thenReturn(List.of());
        when(suppliers.findAllById(any())).thenReturn(List.of());
        when(warehouses.findAllById(any())).thenReturn(List.of());
        when(attributions.resolve(anyCollection())).thenReturn(Map.of());
        when(refundTotal.getRefundDocumentId()).thenReturn(documentId);
        when(refundTotal.getTotalAmount()).thenReturn(new BigDecimal("1000000.00"));
        when(refundTenders.sumByRefundDocumentIds(eq(storeId), anyCollection()))
                .thenReturn(List.of(refundTotal));

        var service = new DocumentReportService(
                documents, organization, customers, suppliers, warehouses,
                attributions, refundTenders);

        var result = service.listInvoices(500, null, true, false);

        assertThat(result.items()).singleElement()
                .extracting(DocumentReportView::pendiente)
                .isEqualTo(BigDecimal.ZERO.setScale(2));
        verify(refundTenders).sumByRefundDocumentIds(eq(storeId), anyCollection());
    }
}
