package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.cash.CashPermissionService;
import com.tpverp.backend.document.template.SalesActivityJasperRenderer;
import com.tpverp.backend.excel.SalesActivityExcelExportService;
import java.time.LocalDate;
import org.springframework.security.core.Authentication;
import org.junit.jupiter.api.Test;

class SalesActivityReportControllerTest {

    @Test
    void delegatesDailyDocumentAggregationWithDateRangeAndCursor() {
        var reports = mock(SalesActivityReportService.class);
        var excel = mock(SalesActivityExcelExportService.class);
        var jasper = mock(SalesActivityJasperRenderer.class);
        var expected = mock(SalesActivityDailyDocumentPageView.class);
        var from = LocalDate.of(2026, 8, 1);
        var to = LocalDate.of(2026, 8, 31);
        when(reports.dailyDocuments(from, to, 100, "2026-08-15"))
                .thenReturn(expected);

        var result = new SalesActivityReportController(reports, excel, jasper)
                .dailyDocuments(from, to, 100, "2026-08-15");

        assertThat(result).isSameAs(expected);
        verify(reports).dailyDocuments(from, to, 100, "2026-08-15");
    }

    @Test
    void acceptsAnAbsentLimitAndCursor() {
        var reports = mock(SalesActivityReportService.class);
        var excel = mock(SalesActivityExcelExportService.class);
        var jasper = mock(SalesActivityJasperRenderer.class);
        var expected = mock(SalesActivityDailyDocumentPageView.class);
        var from = LocalDate.of(2026, 8, 1);
        var to = LocalDate.of(2026, 8, 31);
        when(reports.dailyDocuments(from, to, null, null)).thenReturn(expected);

        assertThat(new SalesActivityReportController(reports, excel, jasper)
                .dailyDocuments(from, to, null, null)).isSameAs(expected);
        verify(reports).dailyDocuments(eq(from), eq(to), isNull(), isNull());
    }

    @Test
    void delegatesCashVisibilityToTheBackendPermissionServiceAndFailsClosed() {
        var reports = mock(SalesActivityReportService.class);
        var excel = mock(SalesActivityExcelExportService.class);
        var jasper = mock(SalesActivityJasperRenderer.class);
        var permissions = mock(CashPermissionService.class);
        var authentication = mock(Authentication.class);
        var expected = mock(SalesDailySummaryView.class);
        var date = LocalDate.of(2026, 8, 16);
        when(permissions.canSeeExpectedTotals(authentication)).thenReturn(true);
        when(reports.daily(date, true)).thenReturn(expected);

        var secured = new SalesActivityReportController(reports, excel, jasper, permissions);
        assertThat(secured.daily(date, authentication)).isSameAs(expected);
        verify(reports).daily(date, true);

        var failClosed = new SalesActivityReportController(reports, excel, jasper);
        when(reports.daily(date, false)).thenReturn(expected);
        assertThat(failClosed.daily(date, authentication)).isSameAs(expected);
        verify(reports).daily(date, false);
    }
}
