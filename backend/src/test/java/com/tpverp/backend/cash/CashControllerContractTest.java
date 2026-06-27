package com.tpverp.backend.cash;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_CONFIGURE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_OPERATE;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.CASH_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_CUENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@WebMvcTest(CashController.class)
@Import(CashControllerContractTest.MethodSecurityConfiguration.class)
class CashControllerContractTest {

    private static final UUID TERMINAL_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID MOVEMENT_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CashSessionService sessions;

    @MockitoBean
    private CashReceiptService receipts;

    @MockitoBean
    private CashReportService reports;

    @Test
    void exposesCashEndpointsWithCashPermissions() throws Exception {
        RequestMapping root = CashController.class.getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly("/api/v1/cash");

        assertEndpoint("status", GetMapping.class, new String[] {"/status"},
                "GESTION_VENTAS", "CASH_OPERATE", "GESTION_CUENTAS", "CASH_READ");
        assertEndpoint("open", PostMapping.class, new String[] {"/sessions/open"},
                "GESTION_VENTAS", "CASH_OPERATE");
        assertEndpoint("close", PostMapping.class, new String[] {"/sessions/close"},
                "GESTION_VENTAS", "CASH_OPERATE");
        assertEndpoint("entry", PostMapping.class, new String[] {"/movements/entry"},
                "GESTION_VENTAS", "CASH_OPERATE");
        assertEndpoint("withdrawal", PostMapping.class, new String[] {"/movements/withdrawal"},
                "GESTION_VENTAS", "CASH_OPERATE");
        assertEndpoint("betweenSessions", PostMapping.class, new String[] {"/movements/between-sessions"},
                "GESTION_CUENTAS", "CASH_CONFIGURE");
        assertEndpoint("withdrawalReceipt", GetMapping.class, new String[] {"/receipts/withdrawals/{movementId}"},
                "GESTION_VENTAS", "CASH_OPERATE", "GESTION_CUENTAS", "CASH_READ");
        assertEndpoint("sessionReceipt", GetMapping.class, new String[] {"/receipts/sessions/{sessionId}"},
                "GESTION_VENTAS", "CASH_OPERATE", "GESTION_CUENTAS", "CASH_READ");
        assertEndpoint("report", GetMapping.class, new String[] {"/reports"},
                "GESTION_CUENTAS", "CASH_READ");
        assertEndpoint("config", GetMapping.class, new String[] {"/config"},
                "GESTION_CUENTAS", "CASH_CONFIGURE");
        assertEndpoint("updateConfig", PutMapping.class, new String[] {"/config"},
                "GESTION_CUENTAS", "CASH_CONFIGURE");
    }

    @Test
    void sellerStatusDoesNotExposeExpectedTotals() throws Exception {
        when(sessions.status(any(), any())).thenReturn(new CashSessionView(
                SESSION_ID, TERMINAL_ID, CashSessionStatus.ABIERTA, Instant.parse("2026-06-25T09:00:00Z"),
                new BigDecimal("40.00"), null, null, null, null, null, null, false));

        mvc.perform(get("/api/v1/cash/status")
                        .param("terminalId", TERMINAL_ID.toString())
                        .with(user("seller").authorities(() -> GESTION_VENTAS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openingFund").value(40.00))
                .andExpect(jsonPath("$.expectedCash").doesNotExist())
                .andExpect(jsonPath("$.availableCash").doesNotExist());
    }

    @Test
    void accountingUserCanReadDailyReport() throws Exception {
        when(reports.report(any(), any(), any(), any(), any())).thenReturn(new CashReportView(
                TERMINAL_ID, STORE_ID, Instant.parse("2026-06-25T00:00:00Z"),
                Instant.parse("2026-06-26T00:00:00Z"),
                Map.of(CashMovementType.COBRO_EFECTIVO, new BigDecimal("120.00")),
                new BigDecimal("80.00"), new BigDecimal("-2.50")));

        mvc.perform(get("/api/v1/cash/reports")
                        .param("terminalId", TERMINAL_ID.toString())
                        .param("storeId", STORE_ID.toString())
                        .param("from", "2026-06-25T00:00:00Z")
                        .param("to", "2026-06-26T00:00:00Z")
                        .with(user("accounting").authorities(() -> GESTION_CUENTAS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalsByType.COBRO_EFECTIVO").value(120.00))
                .andExpect(jsonPath("$.retainedFunds").value(80.00))
                .andExpect(jsonPath("$.discrepancies").value(-2.50));
    }

    @Test
    void sellerCannotReadReports() throws Exception {
        mvc.perform(get("/api/v1/cash/reports")
                        .param("from", "2026-06-25T00:00:00Z")
                        .param("to", "2026-06-26T00:00:00Z")
                        .with(user("seller").authorities(() -> GESTION_VENTAS)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanUpdateCashConfig() throws Exception {
        when(reports.updateConfig(any(), any())).thenReturn(new CashStoreConfigView(
                STORE_ID, new BigDecimal("1.50"), true, false, true));

        mvc.perform(put("/api/v1/cash/config")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "discrepancyTolerance": 1.50,
                                  "requireEntryBreakdown": true,
                                  "requireWithdrawalBreakdown": false,
                                  "requireClosingBreakdown": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discrepancyTolerance").value(1.50))
                .andExpect(jsonPath("$.requireEntryBreakdown").value(true))
                .andExpect(jsonPath("$.requireWithdrawalBreakdown").value(false))
                .andExpect(jsonPath("$.requireClosingBreakdown").value(true));
    }

    @Test
    void omittedCashConfigBooleanBindsAsNullForValidation() throws Exception {
        when(reports.updateConfig(any(), any())).thenReturn(new CashStoreConfigView(
                STORE_ID, new BigDecimal("1.50"), true, false, true));

        mvc.perform(put("/api/v1/cash/config")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "discrepancyTolerance": 1.50,
                                  "requireEntryBreakdown": true,
                                  "requireClosingBreakdown": true
                                }
                                """))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(CashStoreConfigRequest.class);
        verify(reports).updateConfig(captor.capture(), any());
        assertThat(captor.getValue().requireWithdrawalBreakdown()).isNull();
    }

    @Test
    void withdrawalReceiptDelegatesAuthenticationAndMovementId() throws Exception {
        when(receipts.withdrawalReceipt(any(), any())).thenReturn(new CashReceiptView(
                MOVEMENT_ID, SESSION_ID, TERMINAL_ID, "TPV 1",
                Instant.parse("2026-06-25T09:30:00Z"), "SELLER",
                new BigDecimal("20.00"), List.of(), null, null, null, "", ""));

        mvc.perform(get("/api/v1/cash/receipts/withdrawals/{movementId}", MOVEMENT_ID)
                        .with(user("seller").authorities(() -> CASH_READ)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementId").value(MOVEMENT_ID.toString()));

        verify(receipts).withdrawalReceipt(eq(MOVEMENT_ID), any(Authentication.class));
    }

    @Test
    void closeDelegatesAuthenticationAndTerminalFromRequestBody() throws Exception {
        when(sessions.close(any(), any(), any())).thenReturn(new CashSessionView(
                SESSION_ID, TERMINAL_ID, CashSessionStatus.CERRADA, Instant.parse("2026-06-25T09:00:00Z"),
                new BigDecimal("40.00"), null, null, new BigDecimal("40.00"),
                BigDecimal.ZERO, Instant.parse("2026-06-25T18:00:00Z"), 1, true));

        mvc.perform(post("/api/v1/cash/sessions/close")
                        .with(user("seller").authorities(() -> CASH_OPERATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "terminalId": "%s",
                                  "retainedFund": 40.00,
                                  "retainedFundDenominations": [],
                                  "finalWithdrawalAmount": 0,
                                  "finalWithdrawalDenominations": []
                                }
                                """.formatted(TERMINAL_ID)))
                .andExpect(status().isOk());

        verify(sessions).close(any(), any(CashCloseRequest.class), any());
    }

    @Test
    void openDelegatesAuthenticationAndTerminalFromRequestBody() throws Exception {
        when(sessions.open(any(), any())).thenReturn(new CashSessionView(
                SESSION_ID, TERMINAL_ID, CashSessionStatus.ABIERTA, Instant.parse("2026-06-25T09:00:00Z"),
                new BigDecimal("40.00"), null, null, null, null, null, null, false));

        mvc.perform(post("/api/v1/cash/sessions/open")
                        .with(user("seller").authorities(() -> CASH_OPERATE))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"terminalId": "%s"}
                                """.formatted(TERMINAL_ID)))
                .andExpect(status().isOk());

        verify(sessions).open(any(), any());
    }

    private void assertEndpoint(
            String methodName,
            Class<? extends Annotation> mappingType,
            String[] paths,
            String... permissions)
            throws Exception {
        Method method = List.of(CashController.class.getDeclaredMethods()).stream()
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).contains("hasRole('ADMIN')");
        for (String permission : permissions) {
            assertThat(authorization.value()).contains(permission);
        }
        assertThat(mappingPaths(method, mappingType)).containsExactly(paths);
    }

    private String[] mappingPaths(Method method, Class<? extends Annotation> mappingType) throws Exception {
        Annotation mapping = method.getAnnotation(mappingType);
        assertThat(mapping).isNotNull();
        return (String[]) mappingType.getMethod("value").invoke(mapping);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
