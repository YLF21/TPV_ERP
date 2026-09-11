package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WarehouseInputReportControllerTest {
    private final WarehouseInputReportService service = mock(WarehouseInputReportService.class);

    @Test
    void exposesBoundedTypedAndDatedReportWithoutChangingTheOperationalEndpoint() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WarehouseInputReportController(service)).build();
        mvc.perform(get("/api/v1/document-reports/warehouse-inputs")
                .param("type", "FACTURA_ENTRADA").param("limit", "25").param("cursor", "opaque")
                .param("dateFrom", "2026-08-01").param("dateTo", "2026-08-31"))
                .andExpect(status().isOk());
        verify(service).listPage(WarehouseInputDocumentType.FACTURA_ENTRADA, 25, "opaque",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null);
        var method = WarehouseInputReportController.class.getDeclaredMethod("list", WarehouseInputDocumentType.class,
                Integer.class, String.class, LocalDate.class, LocalDate.class, org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .contains("GESTION_PRODUCTO", "GESTION_ALMACEN", "GESTION_CUENTAS", "ADMIN")
                .doesNotContain("VENTA");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?type=FACTURA_VENTA", "?type=FACTURA_ENTRADA&dateFrom=2026-02-30",
            "?type=ALBARAN_ENTRADA&dateTo=invalid"})
    void rejectsMissingTypesAndInvalidDatesAtTheHttpBoundary(String query) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WarehouseInputReportController(service)).build();
        mvc.perform(get("/api/v1/document-reports/warehouse-inputs" + query)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void exposesTheJasperCopyAsAReadOnlySecuredRoute() throws Exception {
        var id = java.util.UUID.randomUUID();
        var mvc = MockMvcBuilders.standaloneSetup(new WarehouseInputReportController(service)).build();
        mvc.perform(get("/api/v1/document-reports/warehouse-inputs/" + id + "/print-document"))
                .andExpect(status().isOk());
        verify(service).printDocument(id, null);
        var method = WarehouseInputReportController.class.getDeclaredMethod("printDocument", java.util.UUID.class,
                org.springframework.security.core.Authentication.class);
        assertThat(method.getAnnotation(PreAuthorize.class).value()).contains("GESTION_CUENTAS", "GESTION_ALMACEN");
    }
}
