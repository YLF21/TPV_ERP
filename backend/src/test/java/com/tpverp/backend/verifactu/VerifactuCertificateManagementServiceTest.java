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
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
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
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class VerifactuCertificateManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-23T10:00:00Z");

    @Mock ManagedVerifactuCertificateRepository certificates;
    @Mock VerifactuCertificateImporter importer;
    @Mock VerifactuCertificateSecretStore secrets;
    @Mock CurrentOrganization organization;
    @Mock AuditService audit;
    @Mock Authentication authentication;

    private UUID companyId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        companyId = UUID.randomUUID();
        userId = UUID.randomUUID();
        var company = mock(Company.class);
        var user = mock(UserAccount.class);
        when(company.getId()).thenReturn(companyId);
        when(user.getId()).thenReturn(userId);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentUser(authentication)).thenReturn(user);
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

        var result = service().importCertificate(new byte[] {9}, password, authentication);

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

        service().importCertificate(new byte[] {9}, "secreto".toCharArray(), authentication);

        assertThat(active.getStatus()).isEqualTo(ManagedCertificateStatus.ANTERIOR);
        assertThat(previous.getStatus()).isEqualTo(ManagedCertificateStatus.ELIMINADO);
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
                new byte[] {9}, "secreto".toCharArray(), authentication))
                .isInstanceOf(IllegalStateException.class);

        verify(secrets).delete("company/cert/private-key.dpapi");
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
        verify(secrets).delete("active/key.dpapi");
        verify(audit).record(eq("VERIFACTU_CERTIFICATE_DELETED"), eq(EXITO), any());
    }

    private VerifactuCertificateManagementService service() {
        return new VerifactuCertificateManagementService(
                certificates, importer, secrets, organization, audit,
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
