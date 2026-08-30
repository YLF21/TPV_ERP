package com.tpverp.backend.verifactu;

import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the runtime class and release identity in the database. The marker
 * is read under row lock so two HA instances cannot make a different startup
 * decision concurrently.
 */
@Component
public class FiscalRuntimeGuardInitializer implements ApplicationRunner {

    private static final List<String> FISCAL_STATE_TABLES = List.of(
            "cadena_fiscal", "registro_fiscal", "estado_envio_fiscal",
            "intento_envio_fiscal", "cadena_eventos_fiscal", "registro_evento_fiscal",
            "transicion_modo_fiscal", "version_sistema_fiscal", "artefacto_registro_fiscal",
            "snapshot_impresion_fiscal", "alarma_fiscal", "exportacion_fiscal",
            "requerimiento_fiscal", "reloj_operativo_fiscal");

    private static final String MARKER_SQL = "select runtime_class, capacidad_producto, "
            + "release_id, esquema_version, manifest_hash, artifact_hash, commit_hash, "
            + "release_sequence, build_sequence from fiscal_runtime_guard "
            + "where id = 1 for update";
    private static final String SUCCESSFUL_FLYWAY_VERSIONS_SQL =
            "select version from flyway_schema_history "
                    + "where success = true and version is not null";

    private final JdbcTemplate jdbc;
    private final FiscalRuntimeProperties runtime;

    public FiscalRuntimeGuardInitializer(JdbcTemplate jdbc, FiscalRuntimeProperties runtime) {
        this.jdbc = jdbc;
        this.runtime = runtime;
    }

    /** Package-visible value object deliberately avoids delimiter encoding. */
    static record RuntimeMarker(String runtimeClass, String capability, String releaseId,
            String schemaVersion, String manifestHash, String artifactHash, String commitHash,
            long releaseSequence, long buildSequence) {
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var manifest = runtime.releaseManifest();
        requireCurrentSchema(manifest);
        var expected = runtime.runtimeClass().name();
        var expectedCapability = runtime.productCapability().name();
        var marker = jdbc.queryForObject(MARKER_SQL, markerRowMapper());
        if (marker == null) {
            throw new IllegalStateException("El marcador fiscal persistente esta vacio");
        }

        var actualRuntime = value(marker.runtimeClass());
        var actualCapability = valueOr(marker.capability(), "DUAL");
        var actualRelease = releaseValueOr(marker.releaseId(), "LEGACY-V216");
        var actualSchema = valueOr(marker.schemaVersion(), "V216");
        var actualManifest = value(marker.manifestHash());
        var actualArtifact = value(marker.artifactHash());
        var actualCommit = value(marker.commitHash());
        var sameMarker = expected.equals(actualRuntime)
                && expectedCapability.equals(actualCapability)
                && releaseValue(manifest.releaseId()).equals(actualRelease)
                && value(manifest.schemaVersion()).equals(actualSchema)
                && value(manifest.manifestHash()).equals(actualManifest)
                && value(runtime.resolvedArtifactHash()).equals(actualArtifact)
                && value(manifest.commitHash()).equals(actualCommit)
                && manifest.releaseSequence() == marker.releaseSequence()
                && manifest.buildSequence() == marker.buildSequence();
        if (sameMarker) {
            return;
        }

        if (expected.equals(actualRuntime) && expectedCapability.equals(actualCapability)) {
            requireReleaseAdvance(manifest, marker, actualRelease);
            persistMarker(expected, expectedCapability);
            return;
        }

        // A restored REAL marker may be adopted by the isolated laboratory only
        // before any fiscal evidence exists. This is intentionally one-way for
        // non-empty state and does not weaken release identity checks later.
        if (isEmptyFiscalState() && runtime.isSandbox() && "REAL".equals(actualRuntime)) {
            persistMarker(expected, expectedCapability);
            return;
        }
        if (expected.equals(actualRuntime)
                && "DUAL".equals(actualCapability)
                && "VERIFACTU_ONLY".equals(expectedCapability)
                && noNoVerifactuEvidence()) {
            requireReleaseAdvance(manifest, marker, actualRelease);
            persistMarker(expected, expectedCapability);
            return;
        }
        if ("VERIFACTU_ONLY".equals(actualCapability)
                && "DUAL".equals(expectedCapability)) {
            throw new IllegalStateException(
                    "No se puede degradar una base VERIFACTU_ONLY a DUAL");
        }
        throw new IllegalStateException(
                "La base fiscal esta marcada como " + actualRuntime + "/" + actualCapability
                        + "/" + actualRelease + " y el proceso intenta arrancar como "
                        + expected + "/" + expectedCapability
                        + "; se rechaza la restauracion cruzada");
    }

    private void requireReleaseAdvance(FiscalReleaseManifest manifest, RuntimeMarker marker,
            String actualRelease) {
        var expectedRelease = releaseValue(manifest.releaseId());
        var persistedRelease = releaseValue(actualRelease);

        // A release id is an immutable identity, not a channel name. Once it
        // exists, changing any marker field (including build sequence or
        // hashes) is a rejected replacement rather than a new build.
        if (expectedRelease.equals(persistedRelease)) {
            throw new IllegalStateException(
                    "El release activo reutiliza releaseId con otro manifiesto, esquema, "
                            + "artefacto o secuencia");
        }

        // LEGACY markers predate release ordering. They may be adopted once;
        // after persistMarker the marker carries a real release id and this
        // escape hatch is no longer reachable.
        if (persistedRelease.toUpperCase(Locale.ROOT).startsWith("LEGACY-")) {
            return;
        }

        // buildSequence is descriptive metadata inside a new release only. It
        // cannot advance an existing release identity and never participates in
        // ordering between different release ids.
        if (manifest.releaseSequence() <= marker.releaseSequence()) {
            throw new IllegalStateException(
                    "El release fiscal nuevo requiere releaseSequence (release.sequence) "
                            + "estrictamente mayor "
                            + "que el release persistido");
        }
    }

    private boolean isEmptyFiscalState() {
        return FISCAL_STATE_TABLES.stream().noneMatch(this::hasRows);
    }

    private boolean noNoVerifactuEvidence() {
        return !hasQueryRows("select 1 from configuracion_verifactu "
                + "where modo_actual = 'NO_VERIFACTU' limit 1")
                && !hasQueryRows("select 1 from registro_fiscal "
                        + "where modo_fiscal = 'NO_VERIFACTU' limit 1")
                && !hasQueryRows("select 1 from registro_evento_fiscal limit 1");
    }

    private void requireCurrentSchema(FiscalReleaseManifest manifest) {
        var versions = jdbc.queryForList(SUCCESSFUL_FLYWAY_VERSIONS_SQL, String.class);
        if (versions.isEmpty()) {
            throw new IllegalStateException(
                    "No existe ninguna migracion Flyway versionada y exitosa");
        }

        String latestRawVersion = null;
        MigrationVersion latestVersion = null;
        for (var rawVersion : versions) {
            if (rawVersion == null || rawVersion.isBlank()) {
                throw new IllegalStateException(
                        "flyway_schema_history contiene una version vacia");
            }
            final MigrationVersion parsedVersion;
            try {
                parsedVersion = MigrationVersion.fromVersion(rawVersion.trim());
            } catch (RuntimeException exception) {
                throw new IllegalStateException(
                        "flyway_schema_history contiene una version no valida: " + rawVersion,
                        exception);
            }
            if (latestVersion == null || parsedVersion.compareTo(latestVersion) > 0) {
                latestVersion = parsedVersion;
                latestRawVersion = rawVersion.trim();
            }
        }

        var appliedSchema = "V" + latestRawVersion;
        if (!manifest.schemaVersion().equals(appliedSchema)) {
            throw new IllegalStateException(
                    "El schema.version del manifiesto (" + manifest.schemaVersion()
                            + ") no coincide con la ultima migracion Flyway exitosa ("
                            + appliedSchema + ")");
        }
    }

    private void persistMarker(String runtimeClass, String capability) {
        var manifest = runtime.releaseManifest();
        var artifactHash = runtime.resolvedArtifactHash();
        jdbc.update(
                "update fiscal_runtime_guard set runtime_class = ?, capacidad_producto = ?, "
                        + "release_id = ?, esquema_version = ?, manifest_hash = ?, "
                        + "artifact_hash = ?, commit_hash = ?, release_sequence = ?, "
                        + "build_sequence = ?, version = version + 1 where id = 1",
                runtimeClass, capability, manifest.releaseId(), manifest.schemaVersion(),
                manifest.manifestHash(), artifactHash, manifest.commitHash(),
                manifest.releaseSequence(), manifest.buildSequence());
        jdbc.update(
                "insert into fiscal_runtime_release_audit "
                        + "(id, runtime_class, capacidad_producto, release_id, esquema_version, "
                        + "manifest_hash, artifact_hash, commit_hash, release_sequence, "
                        + "build_sequence, observado_en) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp)",
                java.util.UUID.randomUUID(), runtimeClass, capability, manifest.releaseId(),
                manifest.schemaVersion(), manifest.manifestHash(), artifactHash,
                manifest.commitHash(), manifest.releaseSequence(), manifest.buildSequence());
    }

    private boolean hasQueryRows(String existsQuery) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (" + existsQuery + ")", Boolean.class));
    }

    private boolean hasRows(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from " + table + " limit 1)", Boolean.class));
    }

    private static RowMapper<RuntimeMarker> markerRowMapper() {
        return (rs, rowNum) -> new RuntimeMarker(
                rs.getString("runtime_class"), rs.getString("capacidad_producto"),
                rs.getString("release_id"), rs.getString("esquema_version"),
                rs.getString("manifest_hash"), rs.getString("artifact_hash"),
                rs.getString("commit_hash"), longValue(rs, "release_sequence"),
                longValue(rs, "build_sequence"));
    }

    private static long longValue(java.sql.ResultSet resultSet, String column)
            throws java.sql.SQLException {
        var value = resultSet.getLong(column);
        return resultSet.wasNull() ? 0L : value;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value(value);
    }

    private static String releaseValueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String releaseValue(String value) {
        return value == null ? "" : value;
    }

    private static String value(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
