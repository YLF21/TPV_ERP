package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VerifactuSubmissionPropertiesTest {

    @Test
    void normalizaConfiguracionDeEnvio() {
        var properties = new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST_SEAL,
                "TPV ERP",
                "01");

        assertThat(properties.mode()).isEqualTo(VerifactuEndpointMode.TEST_SEAL);
        assertThat(properties.systemName()).isEqualTo("TPV ERP");
        assertThat(properties.systemId()).isEqualTo("01");
    }

    @Test
    void rechazaConfiguracionSinSistema() {
        assertThatThrownBy(() -> new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, null, "01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sistema");
        assertThatThrownBy(() -> new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, "TPV ERP", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sistema");
    }
}
