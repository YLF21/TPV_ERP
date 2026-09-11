package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DocumentSyncRecoveryController.class, properties = "tpv.sync.document-recovery-enabled=true")
@ActiveProfiles("dev")
@Import(DocumentSyncRecoveryControllerTest.MethodSecurity.class)
class DocumentSyncRecoveryControllerTest {
    private static final String SCOPE = """
            {"companyId":"00000000-0000-0000-0000-000000000001","storeId":"00000000-0000-0000-0000-000000000002",
             "dateFrom":"2025-01-01","dateTo":"2025-12-31","createdBefore":"2026-09-10T12:00:00Z"}
            """;
    private static final String DOC = "00000000-0000-0000-0000-000000000003";
    @Autowired MockMvc mvc;
    @MockitoBean DocumentSyncRecoveryService service;

    @Test void adminCanPreviewAndTheResponseIsNotCacheable() throws Exception {
        Scope scope = new Scope(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 12, 31), Instant.parse("2026-09-10T12:00:00.123456Z"));
        when(service.preview(any(), any())).thenReturn(new Preview(scope,
                List.of(new Row(UUID.fromString(DOC), "T-TEST", "TICKET", "PAGADO", LocalDate.of(2025, 2, 1),
                        "9007199254740993.01", "EUR", null)), null, false));
        mvc.perform(post("/api/v1/sync/document-recovery/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"scope\":" + SCOPE + "}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.scope.createdBefore").value("2026-09-10T12:00:00.123456Z"))
                .andExpect(jsonPath("$.scope.dateFrom").value("2025-01-01"))
                .andExpect(jsonPath("$.documents[0].total").value("9007199254740993.01"));
        verify(service).preview(any(), any());
    }
    @Test void preparationSerializesExactRevisionAsTextForPowerShellCheckpoints() throws Exception {
        when(service.prepare(any(), any())).thenReturn(new Prepared(null, List.of(new Receipt(UUID.fromString(DOC),
                UUID.fromString("00000000-0000-0000-0000-000000000004"), "9007199254740993", "PENDIENTE")), "ENQUEUED"));
        mvc.perform(post("/api/v1/sync/document-recovery/prepare").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(valid("prepare")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documents[0].sourceRevision").value("9007199254740993"));
    }
    @ParameterizedTest @ValueSource(strings = {"preview", "prepare", "verify"})
    void ordinaryUsersCannotInvokeTechnicalOperations(String action) throws Exception {
        mvc.perform(post("/api/v1/sync/document-recovery/" + action).with(user("cashier").roles("USER")).with(csrf())
                .contentType("application/json").content(valid(action))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void invalidScopeAndMissingReasonAreRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/v1/sync/document-recovery/preview").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"scope\":{}}")) .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/sync/document-recovery/prepare").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"scope\":" + SCOPE + ",\"documentIds\":[\"" + DOC + "\"]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void safePreflightErrorsHaveStableCodes() throws Exception {
        when(service.prepare(any(), any())).thenThrow(DocumentSyncRecoveryException.unavailable());
        mvc.perform(post("/api/v1/sync/document-recovery/prepare").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content(valid("prepare"))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DOCUMENT_RECOVERY_SAAS_UNAVAILABLE"));
    }
    @Test void endpointRequiresBothDevAndExplicitEnablement() {
        var base = new ApplicationContextRunner().withUserConfiguration(DocumentSyncRecoveryController.class)
                .withBean(DocumentSyncRecoveryService.class, () -> mock(DocumentSyncRecoveryService.class));
        base.run(context -> assertThat(context).doesNotHaveBean(DocumentSyncRecoveryController.class));
        base.withPropertyValues("tpv.sync.document-recovery-enabled=true")
                .run(context -> assertThat(context).doesNotHaveBean(DocumentSyncRecoveryController.class));
        base.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).doesNotHaveBean(DocumentSyncRecoveryController.class));
        base.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("tpv.sync.document-recovery-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DocumentSyncRecoveryController.class));
    }
    private static String valid(String action) {
        return switch (action) {
            case "preview" -> "{\"scope\":" + SCOPE + "}";
            case "prepare" -> "{\"scope\":" + SCOPE + ",\"documentIds\":[\"" + DOC + "\"],\"reason\":\"Prueba puntual\"}";
            default -> "{\"scope\":" + SCOPE + ",\"documents\":[{\"documentId\":\"" + DOC
                    + "\",\"eventId\":\"00000000-0000-0000-0000-000000000004\",\"sourceRevision\":\"1\"}]}";
        };
    }
    @EnableMethodSecurity static class MethodSecurity { }
}
