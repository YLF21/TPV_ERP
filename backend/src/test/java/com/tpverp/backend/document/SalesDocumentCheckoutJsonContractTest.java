package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SalesDocumentCheckoutJsonContractTest {

    @ParameterizedTest
    @CsvSource({
            "DRAFT,OMITTED,false",
            "CONFIRM_AND_PAY,OMITTED,false",
            "DRAFT,false,false",
            "CONFIRM_AND_PAY,false,false",
            "DRAFT,true,true",
            "CONFIRM_AND_PAY,true,true",
            "DRAFT,null,false",
            "CONFIRM_AND_PAY,null,false"
    })
    void acceptsFrontendQuotePayload(String completionMode, String wholesaleJson,
            boolean expectedWholesaleMode)
            throws Exception {
        var service = mock(CustomerPendingSaleService.class);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "operator", "unused", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        when(service.quote(any(), eq(authentication)))
                .thenReturn(new CustomerPendingSaleService.Quote(new BigDecimal("12.34"), null));
        var mvc = MockMvcBuilders.standaloneSetup(new SalesDocumentCheckoutController(
                service, mock(CustomerReceivablePrintService.class), mock(DocumentViewAssembler.class)))
                .build();
        var payload = """
                {
                  "checkoutId": "10000000-0000-4000-8000-000000000001",
                  "warehouseId": "10000000-0000-4000-8000-000000000002",
                  "type": "FACTURA_VENTA",
                  "date": "2026-09-18",
                  "customerId": "10000000-0000-4000-8000-000000000003",
                  "dueDate": "2026-10-18",
                  "globalDiscount": "0.00",
                  "documentDiscountPercent": "0.00",
                  "completionMode": "%s",
                  %s
                  "lines": [{
                    "productoId": "10000000-0000-4000-8000-000000000004",
                    "cantidad": 1,
                    "codigo": "TEST-001",
                    "nombre": "Producto de prueba",
                    "tarifa": null,
                    "precioUnitario": "12.34",
                    "descuento": "0.00",
                    "impuestosIncluidos": true,
                    "regimenImpuesto": "IVA",
                    "porcentajeImpuesto": "21.00",
                    "lineType": "PRODUCT",
                    "promotionId": null,
                    "promotionVersionId": null,
                    "promotionalCouponId": null,
                    "temporaryNameOverride": false,
                    "temporaryPriceOverride": false,
                    "cartLineId": "line-1"
                  }],
                  "payments": [],
                  "quotedTotal": "0.00"
                }
                """.formatted(completionMode,
                        wholesaleJson.equals("OMITTED") ? "" : "\"wholesaleMode\": " + wholesaleJson + ",");

        mvc.perform(post("/api/v1/pos/sales-document-checkouts/quote")
                        .principal(authentication)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk());

        var captured = ArgumentCaptor.forClass(CustomerPendingSaleController.CreateRequest.class);
        verify(service).quote(captured.capture(), eq(authentication));
        assertThat(captured.getValue().completionMode().name()).isEqualTo(completionMode);
        assertThat(captured.getValue().wholesaleMode()).isEqualTo(expectedWholesaleMode);
        assertThat(captured.getValue().documentDiscountPercent()).isEqualByComparingTo("0");
        assertThat(captured.getValue().lines().getFirst().porcentajeImpuesto())
                .isEqualByComparingTo("21");
    }
}
