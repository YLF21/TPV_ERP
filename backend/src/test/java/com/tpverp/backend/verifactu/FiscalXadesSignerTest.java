package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class FiscalXadesSignerTest {

    @TempDir Path directory;
    private FiscalRecord record;
    private Path pkcs12;

    @BeforeEach
    void setUp() throws Exception {
        pkcs12 = directory.resolve("sandbox-signing.p12");
        var process = new ProcessBuilder(
                KeytoolTestSupport.executable(), "-genkeypair", "-alias", "test",
                "-storetype", "PKCS12", "-keystore", pkcs12.toString(),
                "-storepass", "secreto", "-keypass", "secreto", "-keyalg", "RSA",
                "-dname", "CN=DEV Fiscal Test,SERIALNUMBER=DEV-00000000",
                "-validity", "365", "-noprompt")
                .redirectErrorStream(true).start();
        if (process.waitFor() != 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        record = mock(FiscalRecord.class);
        when(record.getFiscalMode()).thenReturn(FiscalMode.NO_VERIFACTU);
        when(record.getCompanyId()).thenReturn(UUID.randomUUID());
        when(record.getInstallationId()).thenReturn(UUID.randomUUID());
    }

    @Test
    void signsOnlyTheFiscalRecordAndEmbedsAgePolicy() throws Exception {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED")
                .withProperty("tpv.verifactu.dev-signing-pkcs12", pkcs12.toString())
                .withProperty("tpv.verifactu.dev-signing-password", "secreto");
        var signer = new FiscalXadesSigner(mock(ManagedCertificateKeyStoreFactory.class),
                new FiscalRuntimeProperties(environment));

        var signed = signer.sign(record,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sf:RegistroAlta "
                        + "xmlns:sf=\"https://www2.agenciatributaria.gob.es/static_files/common/"
                        + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\">"
                        + "<sf:IDVersion>1.0</sf:IDVersion></sf:RegistroAlta>");

        assertThat(signed).contains("<ds:Signature");
        assertThat(signed).contains(FiscalXadesSigner.POLICY_ID);
        assertThat(signed).contains(FiscalXadesSigner.POLICY_URL);
        assertThat(signed).contains("rsa-sha256");
        assertThat(Files.readAllBytes(pkcs12)).isNotEmpty();
    }
}
