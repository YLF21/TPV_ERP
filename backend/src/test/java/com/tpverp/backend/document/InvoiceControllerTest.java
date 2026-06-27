package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class InvoiceControllerTest {

    @Mock
    private DocumentService service;
    @Mock
    private DocumentFiscalQrService fiscalQr;

    @Test
    void payPassesRealAuthenticationToDocumentService() {
        var controller = new InvoiceController(service, fiscalQr);
        var invoiceId = UUID.randomUUID();
        var methodId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken("ADMIN", "token");
        var paid = document();
        var expectedPayments = List.of(new PaymentCommand(
                methodId, new BigDecimal("10.00"), true, null, null));
        when(service.payInvoice(eq(invoiceId), eq(expectedPayments), same(authentication)))
                .thenReturn(paid);
        when(fiscalQr.qrUrl(paid.getId())).thenReturn("qr-url");

        var view = controller.pay(
                invoiceId,
                new PaymentRequest(List.of(new PaymentRequest.Item(
                        methodId, new BigDecimal("10.00"), true, null, null, null))),
                authentication);

        verify(service).payInvoice(
                eq(invoiceId),
                argThat(expectedPayments::equals),
                same(authentication));
        assertThat(view.id()).isEqualTo(paid.getId());
        assertThat(view.qrUrl()).isEqualTo("qr-url");
    }

    private static CommercialDocument document() {
        var document = new CommercialDocument(
                UUID.randomUUID(), UUID.randomUUID(), CommercialDocumentType.FACTURA_VENTA,
                LocalDate.of(2026, 6, 27), UUID.randomUUID(), BigDecimal.ZERO);
        document.addLine(new DocumentLine(
                document, UUID.randomUUID(), 1, 1, "P-1", "Producto", "VENTA",
                new BigDecimal("10.00"), BigDecimal.ZERO, true, "IVA",
                new BigDecimal("21")));
        return document;
    }
}
