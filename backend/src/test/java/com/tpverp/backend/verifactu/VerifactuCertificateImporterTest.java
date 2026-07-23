package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifactuCertificateImporterTest {

    @TempDir Path directory;
    private byte[] pkcs12;

    @BeforeEach
    void createPkcs12() throws Exception {
        var path = directory.resolve("test.p12");
        var process = new ProcessBuilder(
                keytool(), "-genkeypair", "-alias", "test", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "secreto", "-keypass", "secreto",
                "-keyalg", "RSA", "-dname", "CN=Company,SERIALNUMBER=IDCES-B12345674",
                "-validity", "365", "-noprompt")
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        pkcs12 = Files.readAllBytes(path);
    }

    @Test
    void importsPrivateKeyPublicChainAndMetadata() {
        var material = importer().importPkcs12(
                pkcs12, "secreto".toCharArray(), "B12345674");

        assertThat(material.taxId()).isEqualTo("B12345674");
        assertThat(material.privateKeyPkcs8()).isNotEmpty();
        assertThat(material.publicChainPkcs7()).isNotEmpty();
        assertThat(material.fingerprint()).matches("[0-9A-F]{64}");
        assertThat(material.validUntil()).isAfter(material.validFrom());
    }

    @Test
    void importsForDemoCompanyWithoutComparingAgainstPlaceholderTaxId() {
        var material = importer().importPkcs12(
                pkcs12, "secreto".toCharArray(), Company.DEMO_TAX_ID);

        assertThat(material.taxId()).isEqualTo("B12345674");
        assertThat(material.privateKeyPkcs8()).isNotEmpty();
    }

    @Test
    void rejectsWrongPasswordAndCertificateForAnotherCompany() {
        assertThatThrownBy(() -> importer().importPkcs12(
                pkcs12, "incorrecta".toCharArray(), "B12345674"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .extracting(value -> ((VerifactuCertificateImportException) value).failure())
                .isEqualTo(VerifactuCertificateImportException.Failure.PASSWORD_OR_FILE_INVALID);

        assertThatThrownBy(() -> importer().importPkcs12(
                pkcs12, "secreto".toCharArray(), "A58818501"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .extracting(value -> ((VerifactuCertificateImportException) value).failure())
                .isEqualTo(VerifactuCertificateImportException.Failure.TAX_ID_MISMATCH);
    }

    @Test
    void collectsAllIndependentValidationFailures() {
        var validator = mock(VerifactuCertificateValidator.class);
        when(validator.validate(any())).thenReturn(new VerifactuCertificateStatus(
                false,
                "CERTIFICATE_EXPIRED",
                "CN=Company",
                java.time.Instant.parse("2025-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-01-01T00:00:00Z")));
        var importer = new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(),
                new CertificateTaxIdExtractor(),
                validator);

        assertThatThrownBy(() -> importer.importPkcs12(
                pkcs12, "secreto".toCharArray(), "A58818501"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .satisfies(value -> assertThat(
                        ((VerifactuCertificateImportException) value).failures())
                        .containsExactly(
                                VerifactuCertificateImportException.Failure.EXPIRED,
                                VerifactuCertificateImportException.Failure.TAX_ID_MISMATCH));
    }

    private static VerifactuCertificateImporter importer() {
        return new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(), new CertificateTaxIdExtractor(),
                new VerifactuCertificateValidator(Clock.systemUTC()));
    }

    private static String keytool() {
        return Path.of(System.getProperty("java.home"), "bin", "keytool.exe").toString();
    }
}
