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
        pkcs12 = createPkcs12("test-rsa.p12", "RSA");
    }

    private byte[] createPkcs12(String filename, String keyAlgorithm) throws Exception {
        var path = directory.resolve(filename);
        var process = new ProcessBuilder(
                KeytoolTestSupport.executable(),
                "-genkeypair", "-alias", "test", "-storetype", "PKCS12",
                "-keystore", path.toString(), "-storepass", "secreto", "-keypass", "secreto",
                "-keyalg", keyAlgorithm,
                "-dname", "CN=Company,SERIALNUMBER=IDCES-B12345674",
                "-validity", "365", "-noprompt")
                .redirectErrorStream(true)
                .start();
        if (process.waitFor() != 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        return Files.readAllBytes(path);
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
                validator,
                runtime(true));

        assertThatThrownBy(() -> importer.importPkcs12(
                pkcs12, "secreto".toCharArray(), "A58818501"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .satisfies(value -> assertThat(
                        ((VerifactuCertificateImportException) value).failures())
                        .containsExactly(
                                VerifactuCertificateImportException.Failure.EXPIRED,
                                VerifactuCertificateImportException.Failure.TAX_ID_MISMATCH));
    }

    @Test
    void rejectsNonRsaPrivateKeysEvenInSandbox() throws Exception {
        var ecPkcs12 = createPkcs12("test-ec.p12", "EC");

        assertThatThrownBy(() -> importer().importPkcs12(
                ecPkcs12, "secreto".toCharArray(), "B12345674"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .satisfies(value -> assertThat(
                        ((VerifactuCertificateImportException) value).failures())
                        .contains(
                                VerifactuCertificateImportException.Failure
                                        .KEY_ALGORITHM_UNSUPPORTED));
    }

    @Test
    void rejectsSelfSignedCertificateOutsideSandbox() {
        var importer = new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(), new CertificateTaxIdExtractor(),
                new VerifactuCertificateValidator(Clock.systemUTC()), runtime(false));

        assertThatThrownBy(() -> importer.importPkcs12(
                pkcs12, "secreto".toCharArray(), "B12345674"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .satisfies(value -> assertThat(
                        ((VerifactuCertificateImportException) value).failures())
                        .containsExactly(
                                VerifactuCertificateImportException.Failure
                                        .CERTIFICATE_CHAIN_UNTRUSTED,
                                VerifactuCertificateImportException.Failure
                                        .SELF_SIGNED_NOT_ALLOWED));
    }

    @Test
    void rejectsCryptographicallyValidChainSignedByUntrustedPrivateCaInReal() throws Exception {
        var privateCaChain = createPrivateCaSignedPkcs12();
        var importer = new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(), new CertificateTaxIdExtractor(),
                new VerifactuCertificateValidator(Clock.systemUTC()), runtime(false));

        assertThatThrownBy(() -> importer.importPkcs12(
                privateCaChain, "secreto".toCharArray(), "B12345674"))
                .isInstanceOf(VerifactuCertificateImportException.class)
                .satisfies(value -> assertThat(
                        ((VerifactuCertificateImportException) value).failures())
                        .containsExactly(
                                VerifactuCertificateImportException.Failure
                                        .CERTIFICATE_CHAIN_UNTRUSTED));
    }

    private byte[] createPrivateCaSignedPkcs12() throws Exception {
        var caStore = directory.resolve("private-ca.p12");
        runKeytool(
                "-genkeypair", "-alias", "private-ca", "-storetype", "PKCS12",
                "-keystore", caStore.toString(), "-storepass", "secreto",
                "-keypass", "secreto", "-keyalg", "RSA",
                "-dname", "CN=Private Test CA", "-validity", "365",
                "-ext", "bc=ca:true", "-ext", "ku=keyCertSign,cRLSign", "-noprompt");

        var leafStore = directory.resolve("private-leaf.p12");
        runKeytool(
                "-genkeypair", "-alias", "leaf", "-storetype", "PKCS12",
                "-keystore", leafStore.toString(), "-storepass", "secreto",
                "-keypass", "secreto", "-keyalg", "RSA",
                "-dname", "CN=Company,SERIALNUMBER=IDCES-B12345674",
                "-validity", "365", "-noprompt");

        var request = directory.resolve("leaf.csr");
        runKeytool(
                "-certreq", "-alias", "leaf", "-keystore", leafStore.toString(),
                "-storepass", "secreto", "-file", request.toString());
        var signedLeaf = directory.resolve("leaf-signed.pem");
        runKeytool(
                "-gencert", "-alias", "private-ca", "-keystore", caStore.toString(),
                "-storepass", "secreto", "-infile", request.toString(),
                "-outfile", signedLeaf.toString(), "-rfc", "-validity", "365",
                "-ext", "bc=ca:false", "-ext", "ku=digitalSignature");
        var caCertificate = directory.resolve("private-ca.pem");
        runKeytool(
                "-exportcert", "-alias", "private-ca", "-keystore", caStore.toString(),
                "-storepass", "secreto", "-file", caCertificate.toString(), "-rfc");
        runKeytool(
                "-importcert", "-alias", "private-ca", "-keystore", leafStore.toString(),
                "-storepass", "secreto", "-file", caCertificate.toString(), "-noprompt");
        runKeytool(
                "-importcert", "-alias", "leaf", "-keystore", leafStore.toString(),
                "-storepass", "secreto", "-file", signedLeaf.toString(), "-noprompt");
        return Files.readAllBytes(leafStore);
    }

    private static void runKeytool(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(KeytoolTestSupport.executable());
        command.addAll(java.util.List.of(arguments));
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
    }

    private static VerifactuCertificateImporter importer() {
        return new VerifactuCertificateImporter(
                new VerifactuPkcs12KeyStoreLoader(), new CertificateTaxIdExtractor(),
                new VerifactuCertificateValidator(Clock.systemUTC()), runtime(true));
    }

    private static FiscalRuntimeProperties runtime(boolean sandbox) {
        var runtime = mock(FiscalRuntimeProperties.class);
        when(runtime.isSandbox()).thenReturn(sandbox);
        return runtime;
    }
}
