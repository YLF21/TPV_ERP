package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                "-keyalg", "RSA", "-dname", "CN=Empresa,SERIALNUMBER=IDCES-B12345674",
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
    void rejectsWrongPasswordAndCertificateForAnotherCompany() {
        assertThatThrownBy(() -> importer().importPkcs12(
                pkcs12, "incorrecta".toCharArray(), "B12345674"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No se pudo cargar el certificado PKCS#12");

        assertThatThrownBy(() -> importer().importPkcs12(
                pkcs12, "secreto".toCharArray(), "A58818501"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El NIF del certificado no coincide con la empresa");
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
