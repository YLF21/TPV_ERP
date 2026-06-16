package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VerifactuSubmissionPropertiesTest {

    @Test
    void normalizaConfiguracionDeEnvio() {
        var properties = new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST_SEAL,
                Path.of("certs/aeat.p12"),
                " secreto ".toCharArray(),
                "TPV ERP",
                "01");

        assertThat(properties.mode()).isEqualTo(VerifactuEndpointMode.TEST_SEAL);
        assertThat(properties.certificatePath()).isEqualTo(Path.of("certs/aeat.p12"));
        assertThat(properties.certificatePassword()).containsExactly("secreto".toCharArray());
        assertThat(properties.systemName()).isEqualTo("TPV ERP");
        assertThat(properties.systemId()).isEqualTo("01");
    }

    @Test
    void rechazaConfiguracionSinCertificadoOPassword() {
        assertThatThrownBy(() -> new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, null, "x".toCharArray(), "TPV ERP", "01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("certificado");
        assertThatThrownBy(() -> new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, Path.of("cert.p12"), " ".toCharArray(), "TPV ERP", "01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password");
    }
}
