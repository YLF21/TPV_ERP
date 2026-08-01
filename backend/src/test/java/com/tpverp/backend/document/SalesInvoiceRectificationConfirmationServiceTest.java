package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class SalesInvoiceRectificationConfirmationServiceTest {

    @Mock private SalesInvoiceRectificationService rectifications;
    @Mock private DocumentService documents;

    private SalesInvoiceRectificationConfirmationService service;

    @BeforeEach
    void setUp() {
        service = new SalesInvoiceRectificationConfirmationService(rectifications, documents);
    }

    @Test
    void verifiesRectificationMetadataAndTypeBeforeConfirming() {
        var id = UUID.randomUUID();
        var authentication = authentication();
        var document = mock(CommercialDocument.class);
        var original = mock(CommercialDocument.class);
        var metadata = mock(SalesInvoiceRectification.class);
        var before = new SalesInvoiceRectificationService.Details(
                document, original, metadata);
        var after = new SalesInvoiceRectificationService.Details(
                document, original, metadata);
        when(document.getTipo()).thenReturn(CommercialDocumentType.RECTIFICATIVA_VENTA);
        when(rectifications.details(id)).thenReturn(before, after);

        var result = service.confirm(id, authentication);

        assertThat(result).isSameAs(after);
        var ordered = inOrder(rectifications, documents);
        ordered.verify(rectifications).details(id);
        ordered.verify(documents).confirm(id, authentication);
        ordered.verify(rectifications).details(id);
    }

    @Test
    void neverConfirmsARegularSalesDocumentThroughRectificationRoute() {
        var id = UUID.randomUUID();
        var authentication = authentication();
        var document = mock(CommercialDocument.class);
        when(document.getTipo()).thenReturn(CommercialDocumentType.FACTURA_VENTA);
        when(rectifications.details(id)).thenReturn(
                new SalesInvoiceRectificationService.Details(
                        document,
                        mock(CommercialDocument.class),
                        mock(SalesInvoiceRectification.class)));

        assertThatThrownBy(() -> service.confirm(id, authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no es una factura rectificativa");

        verify(documents, never()).confirm(id, authentication);
    }

    @Test
    void neverConfirmsWhenRectificationMetadataDoesNotExist() {
        var id = UUID.randomUUID();
        var authentication = authentication();
        when(rectifications.details(id)).thenThrow(
                new IllegalStateException(
                        "La factura rectificativa no tiene metadatos fiscales"));

        assertThatThrownBy(() -> service.confirm(id, authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadatos fiscales");

        verify(documents, never()).confirm(id, authentication);
    }

    private static UsernamePasswordAuthenticationToken authentication() {
        return UsernamePasswordAuthenticationToken.authenticated(
                "ADMIN", "", List.of());
    }
}
