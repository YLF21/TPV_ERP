package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VerifactuCertificateController.class)
@Import(VerifactuCertificateControllerWebMvcTest.MethodSecurityConfiguration.class)
class VerifactuCertificateControllerWebMvcTest {

    private static final String PATH = "/api/v1/verifactu/admin/certificates";

    @Autowired MockMvc mvc;
    @MockitoBean VerifactuCertificateManagementService service;

    @Test
    void adminReadsDeletionAndValidityMetadataButNonAdminIsRejected() throws Exception {
        var view = new ManagedCertificateView(
                UUID.randomUUID(), ManagedCertificateStatus.ACTIVO,
                "CN=Empresa", "CN=Emisor", "123", "B12345674", "A".repeat(64),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
                ManagedCertificateValidityStatus.VALIDO, 163, true, null);
        when(service.list()).thenReturn(List.of(view));

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].validityStatus").value("VALIDO"))
                .andExpect(jsonPath("$[0].daysRemaining").value(163))
                .andExpect(jsonPath("$[0].canDelete").value(true))
                .andExpect(jsonPath("$[0].deleteBlockReason").doesNotExist());
        mvc.perform(get(PATH).with(user("reader").authorities()))
                .andExpect(status().isForbidden());
    }

    @Test
    void replacementForwardsConfirmationAndExpectedActiveCertificateId() throws Exception {
        var expectedId = UUID.randomUUID();
        var file = new MockMultipartFile(
                "file", "certificate.p12", "application/x-pkcs12", new byte[] {1, 2, 3});
        var response = new ManagedCertificateView(
                UUID.randomUUID(), ManagedCertificateStatus.ACTIVO,
                "CN=Empresa", "CN=Emisor", "123", "B12345674", "A".repeat(64),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"),
                ManagedCertificateValidityStatus.VALIDO, 163, false,
                VerifactuCertificateDeletionPolicy.VERIFACTU_ACTIVE);
        when(service.importCertificate(
                any(), any(), eq(expectedId), eq("SUSTITUIR CERTIFICADO"), any()))
                .thenAnswer(invocation -> {
                    assertThat((char[]) invocation.getArgument(1))
                            .containsExactly("secreto".toCharArray());
                    return response;
                });

        mvc.perform(multipart(PATH)
                        .file(file)
                        .param("password", "secreto")
                        .param("expectedActiveCertificateId", expectedId.toString())
                        .param("confirmation", "SUSTITUIR CERTIFICADO")
                        .with(csrf())
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.deleteBlockReason").value("VERIFACTU_ACTIVE"));

        verify(service).importCertificate(
                any(), any(), eq(expectedId), eq("SUSTITUIR CERTIFICADO"), any());
    }

    @Test
    void certificateExceptionUsesStableProblemDetailCodeAndBlockReason() throws Exception {
        when(service.list()).thenThrow(VerifactuCertificateApiException.conflict(
                "VERIFACTU_CERTIFICATE_DELETE_BLOCKED",
                "El certificado no puede eliminarse",
                Map.of("deleteBlockReason", "NON_FINAL_SUBMISSIONS_EXIST")));

        mvc.perform(get(PATH).with(user("admin").roles("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERIFACTU_CERTIFICATE_DELETE_BLOCKED"))
                .andExpect(jsonPath("$.deleteBlockReason")
                        .value("NON_FINAL_SUBMISSIONS_EXIST"));
    }

    @Test
    void certificateImportFailureReturnsSafeLocalizedDetail() throws Exception {
        when(service.list()).thenThrow(VerifactuCertificateApiException.badRequest(
                "CERTIFICATE_VALIDATION_FAILED",
                "message.verifactu.certificate.validation_failed",
                Map.of("errors", List.of(
                        Map.of("code", "CERTIFICATE_EXPIRED"),
                        Map.of("code", "CERTIFICATE_TAX_ID_MISMATCH")))));

        mvc.perform(get(PATH)
                        .header("Accept-Language", "es")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("CERTIFICATE_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail")
                        .value("El certificado contiene uno o varios errores de validacion"))
                .andExpect(jsonPath("$.errors[0].code")
                        .value("CERTIFICATE_EXPIRED"))
                .andExpect(jsonPath("$.errors[1].code")
                        .value("CERTIFICATE_TAX_ID_MISMATCH"));
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
