package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FiscalRuntimePropertiesTest {

    @Test
    void bloqueaIdentidadDeLaboratorioAlConfigurarProduccionReal() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
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
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "Fabricante Fiscal S.L.")
                .withProperty("tpv.verifactu.producer-tax-id", "B12345674")
                .withProperty("tpv.verifactu.system-version", "4.2.7")
                .withProperty("tpv.verifactu.declaration-hash", "ab".repeat(32));

        var runtime = new FiscalRuntimeProperties(environment);

        assertThat(runtime.declarationHash()).isEqualTo("AB".repeat(32));
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

    @Test
    void bloqueaAeatTestSinOptInDeRed() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT");

        var runtime = new FiscalRuntimeProperties(environment);

        assertThatThrownBy(runtime::requireAeatTestNetwork)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("opt-in");
    }

    @Test
    void bloqueaProduccionRealSinHashDeDeclaracionResponsable() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "Fabricante Fiscal S.L.")
                .withProperty("tpv.verifactu.producer-tax-id", "B12345674")
                .withProperty("tpv.verifactu.system-version", "4.2.7");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declaration-hash es obligatorio");
    }

    @Test
    void rechazaHashDeDeclaracionMalformadoTambienEnTest() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.declaration-hash", "no-es-sha256");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHA-256 de 64 hexadecimales");
    }

    @Test
    void bloqueaSandboxConEndpointDeProduccion() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SANDBOX nunca puede usar endpoints de produccion");
    }

    @Test
    void bloqueaRealConTransporteSimulado() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REAL nunca puede usar transporte simulado");
    }

    @Test
    void bloqueaModoProduccionCuandoElEntornoDeclaradoEsTest() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.transport-mode", "AEAT");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no coincide");
    }

    @Test
    void bloqueaModoTestCuandoElEntornoDeclaradoEsProduccion() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "TEST")
                .withProperty("tpv.verifactu.production-enabled", "true");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no coincide");
    }
}
