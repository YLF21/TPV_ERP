package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSystemVersionTest {

    @Test
    void comparesHistoricalLowerCaseDeclarationHashWithoutMutatingIt() throws Exception {
        var version = new FiscalSystemVersion(
                UUID.randomUUID(), UUID.randomUUID(), "B12345674", "TPV ERP SL",
                "TPV ERP", "TPVERP", "4.1.0", "INST-1", "A".repeat(64),
                false, Instant.parse("2026-08-25T10:00:00Z"));
        var field = FiscalSystemVersion.class.getDeclaredField("declarationHash");
        field.setAccessible(true);
        field.set(version, "a".repeat(64));

        assertThat(version.matches(
                "B12345674", "TPV ERP SL", "TPV ERP", "TPVERP", "4.1.0",
                "INST-1", "A".repeat(64), false)).isTrue();
        assertThat(version.getDeclarationHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void releaseIdentityUsesTheResolvedFinalArtifactHashInsteadOfManifestArtifactHash() {
        var artifactHash = "A".repeat(64);
        var manifest = new FiscalReleaseManifest(
                "tpv-erp-4.2.0", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY,
                "V229", "abcdef1", null, null);
        var version = new FiscalSystemVersion(
                UUID.randomUUID(), UUID.randomUUID(), "B12345674", "TPV ERP SL",
                "TPV ERP", "TPVERP", "4.2.0", "INST-1", null,
                false, Instant.parse("2026-08-25T10:00:00Z"), manifest.releaseId(),
                artifactHash, manifest.commitHash(), manifest.capability(),
                manifest.schemaVersion(), manifest.manifestHash());

        assertThat(version.getArtifactHash()).isEqualTo(artifactHash);
        assertThat(version.matchesRelease(manifest, artifactHash)).isTrue();
        assertThat(version.matchesRelease(manifest, "B".repeat(64))).isFalse();
        assertThat(version.matchesRelease(manifest, null)).isFalse();
    }
}
