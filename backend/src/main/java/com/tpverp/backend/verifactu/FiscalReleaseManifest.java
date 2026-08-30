package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Immutable release identity packaged inside the backend artifact. */
public record FiscalReleaseManifest(
        String releaseId,
        String systemVersion,
        FiscalProductCapability capability,
        String schemaVersion,
        long releaseSequence,
        long buildSequence,
        String commitHash,
        String declarationHash,
        String manifestHash) {

    public static final String RESOURCE = "META-INF/tpv-erp-release.properties";

    public FiscalReleaseManifest {
        releaseId = required(releaseId, "releaseId");
        systemVersion = required(systemVersion, "systemVersion");
        capability = Objects.requireNonNull(capability, "capability");
        schemaVersion = required(schemaVersion, "schemaVersion");
        if (releaseSequence < 0 || buildSequence < 0) {
            throw new IllegalArgumentException("Las secuencias de release no pueden ser negativas");
        }
        commitHash = optionalCommit(commitHash);
        declarationHash = optionalHash(declarationHash, "declarationHash");
        manifestHash = optionalHash(manifestHash, "manifestHash");
    }

    public static FiscalReleaseManifest load() {
        try (InputStream input = FiscalReleaseManifest.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("No existe el manifiesto de release " + RESOURCE);
            }
            var properties = new Properties();
            properties.load(input);
            return from(properties);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer el manifiesto de release", exception);
        }
    }

    static FiscalReleaseManifest from(Properties properties) {
        var capability = required(properties, "capability");
        try {
            return new FiscalReleaseManifest(
                    required(properties, "release.id"),
                    required(properties, "system.version"),
                    FiscalProductCapability.valueOf(capability.toUpperCase(Locale.ROOT)),
                    required(properties, "schema.version"),
                    sequence(properties, "release.sequence"),
                    sequence(properties, "build.sequence"),
                    properties.getProperty("commit.hash", ""),
                    properties.getProperty("declaration.hash", ""),
                    properties.getProperty("manifest.hash", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("capability no es valida en el manifiesto", exception);
        }
    }

    /** Returns the canonical payload used to calculate a manifest digest. */
    public String canonicalPayload() {
        return "release.id=" + releaseId + "\n"
                + "system.version=" + systemVersion + "\n"
                + "capability=" + capability.name() + "\n"
                + "schema.version=" + schemaVersion + "\n"
                + "release.sequence=" + releaseSequence + "\n"
                + "build.sequence=" + buildSequence + "\n"
                + "commit.hash=" + nullToEmpty(commitHash) + "\n"
                + "declaration.hash=" + nullToEmpty(declarationHash) + "\n";
    }

    public String computedManifestHash() {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalPayload().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }

    public void requireSelfConsistent() {
        if (manifestHash != null && !manifestHash.equalsIgnoreCase(computedManifestHash())) {
            throw new IllegalStateException("El hash del manifiesto de release no coincide");
        }
    }

    private static String required(Properties properties, String key) {
        return required(properties.getProperty(key), key);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return value.trim();
    }

    /**
     * Compatibility constructor for callers compiled against the pre-sidecar
     * manifest shape. Artifact hashes are deliberately rejected here: the
     * digest is calculated over the final JAR and therefore cannot be embedded
     * without becoming self-referential.
     */
    @Deprecated
    public FiscalReleaseManifest(String releaseId, String systemVersion,
            FiscalProductCapability capability, String schemaVersion, String artifactHash,
            String commitHash, String declarationHash, String manifestHash) {
        this(releaseId, systemVersion, capability, schemaVersion, 0, 0, commitHash,
                declarationHash, manifestHash);
        if (artifactHash != null && !artifactHash.isBlank()) {
            throw new IllegalArgumentException(
                    "artifactHash debe calcularse externamente sobre el artefacto final");
        }
    }

    /** Compatibility constructor for manifests created before release ordering existed. */
    public FiscalReleaseManifest(String releaseId, String systemVersion,
            FiscalProductCapability capability, String schemaVersion,
            String commitHash, String declarationHash, String manifestHash) {
        this(releaseId, systemVersion, capability, schemaVersion, 0, 0,
                commitHash, declarationHash, manifestHash);
    }

    public int compareSequence(FiscalReleaseManifest other) {
        // buildSequence is descriptive metadata for a release, never a
        // second ordering axis that could replace an immutable releaseId.
        return Long.compare(releaseSequence, other.releaseSequence);
    }

    private static long sequence(Properties properties, String key) {
        var value = properties.getProperty(key, "0").trim();
        try {
            var parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(key + " debe ser un entero no negativo", exception);
        }
    }

    private static String optionalHash(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalArgumentException(field + " debe ser SHA-256");
        }
        return normalized;
    }

    private static String optionalCommit(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{7,64}")) {
            throw new IllegalArgumentException("commitHash debe ser hexadecimal");
        }
        return normalized;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
