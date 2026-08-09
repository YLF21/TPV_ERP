package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

class CommercialDocumentRepositoryPrintContractTest {
    @Test
    void printQueryDoesNotFetchTwoListBagsInOneQuery() throws Exception {
        var method = CommercialDocumentRepository.class.getDeclaredMethod(
                "findCustomerDocumentForPrint", UUID.class, UUID.class);
        assertThat(method.getAnnotation(EntityGraph.class).attributePaths())
                .containsExactly("lineas")
                .doesNotContain("pagos", "pagos.metodoPago");
    }

    @Test
    void printQueryIncludesSalesCorrectionInvoices() throws Exception {
        var method = CommercialDocumentRepository.class.getDeclaredMethod(
                "findCustomerDocumentForPrint", UUID.class, UUID.class);

        assertThat(method.getAnnotation(Query.class).value())
                .contains("CommercialDocumentType.RECTIFICATIVA_VENTA");
    }
}
