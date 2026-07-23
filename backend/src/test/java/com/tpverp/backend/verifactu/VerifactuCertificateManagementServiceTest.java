package com.tpverp.backend.verifactu;

import static com.tpverp.backend.audit.AuditResult.EXITO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.organization.CompanyRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VerifactuCertificateManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");

    @Mock ManagedVerifactuCertificateRepository certificates;
    @Mock VerifactuCertificateImporter importer;
    @Mock VerifactuCertificateSecretStore secrets;
    @Mock VerifactuSecretDeletionService secretDeletions;
    @Mock CurrentOrganization organization;
    @Mock CompanyRepository companies;
    @Mock VerifactuCertificateDeletionPolicy deletionPolicy;
    @Mock AuditService audit;
    @Mock Authentication authentication;

    private UUID companyId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        userId = UUID.randomUUID();
        var company = mock(Company.class);
        var store = mock(Store.class);
        var user = mock(UserAccount.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(store.getEmpresa()).thenReturn(company);
        when(store.getTimezone()).thenReturn("Atlantic/Canary");
        when(user.getId()).thenReturn(userId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(organization.currentUser(authentication)).thenReturn(user);
        when(companies.findForUpdate(companyId)).thenReturn(Optional.of(company));
        when(deletionPolicy.evaluate()).thenReturn(
                VerifactuCertificateDeletionDecision.allowed());
        when(deletionPolicy.evaluateForUpdate()).thenReturn(
                VerifactuCertificateDeletionDecision.allowed());
    }

    @Test
    void importsFirstCertificateAndClearsReceivedPassword() {
        stubImport();
        stubSave();
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.empty());
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ANTERIOR))
                .thenReturn(Optional.empty());
        var password = "secreto".toCharArray();

        var result = service().importCertificate(
                new byte[] {9}, password, null, null, authentication);

        assertThat(result.status()).isEqualTo(ManagedCertificateStatus.ACTIVO);
        assertThat(password).containsOnly('\0');
        verify(audit).record(eq("VERIFACTU_CERTIFICATE_IMPORTED"), eq(EXITO), any());
    }

    @Test
    void replacementKeepsOnlyImmediatePreviousCertificate() {
        stubImport();
        stubSave();
        var active = certificate(ManagedCertificateStatus.ACTIVO, "active/key.dpapi");
        var previous = certificate(ManagedCertificateStatus.ANTERIOR, "previous/key.dpapi");
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(active));
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ANTERIOR))
                .thenReturn(Optional.of(previous));

        service().importCertificate(
                new byte[] {9}, "secreto".toCharArray(), active.getId(),
                "SUSTITUIR CERTIFICADO", authentication);

        assertThat(active.getStatus()).isEqualTo(ManagedCertificateStatus.ANTERIOR);
        assertThat(previous.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
        verify(secretDeletions).enqueue(
                companyId, previous.getId(), "previous/key.dpapi",
                VerifactuSecretDeletionReason.PREVIOUS_REPLACED);
        verify(secrets, never()).delete(any());
        verify(audit).record(eq("VERIFACTU_CERTIFICATE_REPLACED"), eq(EXITO), any());
    }

    @Test
    void deletesNewSecretIfDatabaseSaveFails() {
        stubImport();
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.empty());
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ANTERIOR))
                .thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database")).when(certificates).save(any());

        assertThatThrownBy(() -> service().importCertificate(
                new byte[] {9}, "secreto".toCharArray(), null, null, authentication))
                .isInstanceOf(IllegalStateException.class);

        verify(secretDeletions).enqueueAfterRollback(
                companyId, "company/cert/private-key.dpapi",
                VerifactuSecretDeletionReason.IMPORT_ROLLBACK);
        verify(secrets, never()).delete(any());
        verify(audit, never()).record(any(), any(), any());
    }

    @Test
    void deletesActiveCertificateWithExactConfirmation() {
        var active = certificate(ManagedCertificateStatus.ACTIVO, "active/key.dpapi");
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(active));
        when(certificates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().deleteActive("ELIMINAR CERTIFICADO", authentication);

        assertThat(active.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
        assertThat(active.getSecretPath()).isNull();
        verify(secretDeletions).enqueue(
                companyId, active.getId(), "active/key.dpapi",
                VerifactuSecretDeletionReason.ACTIVE_DELETED);
        verify(secrets, never()).delete(any());
        verify(audit).record(eq("VERIFACTU_CERTIFICATE_DELETED"), eq(EXITO), any());
    }

    @Test
    void replacementRequiresExactConfirmationAndCurrentActiveId() {
        stubImport();
        var active = certificate(ManagedCertificateStatus.ACTIVO, "active/key.dpapi");
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service().importCertificate(
                new byte[] {9}, "secreto".toCharArray(), active.getId(), null, authentication))
                .isInstanceOf(VerifactuCertificateApiException.class)
                .extracting(value -> ((VerifactuCertificateApiException) value).code())
                .isEqualTo("VERIFACTU_CERTIFICATE_REPLACEMENT_CONFIRMATION_REQUIRED");

        assertThatThrownBy(() -> service().importCertificate(
                new byte[] {9}, "secreto".toCharArray(), UUID.randomUUID(),
                "SUSTITUIR CERTIFICADO", authentication))
                .isInstanceOf(VerifactuCertificateApiException.class)
                .extracting(value -> ((VerifactuCertificateApiException) value).code())
                .isEqualTo("VERIFACTU_ACTIVE_CERTIFICATE_CHANGED");
        verify(secrets, never()).write(any(), any(), any());
    }

    @Test
    void deleteIsBlockedByBackendPolicy() {
        var active = certificate(ManagedCertificateStatus.ACTIVO, "active/key.dpapi");
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(active));
        when(deletionPolicy.evaluateForUpdate()).thenReturn(
                VerifactuCertificateDeletionDecision.blocked(
                        VerifactuCertificateDeletionPolicy.VERIFACTU_ACTIVE));

        assertThatThrownBy(() -> service().deleteActive(
                "ELIMINAR CERTIFICADO", authentication))
                .isInstanceOf(VerifactuCertificateApiException.class)
                .satisfies(value -> {
                    var exception = (VerifactuCertificateApiException) value;
                    assertThat(exception.code()).isEqualTo(
                            "VERIFACTU_CERTIFICATE_DELETE_BLOCKED");
                    assertThat(exception.properties()).containsEntry(
                            "deleteBlockReason", VerifactuCertificateDeletionPolicy.VERIFACTU_ACTIVE);
                });
        verify(secrets, never()).delete(any());
    }

    @Test
    void previousCertificateIsNeverReportedAsDeletable() {
        var active = certificate(ManagedCertificateStatus.ACTIVO, "active/key.dpapi");
        var previous = certificate(ManagedCertificateStatus.ANTERIOR, "previous/key.dpapi");
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(active));
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ANTERIOR))
                .thenReturn(Optional.of(previous));

        var result = service().list();

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().canDelete()).isTrue();
        assertThat(result.get(1).canDelete()).isFalse();
        assertThat(result.get(1).deleteBlockReason()).isEqualTo(
                VerifactuCertificateDeletionPolicy.NOT_ACTIVE_CERTIFICATE);
    }

    @Test
    void invalidPkcs12UsesStableApiCodeAndDoesNotTouchActiveCertificate() {
        when(organization.currentCompany().getTaxId()).thenReturn("B12345674");
        when(importer.importPkcs12(any(), any(), eq("B12345674")))
                .thenThrow(VerifactuCertificateImportException.of(
                        VerifactuCertificateImportException.Failure.PASSWORD_OR_FILE_INVALID));

        assertThatThrownBy(() -> service().importCertificate(
                new byte[] {9}, "incorrecta".toCharArray(), null, null, authentication))
                .isInstanceOf(VerifactuCertificateApiException.class)
                .satisfies(value -> {
                    var exception = (VerifactuCertificateApiException) value;
                    assertThat(exception.code()).isEqualTo("CERTIFICATE_VALIDATION_FAILED");
                    assertThat(exception.properties()).containsEntry(
                            "errors",
                            java.util.List.of(java.util.Map.of(
                                    "code", "CERTIFICATE_PASSWORD_OR_FILE_INVALID")));
                });
        verify(certificates, never()).save(any());
        verify(secrets, never()).write(any(), any(), any());
    }

    @Test
    void secureStorageFailureUsesStableServerCodeAndClearsPassword() {
        stubImport();
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.empty());
        when(secrets.write(eq(companyId), any(), any()))
                .thenThrow(new IllegalStateException("sensitive storage detail"));
        var password = "secreto".toCharArray();

        assertThatThrownBy(() -> service().importCertificate(
                new byte[] {9}, password, null, null, authentication))
                .isInstanceOf(VerifactuCertificateApiException.class)
                .satisfies(value -> {
                    var exception = (VerifactuCertificateApiException) value;
                    assertThat(exception.status().value()).isEqualTo(500);
                    assertThat(exception.code()).isEqualTo("CERTIFICATE_STORAGE_FAILED");
                    assertThat(exception.getMessage()).isEqualTo(
                            "message.verifactu.certificate.storage_failed");
                });

        assertThat(password).containsOnly('\0');
        verify(certificates, never()).save(any());
    }

    private VerifactuCertificateManagementService service() {
        return new VerifactuCertificateManagementService(
                certificates, importer, secrets, secretDeletions,
                organization, companies, deletionPolicy, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubSave() {
        when(certificates.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubImport() {
        when(organization.currentCompany().getTaxId()).thenReturn("B12345674");
        when(importer.importPkcs12(any(), any(), eq("B12345674"))).thenReturn(material());
        when(secrets.write(eq(companyId), any(), any()))
                .thenReturn("company/cert/private-key.dpapi");
    }

    private ManagedVerifactuCertificate certificate(
            ManagedCertificateStatus targetStatus, String path) {
        var certificate = ManagedVerifactuCertificate.active(
                companyId, "CN=Company", "CN=AC", UUID.randomUUID().toString(),
                "B12345674", NOW.minusSeconds(60), NOW.plusSeconds(3600),
                "A".repeat(64), new byte[] {1}, path, NOW.minusSeconds(120), userId);
        if (targetStatus == ManagedCertificateStatus.ANTERIOR) {
            certificate.markPrevious(NOW.minusSeconds(60), userId);
        }
        return certificate;
    }

    private static ImportedCertificateMaterial material() {
        return new ImportedCertificateMaterial(
                "CN=Company", "CN=AC", "1234", "B12345674",
                NOW.minusSeconds(60), NOW.plusSeconds(3600),
                "B".repeat(64), new byte[] {1, 2}, new byte[] {3, 4});
    }
}
