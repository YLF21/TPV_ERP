package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.env.MockEnvironment;

class FiscalRuntimeGuardInitializerTest {

    @Test
    void restartWithTheSameReleaseAndArtifactIsAllowedWithoutWriting() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V231", null, null,
                null, 1, 1));
        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1))
                .run(new DefaultApplicationArguments());

        verify(jdbc).queryForList(contains("success = true"), eq(String.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void sameReleaseIdWithDifferentArtifactHashIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V231", null,
                "A".repeat(64), null, 1, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("otro manifiesto");
    }

    @Test
    void sameReleaseIdWithDifferentSchemaIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V230", null, null,
                null, 1, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1, "V231"))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reutiliza releaseId");
    }

    @Test
    void higherReleaseSequenceIsAllowedForANewReleaseId() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 4, 9));
        var initializer = new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.DUAL, 5, 1));

        initializer.run(new DefaultApplicationArguments());

        verifyMarkerUpdate(jdbc, "release-new", 5L, 1L);
    }

    @Test
    void v235MarkerIsUpgradedToTheImmutableV236ReleaseIdentity() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev-v235", "V235", null, null,
                null, 4, 0));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("235", "236"));

        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev-v236", "DEV", FiscalProductCapability.DUAL, 5, 0, "V236"))
                .run(new DefaultApplicationArguments());

        verifyMarkerUpdate(jdbc, "tpv-erp-dev-v236", 5L, 0L);
    }

    @Test
    void v236MarkerIsUpgradedToTheImmutableV237ReleaseIdentity() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev-v236", "V236", null, null,
                null, 5, 0));
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("236", "237"));

        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev-v237", "DEV", FiscalProductCapability.DUAL, 6, 0, "V237"))
                .run(new DefaultApplicationArguments());

        verifyMarkerUpdate(jdbc, "tpv-erp-dev-v237", 6L, 0L);
    }

    @Test
    void v237MarkerIsUpgradedToTheImmutableV238ReleaseIdentity() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev-v237", "V237", null, null,
                null, 6, 0));
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("236", "237", "238"));

        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev-v238", "DEV", FiscalProductCapability.DUAL, 7, 0, "V238"))
                .run(new DefaultApplicationArguments());

        verifyMarkerUpdate(jdbc, "tpv-erp-dev-v238", 7L, 0L);
    }

    @Test
    void newReleaseWithTheSameSequenceIsRejectedEvenWithAHigherBuildSequence() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 5, 99));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.DUAL, 5, 100))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estrictamente mayor");
    }

    @Test
    void newReleaseWithALowerSequenceIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 5, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.DUAL, 4, 999))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estrictamente mayor");
    }

    @Test
    void sameReleaseIdRejectsAReplacementBuildEvenWithAHigherBuildSequence() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-current", "V231", null,
                "A".repeat(64), null, 5, 1));
        var initializer = new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-current", "4.2.0", FiscalProductCapability.DUAL, 5, 2));

        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reutiliza releaseId");
    }

    @Test
    void downgradeReleaseSequenceIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-new", "V231", null, null,
                null, 5, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-old", "4.2.0", FiscalProductCapability.DUAL, 4, 9))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("estrictamente mayor");
    }

    @Test
    void dualToOnlyRequiresNoNoVerifactuEvidence() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 1, 1));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY, 2, 1))
                .run(new DefaultApplicationArguments());

        verifyMarkerUpdate(jdbc, "release-new", 2L, 1L);
    }

    @Test
    void dualToOnlyWithNoVerifactuEvidenceIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 1, 1));
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY, 2, 1))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restauracion cruzada");
    }

    @Test
    void dualToOnlyWithOnlyFiscalEventEvidenceIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "release-old", "V231", null, null,
                null, 1, 1));
        when(jdbc.queryForObject(contains("configuracion_verifactu"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("registro_fiscal"), eq(Boolean.class)))
                .thenReturn(false);
        when(jdbc.queryForObject(contains("registro_evento_fiscal"), eq(Boolean.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-new", "4.2.0", FiscalProductCapability.VERIFACTU_ONLY, 2, 1))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restauracion cruzada");
    }

    @Test
    void rejectsManifestSchemaOlderThanLatestSuccessfulFlywayMigration() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V231", null, null,
                null, 1, 1));
        when(jdbc.queryForList(anyString(), eq(String.class)))
                .thenReturn(List.of("216", "230", "231"));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1, "V230"))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V230")
                .hasMessageContaining("V231");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsManifestSchemaNewerThanLatestSuccessfulFlywayMigration() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V231", null, null,
                null, 1, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1,
                        "V232"))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V232")
                .hasMessageContaining("V231");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void onlyToDualIsRejected() {
        var jdbc = jdbc(marker("SANDBOX", "VERIFACTU_ONLY", "release-new", "V231", null,
                null, null, 2, 1));

        assertThatThrownBy(() -> new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("release-next", "4.2.0", FiscalProductCapability.DUAL, 3, 1))
                .run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("degradar");
    }

    @Test
    void colonInReleaseIdDoesNotBreakTypedMarkerReading() {
        var release = "release:with:colon";
        var jdbc = jdbc(marker("SANDBOX", "DUAL", release, "V231", null, null,
                null, 1, 1));

        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox(release, "DEV", FiscalProductCapability.DUAL, 1, 1))
                .run(new DefaultApplicationArguments());

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void markerQueryLocksTheSingleGuardRowForHaStartupSerialization() {
        var jdbc = jdbc(marker("SANDBOX", "DUAL", "tpv-erp-dev", "V231", null, null,
                null, 1, 1));
        new FiscalRuntimeGuardInitializer(jdbc,
                sandbox("tpv-erp-dev", "DEV", FiscalProductCapability.DUAL, 1, 1))
                .run(new DefaultApplicationArguments());

        verify(jdbc).queryForObject(
                org.mockito.ArgumentMatchers.contains("for update"),
                any(RowMapper.class));
    }

    private static JdbcTemplate jdbc(FiscalRuntimeGuardInitializer.RuntimeMarker marker) {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class))).thenReturn(marker);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("231"));
        return jdbc;
    }

    private static void verifyMarkerUpdate(JdbcTemplate jdbc, String release,
            long releaseSequence, long buildSequence) {
        verify(jdbc).update(org.mockito.ArgumentMatchers.startsWith("update fiscal_runtime_guard"),
                any(Object[].class));
    }

    private static FiscalRuntimeGuardInitializer.RuntimeMarker marker(String runtime,
            String capability, String release, String schema, String manifest, String artifact,
            String commit, long releaseSequence, long buildSequence) {
        return new FiscalRuntimeGuardInitializer.RuntimeMarker(runtime, capability, release, schema,
                manifest, artifact, commit, releaseSequence, buildSequence);
    }

    private static FiscalRuntimeProperties sandbox(String releaseId, String version,
            FiscalProductCapability capability, long releaseSequence, long buildSequence) {
        return sandbox(releaseId, version, capability, releaseSequence, buildSequence, "V231");
    }

    private static FiscalRuntimeProperties sandbox(String releaseId, String version,
            FiscalProductCapability capability, long releaseSequence, long buildSequence,
            String schemaVersion) {
        var values = new Properties();
        values.setProperty("release.id", releaseId);
        values.setProperty("system.version", version);
        values.setProperty("capability", capability.name());
        values.setProperty("schema.version", schemaVersion);
        values.setProperty("release.sequence", Long.toString(releaseSequence));
        values.setProperty("build.sequence", Long.toString(buildSequence));
        return new FiscalRuntimeProperties(new MockEnvironment()
                .withProperty("tpv.verifactu.runtime-class", "SANDBOX")
                .withProperty("tpv.verifactu.dev-sandbox.enabled", "true")
                .withProperty("tpv.verifactu.endpoint-environment", "TEST")
                .withProperty("tpv.verifactu.transport-mode", "SIMULATED"),
                FiscalReleaseManifest.from(values));
    }
}
