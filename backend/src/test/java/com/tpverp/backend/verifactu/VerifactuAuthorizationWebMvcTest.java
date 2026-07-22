package com.tpverp.backend.verifactu;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({
        VerifactuAdminController.class,
        VerifactuAdminReadController.class,
        VerifactuAdminActionController.class,
        DefectiveFiscalRecordController.class})
@Import(VerifactuAuthorizationWebMvcTest.MethodSecurityConfiguration.class)
class VerifactuAuthorizationWebMvcTest {

    private static final String ADMIN_QUEUE = "/api/v1/verifactu/admin/queue";
    private static final String ADMIN_STATUS = "/api/v1/verifactu/admin/status";
    private static final String ADMIN_RETRY_NEXT = "/api/v1/verifactu/admin/retry-next";
    private static final String ADMIN_SUMMARY = "/api/v1/verifactu/admin/summary";
    private static final String ADMIN_SUBMISSIONS = "/api/v1/verifactu/admin/submissions";
    private static final String ADMIN_DEFECTIVE_RECORDS = "/api/v1/verifactu/admin/defective-records";
    private static final String ADMIN_DIAGNOSTICS = "/api/v1/verifactu/admin/diagnostics";
    private static final String DEFECTIVE_RECORDS = "/api/v1/verifactu/defective-records";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private VerifactuAdminService adminService;

    @MockitoBean
    private VerifactuAdminReadService adminReadService;

    @MockitoBean
    private VerifactuAdminReviewReadService adminReviewReadService;

    @MockitoBean
    private VerifactuResolutionPolicyService resolutionPolicy;

    @MockitoBean
    private VerifactuManualRetryService manualRetries;

    @MockitoBean
    private DefectiveFiscalRecordService defectiveRecords;

    @MockitoBean
    private FiscalSubmissionAttemptService attempts;

    @MockitoBean
    private FiscalCorrectionService corrections;

    @Test
    void adminCanUseReadSensitiveRetryAttemptAndCorrectionEndpoints() throws Exception {
        UUID recordId = UUID.randomUUID();
        when(adminService.queue()).thenReturn(List.of());
        when(adminService.attempts(recordId)).thenReturn(List.of());
        when(adminService.retryNext()).thenReturn(VerifactuWorkerResult.empty());
        when(defectiveRecords.list()).thenReturn(List.of());
        when(attempts.history(recordId)).thenReturn(List.of());

        mvc.perform(get(ADMIN_STATUS).with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_QUEUE).with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/verifactu/admin/records/{recordId}/attempts", recordId)
                        .with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get(DEFECTIVE_RECORDS).with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get(DEFECTIVE_RECORDS + "/{recordId}/attempts", recordId)
                        .with(admin()))
                .andExpect(status().isOk());
        mvc.perform(post(DEFECTIVE_RECORDS + "/{recordId}/corrections", recordId)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isOk());
        mvc.perform(post(ADMIN_RETRY_NEXT).with(admin()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void gestionFiscalReaderCanReadQueueClockAndDefectiveRecords() throws Exception {
        when(adminService.queue()).thenReturn(List.of());
        when(defectiveRecords.list()).thenReturn(List.of());
        var reader = permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");

        mvc.perform(get(ADMIN_QUEUE).with(reader))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/verifactu/admin/clock")
                        .with(permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ")))
                .andExpect(status().isOk());
        mvc.perform(get(DEFECTIVE_RECORDS)
                        .with(permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ")))
                .andExpect(status().isOk());
    }

    @Test
    void gestionFiscalReaderCanReadSanitizedSummaryAndPaginatedSubmissions() throws Exception {
        var reader = permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");

        mvc.perform(get(ADMIN_SUMMARY).with(reader))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_SUBMISSIONS).with(reader))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_SUMMARY).with(admin()))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_DEFECTIVE_RECORDS).with(reader))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_DIAGNOSTICS).with(reader))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_SUBMISSIONS + "/{recordId}/attempts", UUID.randomUUID())
                        .with(reader))
                .andExpect(status().isOk());
        mvc.perform(get(ADMIN_SUBMISSIONS + "/{recordId}/resolution", UUID.randomUUID())
                        .with(reader))
                .andExpect(status().isOk());
    }

    @Test
    void fiscalManagerCanRetryOneScopedRecordButReaderCannot() throws Exception {
        var recordId = UUID.randomUUID();
        when(manualRetries.retry(
                org.mockito.ArgumentMatchers.eq(recordId),
                org.mockito.ArgumentMatchers.any(VerifactuManualRetryRequest.class)))
                .thenReturn(new VerifactuManualRetryView(
                        recordId, FiscalSubmissionStatus.ACEPTADO, null));

        mvc.perform(post(ADMIN_SUBMISSIONS + "/{recordId}/retry", recordId)
                        .with(fiscalManager())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualRetryBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value(recordId.toString()))
                .andExpect(jsonPath("$.status").value("ACEPTADO"))
                .andExpect(jsonPath("$.error").doesNotExist());
        mvc.perform(post(ADMIN_SUBMISSIONS + "/{recordId}/retry", recordId)
                        .with(fiscalReader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualRetryBody()))
                .andExpect(status().isForbidden());
        mvc.perform(post(ADMIN_SUBMISSIONS + "/{recordId}/retry", recordId)
                        .with(permissions(
                                "manager", "VERIFACTU_READ", "VERIFACTU_MANAGE"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(manualRetryBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualRetryValidatesReasonAndExpectedVersion() throws Exception {
        mvc.perform(post(ADMIN_SUBMISSIONS + "/{recordId}/retry", UUID.randomUUID())
                        .with(fiscalManager())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\",\"expectedVersion\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sanitizedReadsRejectIncompleteOrCommercialPermissions() throws Exception {
        mvc.perform(get(ADMIN_SUMMARY)
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_SUBMISSIONS)
                        .with(permissions("app-user", "APP_GESTION_ACCESS")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_SUBMISSIONS)
                        .with(permissions("sales", "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_DEFECTIVE_RECORDS)
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_DIAGNOSTICS)
                        .with(permissions("sales", "GESTION_VENTAS")))
                .andExpect(status().isForbidden());
    }

    @Test
    void sanitizedReadJsonOmitsCertificateIdentityPayloadAndTechnicalError() throws Exception {
        var recordId = UUID.randomUUID();
        when(adminReadService.summary()).thenReturn(new VerifactuAdminSummaryView(
                true,
                "VOLUNTARY",
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                VerifactuEndpointMode.TEST,
                true,
                Map.of(FiscalSubmissionStatus.PENDIENTE, 1L),
                Instant.parse("2026-07-21T10:00:00Z"),
                new VerifactuAdminCertificateSummary(true, true, null,
                        Instant.parse("2027-07-01T00:00:00Z")),
                new VerifactuAdminClockSummary(true, false, null, 1L, 30L,
                        Instant.parse("2026-07-21T09:00:00Z"))));
        when(adminReadService.submissions(
                null, null, null, null, null, null, 0, 25))
                .thenReturn(new VerifactuAdminSubmissionPage(
                        List.of(new VerifactuAdminSubmissionView(
                                recordId, 1, "T-1", FiscalDocumentType.F2,
                                FiscalRecordOperation.ALTA, FiscalSubmissionStatus.RECHAZADO,
                                Instant.parse("2026-07-21T10:00:00Z"), "AEAT-1")),
                        0, 25, 1, 1));
        when(adminReviewReadService.defectiveRecords(
                null, null, null, null, null, null, 0, 25))
                .thenReturn(new VerifactuAdminDefectiveRecordPage(
                        List.of(new VerifactuAdminDefectiveRecordView(
                                recordId, 1, "T-1", FiscalDocumentType.F2,
                                FiscalRecordOperation.ALTA, java.time.LocalDate.of(2026, 7, 21),
                                FiscalSubmissionStatus.RECHAZADO,
                                Instant.parse("2026-07-21T10:00:00Z"), "AEAT-1")),
                        0, 25, 1, 1));
        when(adminReviewReadService.attempts(recordId, 0, 25))
                .thenReturn(new VerifactuAdminAttemptPage(
                        List.of(new VerifactuAdminAttemptView(
                                UUID.randomUUID(), Instant.parse("2026-07-21T10:00:00Z"),
                                FiscalSubmissionStatus.RECHAZADO, "AEAT-1", true)),
                        0, 25, 1, 1));
        when(resolutionPolicy.resolution(
                org.mockito.ArgumentMatchers.eq(recordId),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new VerifactuResolutionView(
                        recordId,
                        FiscalRecordOperation.ALTA,
                        FiscalSubmissionStatus.RECHAZADO,
                        3,
                        "AEAT-1",
                        VerifactuResolutionCategory.AEAT_REJECTED,
                        VerifactuResolutionAction.CREATE_CORRECTION,
                        List.of()));

        mvc.perform(get(ADMIN_SUMMARY).with(fiscalReader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate.configured").value(true))
                .andExpect(jsonPath("$.certificate.subject").doesNotExist())
                .andExpect(jsonPath("$.certificate.issuer").doesNotExist())
                .andExpect(jsonPath("$.certificate.taxId").doesNotExist())
                .andExpect(content().string(not(containsString("fingerprint"))));
        mvc.perform(get(ADMIN_SUBMISSIONS).with(fiscalReader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].errorCode").value("AEAT-1"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist())
                .andExpect(content().string(not(containsString("responsePayload"))))
                .andExpect(content().string(not(containsString("snapshot"))));
        mvc.perform(get(ADMIN_DEFECTIVE_RECORDS).with(fiscalReader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].errorCode").value("AEAT-1"))
                .andExpect(jsonPath("$.items[0].error").doesNotExist())
                .andExpect(jsonPath("$.items[0].totalAmount").doesNotExist())
                .andExpect(jsonPath("$.items[0].qrUrl").doesNotExist());
        mvc.perform(get(ADMIN_SUBMISSIONS + "/{recordId}/attempts", recordId)
                        .with(fiscalReader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].errorCode").value("AEAT-1"))
                .andExpect(jsonPath("$.items[0].hasTechnicalDetail").value(true))
                .andExpect(jsonPath("$.items[0].error").doesNotExist())
                .andExpect(content().string(not(containsString("requestXml"))))
                .andExpect(content().string(not(containsString("responsePayload"))));
        mvc.perform(get(ADMIN_SUBMISSIONS + "/{recordId}/resolution", recordId)
                        .with(fiscalReader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("AEAT_REJECTED"))
                .andExpect(jsonPath("$.recommendedAction").value("CREATE_CORRECTION"))
                .andExpect(jsonPath("$.errorCode").value("AEAT-1"))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(content().string(not(containsString("snapshot"))))
                .andExpect(content().string(not(containsString("responsePayload"))));
    }

    @Test
    void fiscalManagerWithReadAndGestionAccessCanReadTechnicalAttempts() throws Exception {
        UUID recordId = UUID.randomUUID();
        when(adminService.attempts(recordId)).thenReturn(List.of());
        when(attempts.history(recordId)).thenReturn(List.of());

        mvc.perform(get("/api/v1/verifactu/admin/records/{recordId}/attempts", recordId)
                        .with(fiscalManager()))
                .andExpect(status().isOk());
        mvc.perform(get(DEFECTIVE_RECORDS + "/{recordId}/attempts", recordId)
                        .with(fiscalManager()))
                .andExpect(status().isOk());
    }

    @Test
    void fiscalCorrectorWithReadAndGestionAccessCanCreateCorrection() throws Exception {
        UUID recordId = UUID.randomUUID();

        mvc.perform(post(DEFECTIVE_RECORDS + "/{recordId}/corrections", recordId)
                        .with(permissions(
                                "corrector",
                                "APP_GESTION_ACCESS",
                                "VERIFACTU_READ",
                                "VERIFACTU_CORRECT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isOk());
    }

    @Test
    void legacySalesManagementPermissionDoesNotGrantFiscalAdministration() throws Exception {
        var salesManager = permissions("sales-manager", "GESTION_VENTAS");

        mvc.perform(get(ADMIN_QUEUE).with(salesManager))
                .andExpect(status().isForbidden());
        mvc.perform(post(DEFECTIVE_RECORDS + "/{recordId}/corrections", UUID.randomUUID())
                        .with(permissions("sales-manager", "GESTION_VENTAS"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void fiscalPermissionsWithoutGestionAccessAreRejected() throws Exception {
        UUID recordId = UUID.randomUUID();

        mvc.perform(get(ADMIN_QUEUE)
                        .with(permissions("reader", "VERIFACTU_READ")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/verifactu/admin/records/{recordId}/attempts", recordId)
                        .with(permissions("manager", "VERIFACTU_READ", "VERIFACTU_MANAGE")))
                .andExpect(status().isForbidden());
        mvc.perform(post(DEFECTIVE_RECORDS + "/{recordId}/corrections", recordId)
                        .with(permissions("corrector", "VERIFACTU_READ", "VERIFACTU_CORRECT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void fiscalReaderWithoutSpecificActionPermissionCannotInspectAttemptsOrCorrect() throws Exception {
        UUID recordId = UUID.randomUUID();

        mvc.perform(get("/api/v1/verifactu/admin/records/{recordId}/attempts", recordId)
                        .with(fiscalReader()))
                .andExpect(status().isForbidden());
        mvc.perform(get(DEFECTIVE_RECORDS + "/{recordId}/attempts", recordId)
                        .with(fiscalReader()))
                .andExpect(status().isForbidden());
        mvc.perform(post(DEFECTIVE_RECORDS + "/{recordId}/corrections", recordId)
                        .with(fiscalReader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(correctionBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacySensitiveStatusAndGlobalRetryRemainAdminOnly() throws Exception {
        mvc.perform(get(ADMIN_STATUS).with(fiscalManager()))
                .andExpect(status().isForbidden());
        mvc.perform(post(ADMIN_RETRY_NEXT).with(fiscalManager()).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static RequestPostProcessor fiscalReader() {
        return permissions("reader", "APP_GESTION_ACCESS", "VERIFACTU_READ");
    }

    private static RequestPostProcessor fiscalManager() {
        return permissions(
                "manager",
                "APP_GESTION_ACCESS",
                "VERIFACTU_READ",
                "VERIFACTU_MANAGE");
    }

    private static RequestPostProcessor permissions(String username, String... permissionCodes) {
        var authorities = Arrays.stream(permissionCodes)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return user(username).authorities(authorities);
    }

    private static String correctionBody() {
        return """
                {
                  "reason": "Correccion administrativa autorizada",
                  "recipientTaxId": "12345678Z",
                  "recipientName": "Cliente de prueba",
                  "operationDescription": "Subsanacion contractual"
                }
                """;
    }

    private static String manualRetryBody() {
        return """
                {
                  "reason": "Incidencia de comunicacion revisada",
                  "expectedVersion": 0
                }
                """;
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
