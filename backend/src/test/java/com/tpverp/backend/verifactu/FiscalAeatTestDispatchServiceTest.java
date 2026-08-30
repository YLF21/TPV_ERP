package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.argThat;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class FiscalAeatTestDispatchServiceTest {

    private static final UUID COMPANY_ID = UUID.randomUUID();
    private static final UUID INSTALLATION_ID = UUID.randomUUID();
    private static final UUID RECORD_ID = UUID.randomUUID();
    private static final String RELEASE_ID = "DEV-TEST-20260827";
    private static final String HASH = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF";

    @Mock FiscalRuntimeProperties runtime;
    @Mock CurrentOrganization organization;
    @Mock InstallationRepository installations;
    @Mock LicenseRepository licenses;
    @Mock VerifactuSubmissionWorker worker;
    @Mock AuditService audit;
    @Mock FiscalResponsibleDeclarationService declarations;
    @Mock Company company;
    @Mock Installation installation;
    @Mock Authentication authentication;
    private FiscalAeatTestDispatchService service;

    @BeforeEach
    void setUp() {
        service = new FiscalAeatTestDispatchService(
                runtime, organization, installations, licenses, worker, audit, declarations);
        lenient().when(runtime.releaseManifest()).thenReturn(new FiscalReleaseManifest(
                RELEASE_ID, "4.2.0", FiscalProductCapability.VERIFACTU_ONLY, "V229",
                "abcdef1", HASH, HASH));
        lenient().when(runtime.runtimeClass()).thenReturn(FiscalRuntimeClass.SANDBOX);
        lenient().when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.TEST);
        lenient().when(runtime.transportMode()).thenReturn(FiscalTransportMode.AEAT);
        lenient().when(runtime.productCapability()).thenReturn(FiscalProductCapability.VERIFACTU_ONLY);
        lenient().when(runtime.resolvedArtifactHash()).thenReturn(HASH);
        lenient().when(declarations.status()).thenReturn(new FiscalResponsibleDeclarationService.ResponsibleDeclarationStatus(
                "AVAILABLE", "4.2.0", RELEASE_ID, "declaracion.pdf", "application/pdf",
                123L, HASH, null, "/api/v1/fiscal/responsible-declaration/content"));
        lenient().when(organization.currentCompany()).thenReturn(company);
        lenient().when(company.getId()).thenReturn(COMPANY_ID);
        lenient().when(installations.findAll()).thenReturn(List.of(installation));
        lenient().when(installation.getId()).thenReturn(INSTALLATION_ID);
        lenient().when(authentication.getName()).thenReturn("admin");
    }

    @Test
    void processedFalseIsReturnedAndAuditedWithoutClaimingRecord() {
        when(worker.processPendingForScope(COMPANY_ID, INSTALLATION_ID, null))
                .thenReturn(VerifactuWorkerResult.empty());

        var response = service.dispatch(request(null, "CONFIRMAR_AEAT_TEST"), authentication);

        assertThat(response.processed()).isFalse();
        assertThat(response.status()).isNull();
        assertThat(response.errorCode()).isNull();
        assertThat(response.evidence().releaseId()).isEqualTo(RELEASE_ID);
        assertThat(response.evidence().certificateMaterialRedacted()).isTrue();
        verify(worker).processPendingForScope(COMPANY_ID, INSTALLATION_ID, null);
        verify(runtime).requireAeatTestReleaseCandidate();
        verify(audit).record(any(), any(), any());
        verify(audit).record(any(), eq(com.tpverp.backend.audit.AuditResult.FALLO), any());
    }

    @Test
    void networkEvidenceIsPropagatedAndErrorIsRedacted() {
        when(worker.processPendingForScope(COMPANY_ID, INSTALLATION_ID, RECORD_ID))
                .thenReturn(new VerifactuWorkerResult(
                        true, FiscalSubmissionStatus.RECHAZADO, "AEAT_RECHAZADO",
                        "<soap><certificate-password>dont-return-me</certificate-password></soap>",
                        RECORD_ID, true));

        var response = service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication);

        assertThat(response.evidence().networkRequestIssued()).isTrue();
        assertThat(response.error()).doesNotContain("dont-return-me").doesNotContain("<soap>");
        assertThat(response.error()).hasSizeLessThanOrEqualTo(256);
    }

    @Test
    void processedWithoutNetworkIsNotReportedAsRealEvidence() {
        when(worker.processPendingForScope(COMPANY_ID, INSTALLATION_ID, RECORD_ID))
                .thenReturn(new VerifactuWorkerResult(
                        true, FiscalSubmissionStatus.DEFECTUOSO, "INVALID_XSD",
                        "schema error", RECORD_ID, false));

        var response = service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication);

        assertThat(response.processed()).isTrue();
        assertThat(response.evidence().networkRequestIssued()).isFalse();
    }

    @Test
    void confirmationMustBeExact() {
        assertThatThrownBy(() -> service.dispatch(request(null, "confirmar_aeat_test"), authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactamente");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = FiscalRuntimeClass.class, names = {"REAL"})
    void realRuntimeIsAlwaysBlocked(FiscalRuntimeClass runtimeClass) {
        when(runtime.runtimeClass()).thenReturn(runtimeClass);

        assertThatThrownBy(() -> service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void productionEndpointIsAlwaysBlocked() {
        when(runtime.endpointEnvironment()).thenReturn(FiscalEndpointEnvironment.PRODUCTION);

        assertThatThrownBy(() -> service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoint TEST");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void simulatedTransportIsAlwaysBlocked() {
        when(runtime.transportMode()).thenReturn(FiscalTransportMode.SIMULATED);

        assertThatThrownBy(() -> service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transporte AEAT");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void networkOptInIsRequired() {
        org.mockito.Mockito.doThrow(new IllegalStateException("opt-in requerido"))
                .when(runtime).requireAeatTestNetwork();

        assertThatThrownBy(() -> service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opt-in");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void declarationResponsibleMustBeAvailableAndAValidPdf() {
        when(declarations.status()).thenReturn(new FiscalResponsibleDeclarationService.ResponsibleDeclarationStatus(
                "UNAVAILABLE", "4.2.0", RELEASE_ID, null, null, null, null, null, null));

        assertThatThrownBy(() -> service.dispatch(request(RECORD_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declaracion responsable");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void crossCompanyScopeCannotReachWorker() {
        var otherCompany = UUID.randomUUID();

        assertThatThrownBy(() -> service.dispatch(
                new FiscalAeatTestDispatchRequest(
                        otherCompany, INSTALLATION_ID, RECORD_ID,
                        RELEASE_ID, "CONFIRMAR_AEAT_TEST"), authentication))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("empresa");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    @Test
    void expectedReleaseIsMandatoryAndBoundToRuntimeManifest() {
        assertThatThrownBy(() -> service.dispatch(request(null, "CONFIRMAR_AEAT_TEST", "OTHER"), authentication))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release activo");
        verify(worker, never()).processPendingForScope(any(), any(), any());
    }

    private static FiscalAeatTestDispatchRequest request(UUID recordId, String confirmation) {
        return request(recordId, confirmation, RELEASE_ID);
    }

    private static FiscalAeatTestDispatchRequest request(
            UUID recordId, String confirmation, String releaseId) {
        return new FiscalAeatTestDispatchRequest(
                COMPANY_ID, INSTALLATION_ID, recordId, releaseId, confirmation);
    }
}
