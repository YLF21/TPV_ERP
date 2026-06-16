package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class VerifactuSubmissionPropertiesFactoryTest {

    @Test
    void leeConfiguracionDesdeEnvironment() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.endpoint-mode", "TEST_SEAL")
                .withProperty("tpv.verifactu.certificate-path", "certs/aeat.p12")
                .withProperty("tpv.verifactu.certificate-password", "secreto")
                .withProperty("tpv.verifactu.system-name", "TPV ERP")
                .withProperty("tpv.verifactu.system-id", "01");

        var properties = new VerifactuSubmissionPropertiesFactory(environment).current();

        assertThat(properties.mode()).isEqualTo(VerifactuEndpointMode.TEST_SEAL);
        assertThat(properties.certificatePath()).isEqualTo(Path.of("certs/aeat.p12"));
        assertThat(properties.certificatePassword()).containsExactly("secreto".toCharArray());
        assertThat(properties.systemName()).isEqualTo("TPV ERP");
        assertThat(properties.systemId()).isEqualTo("01");
    }
}
