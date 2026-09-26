package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class SalesDocumentPrintFailureReportingTest {
    @Test void reportsSwallowedPrintFailureWithoutTurningCommittedSaleIntoFailure() {
        var printing = mock(CustomerReceivablePrintService.class);
        UUID document = UUID.randomUUID();
        var failure = new IllegalStateException("template failure");
        when(printing.document(document)).thenThrow(failure);
        @SuppressWarnings("unchecked") Consumer<RuntimeException> capture = mock(Consumer.class);
        var result = SalesDocumentCheckoutController.preparePrintDocument(printing, document, capture);
        verify(capture).accept(failure);
        assertThat(result.document()).isNull();
        assertThat(result.errorCode()).isEqualTo("document_print_preparation_failed");
        doThrow(new IllegalStateException("reporter unavailable")).when(capture).accept(failure);
        assertThat(SalesDocumentCheckoutController.preparePrintDocument(printing, document, capture).errorCode())
                .isEqualTo("document_print_preparation_failed");
    }
}
