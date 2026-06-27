package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedCertificateKeyStoreFactoryTest {

    @TempDir Path directory;

    @Test
    void rebuildsEphemeralKeyStoreFromManagedCertificate() throws Exception {
        var material = material();
        var companyId = UUID.randomUUID();
        var certificate = ManagedVerifactuCertificate.active(
                companyId, material.subject(), material.issuer(), material.serialNumber(),
                material.taxId(), material.validFrom(), material.validUntil(),
                material.fingerprint(), material.publicChainPkcs7(), "secret.dpapi",
                Instant.now(), UUID.randomUUID());
        var certificates = mock(ManagedVerifactuCertificateRepository.class);
        var secrets = mock(VerifactuCertificateSecretStore.class);
        var organization = mock(CurrentOrganization.class);
        var company = mock(Company.class);
        when(company.getId()).thenReturn(companyId);
        when(organization.currentCompany()).thenReturn(company);
        when(certificates.findByCompanyIdAndStatus(companyId, ManagedCertificateStatus.ACTIVO))
                .thenReturn(Optional.of(certificate));
        when(secrets.read("secret.dpapi")).thenReturn(material.privateKeyPkcs8());

        try (var managed = new ManagedCertificateKeyStoreFactory(
                certificates, secrets, organization, Clock.systemUTC()).activeForCurrentCompany()) {
            assertThat(managed.keyStore().isKeyEntry("verifactu")).isTrue();
            assertThat(managed.password()).isNotEmpty();
        }
    }

    private ImportedCertificateMaterial material() throws Exception {
        var path = directory.resolve("test.p12");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString(),
                "-genkeypair", "-alias", "test", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "secreto", "-keypass", "secreto",
                "-keyalg", "RSA", "-dname", "CN=Company,SERIALNUMBER=IDCES-B12345674",
                "-validity", "365", "-noprompt")
                .redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        return new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(), new CertificateTaxIdExtractor(),
                new VerifactuCertificateValidator(Clock.systemUTC()))
                .importPkcs12(Files.readAllBytes(path), "secreto".toCharArray(), "B12345674");
    }
}
