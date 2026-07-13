package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentTerminalOperationController.class)
@Import({PaymentTerminalOperationControllerContractTest.MethodSecurityConfiguration.class,
        PaymentTerminalReauthenticationService.class})
class PaymentTerminalOperationControllerContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean PaymentTerminalOperationsService service;
    @MockitoBean CurrentOrganization organization;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void adjustmentsUseSpecificPermissionsAndRealPasswordReauthentication() throws Exception {
        var originalId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var account = new UserAccount(null, "ADMIN", passwordEncoder.encode("1234"), new Role(null, "ADMIN"));
        when(organization.currentUser(any(Authentication.class))).thenReturn(account);
        when(service.voidAuthorization(originalId, operationId, "void-key"))
                .thenReturn(mock(PaymentTerminalOperation.class));

        var body = "{\"operationId\":\"%s\",\"idempotencyKey\":\"void-key\",\"password\":\"1234\"}"
                .formatted(operationId);
        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/void", originalId)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/void", originalId)
                        .with(user("cashier").authorities(() -> "GESTION_VENTAS")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/void", originalId)
                        .with(user("cashier").authorities(() -> "PAYMENT_TERMINAL_VOID")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingOrIncorrectReauthenticationAndHidesOtherStores() throws Exception {
        var originalId = UUID.randomUUID();
        var operationId = UUID.randomUUID();
        var account = new UserAccount(null, "ADMIN", passwordEncoder.encode("1234"), new Role(null, "ADMIN"));
        when(organization.currentUser(any(Authentication.class))).thenReturn(account);
        var permitted = user("cashier").authorities(() -> "PAYMENT_TERMINAL_REFUND");
        var template = "{\"operationId\":\"%s\",\"idempotencyKey\":\"refund-key\",%s\"amount\":1.00}";

        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/refund", originalId)
                        .with(permitted).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(operationId, "")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/refund", originalId)
                        .with(permitted).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(operationId, "\"password\":\"wrong\",")))
                .andExpect(status().isUnauthorized());

        when(service.refund(any(), any(), any(), any(), any())).thenThrow(new PaymentTerminalApiException(
                org.springframework.http.HttpStatus.NOT_FOUND, "PAYMENT_OPERATION_NOT_FOUND", "Operacion no encontrada"));
        mvc.perform(post("/api/v1/payment-terminal/operations/{id}/refund", originalId)
                        .with(permitted).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(operationId, "\"password\":\"1234\",")))
                .andExpect(status().isNotFound());
    }

    @Test
    void operationsRequireAuthenticationAndReadPermission() throws Exception {
        var id=UUID.randomUUID();
        mvc.perform(get("/api/v1/payment-terminal/operations/{id}",id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/payment-terminal/operations/{id}",id)
                        .with(user("cashier").authorities(() -> "PAYMENT_TERMINAL_RECONCILE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciliationPostAndGetRequireReconciliationPermission() throws Exception {
        var id=UUID.randomUUID();
        mvc.perform(post("/api/v1/payment-terminal/terminals/{id}/reconciliations",id)
                        .with(user("reader").authorities(() -> "TICKETS_READ")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reconciliationId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment-terminal/reconciliations/{id}",id)
                        .with(user("reader").authorities(() -> "TICKETS_READ")))
                .andExpect(status().isForbidden());
    }
    @Test
    void exposesProtectedStatusAdjustmentReceiptHistoryAndReconciliationContracts() throws Exception {
        assertThat(PaymentTerminalOperationController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/payment-terminal");
        assertProtected("status", new Class<?>[]{UUID.class}, GetMapping.class, "TICKETS_READ");
        assertProtected("query", new Class<?>[]{UUID.class}, PostMapping.class, "TICKETS_READ");
        assertProtected("voidAuthorization", new Class<?>[]{UUID.class,
                PaymentTerminalOperationController.AdjustmentRequest.class, Authentication.class}, PostMapping.class,
                "PAYMENT_TERMINAL_VOID");
        assertProtected("refund", new Class<?>[]{UUID.class,
                PaymentTerminalOperationController.RefundRequest.class, Authentication.class}, PostMapping.class,
                "PAYMENT_TERMINAL_REFUND");
        assertProtected("receipt", new Class<?>[]{UUID.class}, GetMapping.class, "TICKETS_READ");
        assertProtected("events", new Class<?>[]{UUID.class}, GetMapping.class, "TICKETS_READ");
        assertProtected("reconcile", new Class<?>[]{UUID.class,
                PaymentTerminalOperationController.ReconciliationRequest.class}, PostMapping.class,
                "PAYMENT_TERMINAL_RECONCILE");
        assertProtected("reconciliation", new Class<?>[]{UUID.class}, GetMapping.class,
                "PAYMENT_TERMINAL_RECONCILE");
    }

    @Test
    void refundRequestRejectsNonPositiveAmountsByContract() throws Exception {
        var annotation = PaymentTerminalOperationController.RefundRequest.class
                .getDeclaredMethod("amount").getAnnotation(jakarta.validation.constraints.DecimalMin.class);
        assertThat(annotation.value()).isEqualTo("0.01");
        assertThat(annotation.inclusive()).isTrue();
        assertThat(new PaymentTerminalOperationController.RefundRequest(UUID.randomUUID(), "key",
                "1234",
                new BigDecimal("1.00")).amount()).isEqualByComparingTo("1.00");
    }

    private static void assertProtected(String name, Class<?>[] parameters,
            Class<? extends java.lang.annotation.Annotation> mapping, String permission) throws Exception {
        Method method = PaymentTerminalOperationController.class.getDeclaredMethod(name, parameters);
        assertThat(method.getAnnotation(mapping)).isNotNull();
        assertThat(method.getAnnotation(PreAuthorize.class).value()).contains(permission);
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
        @Bean PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }
}
