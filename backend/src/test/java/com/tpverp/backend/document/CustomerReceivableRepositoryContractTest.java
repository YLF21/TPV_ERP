package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;

class CustomerReceivableRepositoryContractTest {

    @Test
    void listExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findCustomerReceivables", UUID.class);
    }

    @Test
    void paidListExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findCustomerReceivablesIncludingPaid", UUID.class);
    }

    @Test
    void paymentHistoryExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin(DocumentPaymentRepository.class,
                "findCustomerReceivablePaymentHistory",
                UUID.class, java.time.Instant.class, java.time.Instant.class,
                boolean.class, UUID.class, boolean.class, UUID.class);
    }

    @Test
    void printablePaymentExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin(DocumentPaymentRepository.class,
                "findCustomerReceivablePayment",
                UUID.class, UUID.class, UUID.class);
    }

    @Test
    void detailExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findCustomerReceivable", UUID.class, UUID.class);
    }

    @Test
    void lockedPaymentLookupExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findLockedReceivable", UUID.class, UUID.class);
    }

    @Test
    void invoiceRelationLocksBothDocumentsBeforeCheckingAccountingState() throws Exception {
        var method = CommercialDocumentRepository.class.getDeclaredMethod(
                "findLockedDocument", UUID.class, UUID.class);

        assertThat(method.getAnnotation(Lock.class).value())
                .isEqualTo(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    private static void assertExcludesInvoicedOrigin(
            String methodName, Class<?>... parameterTypes) throws Exception {
        assertExcludesInvoicedOrigin(
                CommercialDocumentRepository.class, methodName, parameterTypes);
    }

    private static void assertExcludesInvoicedOrigin(
            Class<?> repository, String methodName, Class<?>... parameterTypes) throws Exception {
        var query = repository
                .getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Query.class)
                .value();

        assertThat(query)
                .contains("relation.origen.id = document.id")
                .contains("DocumentRelationType.FACTURA_DE")
                .contains("relation.documento.estado not in")
                .contains("DocumentStatus.BORRADOR")
                .contains("DocumentStatus.ANULADO");
    }
}
