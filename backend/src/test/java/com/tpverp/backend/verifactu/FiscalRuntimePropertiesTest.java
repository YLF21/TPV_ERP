package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FiscalRuntimePropertiesTest {

    @Test
    void bloqueaIdentidadDeLaboratorioAlConfigurarProduccionReal() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "TPV ERP DEV")
                .withProperty("tpv.verifactu.producer-tax-id", "B00000000")
                .withProperty("tpv.verifactu.system-version", "0.0.1-SNAPSHOT");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identidad provisional");
    }

    @Test
    void aceptaIdentidadDeclaradaEnProduccionReal() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "Fabricante Fiscal S.L.")
                .withProperty("tpv.verifactu.producer-tax-id", "B12345674")
                .withProperty("tpv.verifactu.system-version", "4.2.7");

        assertThatCode(() -> new FiscalRuntimeProperties(environment))
                .doesNotThrowAnyException();
    }

    @Test
    void permiteElLaboratorioConIdentidadFicticiaPeroSinProduccion() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED")
                .withProperty("tpv.verifactu.producer-name", "TPV ERP DEV")
                .withProperty("tpv.verifactu.producer-tax-id", "B00000000");

        assertThatCode(() -> new FiscalRuntimeProperties(environment))
                .doesNotThrowAnyException();
    }
}
