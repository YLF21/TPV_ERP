package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class FiscalReleaseManifestTest {

    @Test
    void realUsesImmutableReleaseCapabilityEvenWhenEnvironmentOmitsIt() {
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT"),
                manifest("release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY));

        assertThat(runtime.productCapability()).isEqualTo(FiscalProductCapability.VERIFACTU_ONLY);
        assertThat(runtime.systemVersion()).isEqualTo("4.2.0");
    }

    @Test
    void realRejectsCapabilityOverride() {
        assertThatThrownBy(() -> new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.product-capability", "DUAL"),
                manifest("release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no puede alterarse");
    }

    @Test
    void realTestMayKeepWorkerDisabled() {
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.worker-enabled", "false"),
                manifest("release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY));

        assertThat(runtime.workerEnabled()).isFalse();
    }

    @Test
    void realProductionRequiresTheSubmissionWorker() {
        assertThatThrownBy(() -> new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "PRODUCTION")
                .withProperty("tpv.verifactu.endpoint-mode", "PRODUCTION")
                .withProperty("tpv.verifactu.transport-mode", "AEAT")
                .withProperty("tpv.verifactu.production-enabled", "true")
                .withProperty("tpv.verifactu.worker-enabled", "false"),
                manifest("release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worker-enabled=true");
    }

    @Test
    void rejectsAReleaseManifestWithAnIncorrectManifestHash() {
        var values = properties("release-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY);
        values.setProperty("manifest.hash", "0".repeat(64));
        assertThatThrownBy(() -> new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "REAL")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "AEAT"),
                FiscalReleaseManifest.from(values)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hash del manifiesto");
    }

    @Test
    void sandboxUsesTheSameBuildManifestWithoutChangingItsCapability() {
        var runtime = new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"),
                manifest("dev", "DEV", FiscalProductCapability.DUAL));

        assertThat(runtime.productCapability()).isEqualTo(FiscalProductCapability.DUAL);
        assertThat(runtime.releaseManifest().releaseId()).isEqualTo("dev");
    }

    @Test
    void parsesReleaseSequenceAndKeepsBuildSequenceAsMetadata() {
        var older = properties("release-old", "4.2.0", FiscalProductCapability.DUAL);
        older.setProperty("release.sequence", "7");
        older.setProperty("build.sequence", "9");
        var newer = new Properties();
        newer.putAll(older);
        newer.setProperty("release.id", "release-new");
        newer.setProperty("build.sequence", "10");

        var oldManifest = FiscalReleaseManifest.from(older);
        var newManifest = FiscalReleaseManifest.from(newer);

        assertThat(newManifest.releaseSequence()).isEqualTo(7);
        assertThat(newManifest.buildSequence()).isEqualTo(10);
        assertThat(newManifest.compareSequence(oldManifest)).isZero();
    }

    private static FiscalReleaseManifest manifest(
            String releaseId, String version, FiscalProductCapability capability) {
        return FiscalReleaseManifest.from(properties(releaseId, version, capability));
    }

    private static Properties properties(
            String releaseId, String version, FiscalProductCapability capability) {
        var values = new Properties();
        values.setProperty("release.id", releaseId);
        values.setProperty("system.version", version);
        values.setProperty("capability", capability.name());
        values.setProperty("schema.version", "V231");
        return values;
    }
}
