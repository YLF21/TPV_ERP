package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;

class FiscalRuntimePropertiesTest {

    @Test
    void springCreatesTheRuntimePropertiesWithTheEnvironmentConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(FiscalRuntimeProperties.class);
            context.refresh();

            assertThat(context.getBean(FiscalRuntimeProperties.class)).isNotNull();
        }
    }

    @Test
    void memoizesAnAbsentArtifactHash() {
        var resolutions = new AtomicInteger();
        var runtime = sandboxRuntime(() -> {
            resolutions.incrementAndGet();
            return null;
        });

        assertThat(runtime.resolvedArtifactHash()).isNull();
        assertThat(runtime.resolvedArtifactHash()).isNull();
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void resolvesArtifactHashOnlyOnceAcrossConcurrentCallers() throws Exception {
        var resolutions = new AtomicInteger();
        var start = new CountDownLatch(1);
        var hash = "AB".repeat(32);
        var runtime = sandboxRuntime(() -> {
            resolutions.incrementAndGet();
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrumpido al resolver el hash", exception);
            }
            return hash;
        });
        var executor = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (var index = 0; index < 32; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return runtime.resolvedArtifactHash();
                }));
            }
            start.countDown();
            for (var future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(hash);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(resolutions).hasValue(1);
    }

    @Test
    void artifactHashResolutionFailureRemainsFailClosed() {
        var runtime = sandboxRuntime(() -> {
            throw new IllegalStateException("Sidecar alterado");
        });

        assertThatThrownBy(runtime::resolvedArtifactHash)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Sidecar alterado");
    }

    @Test
    void bloqueaIdentidadDeLaboratorioAlConfigurarProduccionReal() {
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "TPV ERP DEV")
                .withProperty("tpv.verifactu.producer-tax-id", "B00000000")
                .withProperty("tpv.verifactu.system-version", "DEV")
                .withProperty("tpv.verifactu.worker-enabled", "true");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment,
                new FiscalReleaseManifest("dev", "DEV", FiscalProductCapability.VERIFACTU_ONLY,
                        "V231", null, null, null)))
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
                .withProperty("tpv.verifactu.system-name", "TPV ERP")
                .withProperty("tpv.verifactu.system-id", "01")
                .withProperty("tpv.verifactu.system-version", "4.2.0")
                .withProperty("tpv.verifactu.declaration-hash", "ab".repeat(32))
                .withProperty("tpv.verifactu.worker-enabled", "true");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment,
                new FiscalReleaseManifest("release-4.2.0", "4.2.0",
                        FiscalProductCapability.VERIFACTU_ONLY, "V231", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifiesto");
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
                .withProperty("tpv.verifactu.system-name", "TPV ERP")
                .withProperty("tpv.verifactu.system-id", "01")
                .withProperty("tpv.verifactu.system-version", "4.2.0")
                .withProperty("tpv.verifactu.worker-enabled", "true");

        assertThatThrownBy(() -> new FiscalRuntimeProperties(environment,
                new FiscalReleaseManifest("release-4.2.0", "4.2.0",
                        FiscalProductCapability.VERIFACTU_ONLY, "V231", null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifiesto");
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
    void aeatTestCandidateReachesSidecarCheckOnlyWithCompleteReleaseIdentity() {
        var declarationHash = "AB".repeat(32);
        var unsigned = new FiscalReleaseManifest(
                "release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY,
                "V231", "abcdef1", declarationHash, null);
        var manifest = new FiscalReleaseManifest(
                "release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY,
                "V231", "abcdef1", declarationHash, unsigned.computedManifestHash());
        var environment = new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.aeat-test-network-enabled", "true")
                .withProperty("tpv.verifactu.producer-name", "Fabricante Fiscal S.L.")
                .withProperty("tpv.verifactu.producer-tax-id", "B12345674")
                .withProperty("tpv.verifactu.system-name", "TPV ERP")
                .withProperty("tpv.verifactu.system-id", "01")
                .withProperty("tpv.verifactu.system-version", "4.2.0")
                .withProperty("tpv.verifactu.declaration-hash", declarationHash);

        var runtime = new FiscalRuntimeProperties(environment, manifest);

        assertThatThrownBy(runtime::requireAeatTestReleaseCandidate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sidecar");
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

    private static FiscalRuntimeProperties sandboxRuntime(
            java.util.function.Supplier<String> artifactHashResolver) {
        var values = new Properties();
        values.setProperty("release.id", "tpv-erp-dev");
        values.setProperty("system.version", "DEV");
        values.setProperty("capability", "DUAL");
        values.setProperty("schema.version", "V231");
        return new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"),
                FiscalReleaseManifest.from(values), artifactHashResolver);
    }
}
