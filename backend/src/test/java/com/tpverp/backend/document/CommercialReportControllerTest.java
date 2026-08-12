package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tpverp.backend.cash.CashPermissionService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CommercialReportControllerTest {

    @Test
    void delegatesSensitiveCashVisibilityToTheBackendPermissionPolicy() {
        var reports = mock(DailyCommercialReportService.class);
        var cashPermissions = mock(CashPermissionService.class);
        var authentication = mock(Authentication.class);
        var expected = mock(DailyCommercialReportView.class);
        var date = LocalDate.of(2026, 8, 11);
        when(cashPermissions.canSeeExpectedTotals(authentication)).thenReturn(false);
        when(reports.report(date, date, false)).thenReturn(expected);

        var result = new CommercialReportController(reports, cashPermissions)
                .daily(date, null, null, authentication);

        assertThat(result).isSameAs(expected);
        verify(cashPermissions).canSeeExpectedTotals(authentication);
        verify(reports).report(date, date, false);
    }

    @Test
    void acceptsIsoDateRangeFromTheSalesReportScreen() throws Exception {
        var reports = mock(DailyCommercialReportService.class);
        var cashPermissions = mock(CashPermissionService.class);
        var date = LocalDate.of(2026, 8, 11);
        when(reports.report(date, date, false)).thenReturn(mock(DailyCommercialReportView.class));
        var mvc = MockMvcBuilders
                .standaloneSetup(new CommercialReportController(reports, cashPermissions))
                .build();

        mvc.perform(get("/api/v1/commercial-reports/daily")
                        .param("dateFrom", "2026-08-11")
                        .param("dateTo", "2026-08-11"))
                .andExpect(status().isOk());

        verify(reports).report(date, date, false);
    }
}
