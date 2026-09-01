package com.tpverp.backend.document;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.cash.CashPermissionService;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.SalesActivityJasperRenderer;
import com.tpverp.backend.excel.SalesActivityExcelExportService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SalesActivityReportController.class)
@Import(SalesActivityReportControllerWebMvcTest.MethodSecurityConfiguration.class)
class SalesActivityReportControllerWebMvcTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 16);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SalesActivityReportService reports;
    @MockitoBean
    private SalesActivityExcelExportService excel;
    @MockitoBean
    private SalesActivityJasperRenderer jasper;
    @MockitoBean
    private CashPermissionService cashPermissions;

    @Test
    void permitsAdminSalesAndAccountingRolesButRejectsAnonymousAndInvalidDate() throws Exception {
        var summary = summary();
        when(cashPermissions.canSeeExpectedTotals(any(Authentication.class))).thenAnswer(invocation -> {
            var authentication = invocation.getArgument(0, Authentication.class);
            return "ADMIN".equals(authentication.getName())
                    || authentication.getAuthorities().stream()
                    .anyMatch(value -> "GESTION_CUENTAS".equals(value.getAuthority()));
        });
        when(reports.daily(DATE, true)).thenReturn(summary);
        when(reports.daily(DATE, false)).thenReturn(summary);

        mvc.perform(get("/api/v1/sales-activity/daily")
                        .param("date", DATE.toString()).with(user("ADMIN").roles("ADMIN")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sales-activity/daily")
                        .param("date", DATE.toString())
                        .with(user("sales").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sales-activity/daily")
                        .param("date", DATE.toString())
                        .with(user("accounts").authorities(() -> "GESTION_CUENTAS")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sales-activity/daily")
                        .param("date", DATE.toString()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/sales-activity/daily")
                        .param("date", "not-a-date")
                        .with(user("sales").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isBadRequest());

        verify(reports, org.mockito.Mockito.times(2)).daily(DATE, true);
        verify(reports).daily(DATE, false);
    }

    @Test
    void usesRedactedSummaryForExcelAndRenderForSalesRole() throws Exception {
        var summary = summary();
        when(cashPermissions.canSeeExpectedTotals(any(Authentication.class))).thenReturn(false);
        when(reports.daily(DATE, false)).thenReturn(summary);
        when(excel.daily(summary)).thenReturn(new byte[] {1});
        when(jasper.renderDaily(summary, DocumentTemplateFormat.A4))
                .thenReturn(new SalesActivityJasperRenderer.RenderedReport(new byte[] {2}, null));

        mvc.perform(get("/api/v1/sales-activity/daily/excel")
                        .param("date", DATE.toString())
                        .with(user("sales").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/sales-activity/daily/render")
                        .param("date", DATE.toString())
                        .param("format", "A4")
                        .with(user("sales").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isOk());

        verify(reports, org.mockito.Mockito.times(2)).daily(DATE, false);
        verify(excel).daily(summary);
        verify(jasper).renderDaily(summary, DocumentTemplateFormat.A4);
    }

    @Test
    void defaultsDocumentExcelToDocumentGroupingWhenGroupingIsOmitted() throws Exception {
        when(excel.documents(DATE, DATE, SalesActivityPrintGrouping.DOCUMENT))
                .thenReturn(new byte[] {1});

        mvc.perform(get("/api/v1/sales-activity/documents/excel")
                        .param("dateFrom", DATE.toString())
                        .param("dateTo", DATE.toString())
                        .with(user("sales").authorities(() -> "GESTION_VENTAS")))
                .andExpect(status().isOk());

        verify(excel).documents(DATE, DATE, SalesActivityPrintGrouping.DOCUMENT);
    }

    private static SalesDailySummaryView summary() {
        return new SalesDailySummaryView(
                UUID.randomUUID(), "EMPRESA", "001", DATE, BigDecimal.ZERO, List.of(),
                new SalesDailySummaryView.ActivityCountsView(0, 0, 0, 0), List.of());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
