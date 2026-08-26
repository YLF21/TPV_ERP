package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FiscalHistoryReadController.class)
@Import(FiscalHistoryCursorControllerAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class FiscalHistoryCursorControllerAuthorizationWebMvcTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private FiscalHistoryReadService history;

    @Test
    void readerCanUseBothKeysetEndpointsWithoutTotals() throws Exception {
        when(history.exportsCursor(null, null)).thenReturn(
                new FiscalHistoryReadCursorPage<FiscalExportHistoryView>(
                        List.of(), 100, null, null, false, false));
        when(history.requiredSubmissionsCursor(null, null)).thenReturn(
                new FiscalHistoryReadCursorPage<FiscalRequiredSubmissionHistoryView>(
                        List.of(), 100, null, null, false, false));

        mvc.perform(get("/api/v1/fiscal/exports/cursor").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
        mvc.perform(get("/api/v1/fiscal/required-submissions/cursor").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void incompletePermissionIsRejectedForCursorHistory() throws Exception {
        mvc.perform(get("/api/v1/fiscal/exports/cursor")
                        .with(user("reader").authorities(new SimpleGrantedAuthority("VERIFACTU_READ"))))
                .andExpect(status().isForbidden());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor reader() {
        return user("reader").authorities(Arrays.asList(
                new SimpleGrantedAuthority("APP_GESTION_ACCESS"),
                new SimpleGrantedAuthority("VERIFACTU_READ")));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
