package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerReceivablePrintServiceTest {

    @Test
    void buildsAuthoritativeCommercialDocumentAndSinglePaymentReceiptWithinCurrentStore() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(documents, payments, organization);
        var document = document(storeId);
        var payment = new DocumentPayment(document,
                new PaymentMethod(UUID.randomUUID(), "TRANSFERENCIA", true), 1,
                new BigDecimal("20.00"), true, null, null, null, "TR-1",
                Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.randomUUID());
        document.addPayment(payment); document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(payments.findByRequestId(payment.getRequestId())).thenReturn(Optional.of(payment));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(payment));

        var printable = service.document(document.getId());
        var receipt = service.paymentReceipt(document.getId(), payment.getRequestId());

        assertThat(printable.total()).isEqualByComparingTo("100.00");
        assertThat(printable.lines()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("Producto");
            assertThat(line.total()).isEqualByComparingTo("100.00");
        });
        assertThat(receipt.paymentId()).isEqualTo(payment.getRequestId());
        assertThat(receipt.amount()).isEqualByComparingTo("20.00");
        assertThat(receipt.remaining()).isEqualByComparingTo("80.00");
        assertThat(receipt.reference()).isEqualTo("TR-1");
    }

    @Test
    void receiptKeepsHistoricalRemainingAfterEachPayment() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(documents, payments, organization);
        var document = document(storeId);
        var method = new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true);
        var first = new DocumentPayment(document, method, 1, new BigDecimal("20.00"), true,
                null, null, null, null, Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        var second = new DocumentPayment(document, method, 2, new BigDecimal("30.00"), false,
                null, null, null, null, Instant.parse("2026-07-20T09:00:00Z"), null,
                null, null, null, null, UUID.fromString("00000000-0000-0000-0000-000000000001"));
        document.addPayment(first); document.addPayment(second); document.updatePaymentStatus();
        when(documents.findCustomerDocumentForPrint(document.getId(), storeId)).thenReturn(Optional.of(document));
        when(payments.findByRequestId(first.getRequestId())).thenReturn(Optional.of(first));
        when(payments.findByRequestId(second.getRequestId())).thenReturn(Optional.of(second));
        when(payments.findAllByDocumentoId(document.getId())).thenReturn(List.of(second, first));

        assertThat(service.paymentReceipt(document.getId(), first.getRequestId()).remaining())
                .isEqualByComparingTo("80.00");
        assertThat(service.paymentReceipt(document.getId(), second.getRequestId()).remaining())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void rejectsPaymentReceiptFromAnotherDocumentOrStore() {
        var documents = mock(CommercialDocumentRepository.class);
        var payments = mock(DocumentPaymentRepository.class);
        var organization = mock(CurrentOrganization.class);
        var store = mock(Store.class);
        var storeId = UUID.randomUUID();
        when(store.getId()).thenReturn(storeId); when(organization.currentStore()).thenReturn(store);
        var service = new CustomerReceivablePrintService(documents, payments, organization);
        var requested = document(storeId); var foreign = document(UUID.randomUUID());
        var requestId = UUID.randomUUID();
        var payment = new DocumentPayment(foreign,
                new PaymentMethod(UUID.randomUUID(), "EFECTIVO", true), 1,
                BigDecimal.TEN, true, null, null, null, null, Instant.now(), null,
                null, null, null, null, requestId);
        when(documents.findCustomerDocumentForPrint(requested.getId(), storeId)).thenReturn(Optional.of(requested));
        when(payments.findByRequestId(requestId)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> service.paymentReceipt(requested.getId(), requestId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CommercialDocument document(UUID storeId) {
        var document = new CommercialDocument(storeId, UUID.randomUUID(),
                CommercialDocumentType.FACTURA_VENTA, LocalDate.of(2026, 7, 16),
                UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(document, UUID.randomUUID(), 1, 1,
                "P1", "Producto", "VENTA", new BigDecimal("100.00"),
                BigDecimal.ZERO, true, "IVA", BigDecimal.ZERO));
        document.setParties(UUID.randomUUID(), null, null);
        document.confirm("FV-1", UUID.randomUUID(), Instant.parse("2026-07-16T10:00:00Z"), false);
        return document;
    }
}
