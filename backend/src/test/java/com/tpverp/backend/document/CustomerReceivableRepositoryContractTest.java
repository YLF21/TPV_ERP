package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class CustomerReceivableRepositoryContractTest {

    @Test
    void listExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findCustomerReceivables", UUID.class);
    }

    @Test
    void detailExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findCustomerReceivable", UUID.class, UUID.class);
    }

    @Test
    void lockedPaymentLookupExcludesDeliveryNotesCoveredByAnActiveInvoice() throws Exception {
        assertExcludesInvoicedOrigin("findLockedReceivable", UUID.class, UUID.class);
    }

    private static void assertExcludesInvoicedOrigin(
            String methodName, Class<?>... parameterTypes) throws Exception {
        var query = CommercialDocumentRepository.class
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
