package com.tpverp.backend.cash;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CashDrawerController.class)
@Import(CashDrawerControllerContractTest.MethodSecurityConfiguration.class)
class CashDrawerControllerContractTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private CashDrawerService service;

    @Test
    void authenticatedOperatorCanSubmitDelegatedCredentialsForBackendAuthorization() throws Exception {
        var terminalId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        when(service.authorize(
                org.mockito.ArgumentMatchers.eq(terminalId),
                org.mockito.ArgumentMatchers.eq("encargado"),
                org.mockito.ArgumentMatchers.eq("1234"),
                any())).thenReturn(new CashDrawerService.AuthorizationView(
                        operationId, "ENCARGADO", true, Instant.parse("2026-07-24T12:02:00Z")));

        mvc.perform(post("/api/v1/pos/cash-drawer/open-authorizations")
                        .with(user("seller"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "terminalId": "%s",
                                  "authorizerUsername": "encargado",
                                  "authorizerPassword": "1234"
                                }
                                """.formatted(terminalId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId.toString()))
                .andExpect(jsonPath("$.authorizedBy").value("ENCARGADO"))
                .andExpect(jsonPath("$.delegated").value(true));
    }

    @Test
    void completionResultIsForwardedToTheAuthorizedOperation() throws Exception {
        var operationId = UUID.randomUUID();
        when(service.complete(
                org.mockito.ArgumentMatchers.eq(operationId),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("CASH_DRAWER_UNAVAILABLE"),
                org.mockito.ArgumentMatchers.eq("Cajon no configurado"),
                any())).thenReturn(new CashDrawerService.CompletionView(operationId, false));

        mvc.perform(post("/api/v1/pos/cash-drawer/open-authorizations/{operationId}/result", operationId)
                        .with(user("seller"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "opened": false,
                                  "errorCode": "CASH_DRAWER_UNAVAILABLE",
                                  "errorMessage": "Cajon no configurado"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.opened").value(false));

        verify(service).complete(
                org.mockito.ArgumentMatchers.eq(operationId),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.eq("CASH_DRAWER_UNAVAILABLE"),
                org.mockito.ArgumentMatchers.eq("Cajon no configurado"),
                any());
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
