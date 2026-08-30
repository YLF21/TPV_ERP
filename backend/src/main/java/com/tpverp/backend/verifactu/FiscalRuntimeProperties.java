package com.tpverp.backend.verifactu;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Runtime fiscal boundary. Spring profiles are not treated as fiscal
 * authorization; the explicit properties below are the source of truth.
 */
@Component
public class FiscalRuntimeProperties {

    private static final Object ARTIFACT_HASH_UNRESOLVED = new Object();
    private static final Object ARTIFACT_HASH_ABSENT = new Object();

    private final FiscalRuntimeClass runtimeClass;
    private final FiscalEndpointEnvironment endpointEnvironment;
    private final VerifactuEndpointMode endpointMode;
    private final FiscalTransportMode transportMode;
    private final boolean sandboxEnabled;
    private final boolean aeatTestNetworkEnabled;
    private final boolean productionEnabled;
    private final boolean workerEnabled;
    private final FiscalMode sandboxInitialMode;
    private final String devSigningPkcs12;
    private final String devSigningPassword;
    private final String producerName;
    private final String producerTaxId;
    private final String systemName;
    private final String systemId;
    private final String systemVersion;
    private final String declarationHash;
    private final FiscalReleaseManifest releaseManifest;
    private final FiscalProductCapability productCapability;
    private final Supplier<String> artifactHashResolver;
    private final Object artifactHashResolutionLock = new Object();
    private volatile Object artifactHashResolution = ARTIFACT_HASH_UNRESOLVED;

    @Autowired
    public FiscalRuntimeProperties(Environment environment) {
        this(environment, FiscalReleaseManifest.load());
    }

    FiscalRuntimeProperties(Environment environment, FiscalReleaseManifest releaseManifest) {
        this(environment, releaseManifest,
                FiscalRuntimeProperties::resolveArtifactHashFromCodeSource);
    }

    FiscalRuntimeProperties(Environment environment, FiscalReleaseManifest releaseManifest,
            Supplier<String> artifactHashResolver) {
        var productionManifest = java.util.Objects.requireNonNull(releaseManifest, "releaseManifest");
        productionManifest.requireSelfConsistent();
        this.artifactHashResolver = java.util.Objects.requireNonNull(
                artifactHashResolver, "artifactHashResolver");
        runtimeClass = enumValue(environment, "tpv.verifactu.runtime-class",
                FiscalRuntimeClass.REAL);
        this.releaseManifest = productionManifest;
        endpointEnvironment = enumValue(environment, "tpv.verifactu.endpoint-environment",
                FiscalEndpointEnvironment.TEST);
        endpointMode = enumValue(environment, "tpv.verifactu.endpoint-mode",
                VerifactuEndpointMode.TEST);
        transportMode = enumValue(environment, "tpv.verifactu.transport-mode",
                FiscalTransportMode.AEAT);
        sandboxEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.dev-sandbox.enabled", "false"));
        aeatTestNetworkEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.aeat-test-network-enabled", "false"));
        productionEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.production-enabled", "false"));
        workerEnabled = Boolean.parseBoolean(environment.getProperty(
                "tpv.verifactu.worker-enabled", "false"));
        sandboxInitialMode = enumValue(environment, "tpv.verifactu.dev-initial-mode",
                FiscalMode.VERIFACTU);
        devSigningPkcs12 = environment.getProperty("tpv.verifactu.dev-signing-pkcs12", "");
        devSigningPassword = environment.getProperty("tpv.verifactu.dev-signing-password", "");
        producerName = environment.getProperty("tpv.verifactu.producer-name", "");
        producerTaxId = environment.getProperty("tpv.verifactu.producer-tax-id", "");
        systemName = environment.getProperty("tpv.verifactu.system-name", "");
        systemId = environment.getProperty("tpv.verifactu.system-id", "");
        var configuredVersion = environment.getProperty("tpv.verifactu.system-version", "");
        if (!configuredVersion.isBlank()
                && !productionManifest.systemVersion().equals(configuredVersion.trim())) {
            throw new IllegalStateException(
                    "tpv.verifactu.system-version no coincide con el manifiesto de release");
        }
        systemVersion = productionManifest.systemVersion();
        declarationHash = normalizeHash(environment.getProperty(
                "tpv.verifactu.declaration-hash", productionManifest.declarationHash() == null
                        ? "" : productionManifest.declarationHash()));
        productCapability = capability(environment);
        validate();
    }

    public FiscalRuntimeClass runtimeClass() {
        return runtimeClass;
    }

    public FiscalEndpointEnvironment endpointEnvironment() {
        return endpointEnvironment;
    }

    public VerifactuEndpointMode endpointMode() {
        return endpointMode;
    }

    public FiscalTransportMode transportMode() {
        return transportMode;
    }

    public boolean sandboxEnabled() {
        return sandboxEnabled;
    }

    public boolean aeatTestNetworkEnabled() {
        return aeatTestNetworkEnabled;
    }

    public boolean productionEnabled() {
        return productionEnabled;
    }

    public boolean workerEnabled() {
        return workerEnabled;
    }

    public boolean isSandbox() {
        return runtimeClass == FiscalRuntimeClass.SANDBOX;
    }

    public boolean isAeatTest() {
        return endpointEnvironment == FiscalEndpointEnvironment.TEST
                && transportMode == FiscalTransportMode.AEAT;
    }

    public FiscalMode sandboxInitialMode() {
        return sandboxInitialMode;
    }

    public String devSigningPkcs12() {
        return devSigningPkcs12;
    }

    public String devSigningPassword() {
        return devSigningPassword;
    }

    public String declarationHash() {
        return declarationHash;
    }

    public String producerName() {
        return producerName;
    }

    public String producerTaxId() {
        return producerTaxId;
    }

    public String systemName() {
        return systemName;
    }

    public String systemId() {
        return systemId;
    }

    public FiscalReleaseManifest releaseManifest() {
        return releaseManifest;
    }

    public FiscalProductCapability productCapability() {
        return productCapability;
    }

    public String systemVersion() {
        return systemVersion;
    }

    /**
     * Resolves the hash of the running CodeSource from its adjacent sidecar.
     * The sidecar is produced after the JAR has been closed, so the hash cannot
     * accidentally become self-referential through the embedded manifest.
     */
    public String resolvedArtifactHash() {
        var cached = artifactHashResolution;
        if (cached != ARTIFACT_HASH_UNRESOLVED) {
            return cached == ARTIFACT_HASH_ABSENT ? null : (String) cached;
        }
        synchronized (artifactHashResolutionLock) {
            cached = artifactHashResolution;
            if (cached == ARTIFACT_HASH_UNRESOLVED) {
                var resolved = artifactHashResolver.get();
                cached = resolved == null ? ARTIFACT_HASH_ABSENT : resolved;
                artifactHashResolution = cached;
            }
        }
        return cached == ARTIFACT_HASH_ABSENT ? null : (String) cached;
    }

    private static String resolveArtifactHashFromCodeSource() {
        try {
            var source = Path.of(FiscalRuntimeProperties.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            var sidecar = Files.isDirectory(source)
                    ? source.resolve("tpv-erp-release.sha256")
                    : Path.of(source.toString() + ".sha256");
            if (!Files.isRegularFile(sidecar)) {
                return null;
            }
            var expected = Files.readString(sidecar, StandardCharsets.UTF_8).trim()
                    .split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            if (!expected.matches("[0-9A-F]{64}")) {
                throw new IllegalStateException("El sidecar de artifact hash no es SHA-256");
            }
            var actual = codeSourceHash(source, sidecar);
            if (!actual.equals(expected)) {
                throw new IllegalStateException("El SHA-256 del CodeSource no coincide con su sidecar");
            }
            return actual;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo verificar el SHA-256 del CodeSource", exception);
        }
    }

    private static String codeSourceHash(Path source, Path sidecar) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        if (Files.isDirectory(source)) {
            try (var paths = Files.walk(source)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !path.equals(sidecar))
                        .sorted()
                        .forEach(path -> {
                            try {
                                digest.update(source.relativize(path).toString()
                                        .replace('\\', '/').getBytes(StandardCharsets.UTF_8));
                                digest.update((byte) 0);
                                digest.update(Files.readAllBytes(path));
                                digest.update((byte) 0);
                            } catch (Exception exception) {
                                throw new HashingRuntimeException(exception);
                            }
                        });
            } catch (HashingRuntimeException exception) {
                throw exception.getCause();
            }
        } else {
            digest.update(Files.readAllBytes(source));
        }
        return HexFormat.of().withUpperCase().formatHex(digest.digest());
    }

    private static final class HashingRuntimeException extends RuntimeException {
        private HashingRuntimeException(Exception cause) { super(cause); }
        @Override public synchronized Exception getCause() {
            return (Exception) super.getCause();
        }
    }

    /** Verifies that the effective runtime still belongs to the packaged release. */
    public void requireReleaseBinding() {
        if (!releaseManifest.systemVersion().equals(systemVersion)) {
            throw new IllegalStateException(
                    "tpv.verifactu.system-version no coincide con el manifiesto de release");
        }
        if (productCapability != releaseManifest.capability()) {
            throw new IllegalStateException(
                    "La capacidad fiscal configurada no coincide con el manifiesto de release");
        }
    }

    /**
     * REAL production can only be enabled after replacing the clearly fictitious
     * laboratory identity with the declared fiscal software identity.
     */
    public void requireProductionIdentity() {
        if (runtimeClass != FiscalRuntimeClass.REAL
                || endpointEnvironment != FiscalEndpointEnvironment.PRODUCTION) {
            return;
        }
        requirePromotableRelease("REAL/PRODUCTION");
    }

    /**
     * Preflight common to the isolated AEAT TEST promotion check and REAL
     * production. It verifies release identity, declared software identity and
     * the digest of the final CodeSource without depending on Spring profiles.
     */
    public void requireAeatTestReleaseCandidate() {
        requirePromotableRelease("AEAT TEST");
    }

    private void requirePromotableRelease(String context) {
        if (productCapability != FiscalProductCapability.VERIFACTU_ONLY) {
            throw new IllegalStateException(
                    context + " requiere capability VERIFACTU_ONLY");
        }
        rejectPlaceholder("tpv.verifactu.producer-name", producerName,
                value -> value.toUpperCase(Locale.ROOT).contains("DEV")
                        || value.toUpperCase(Locale.ROOT).contains("TEST")
                        || value.toUpperCase(Locale.ROOT).contains("PLACEHOLDER"));
        rejectPlaceholder("tpv.verifactu.producer-tax-id", producerTaxId,
                value -> value.equalsIgnoreCase("B00000000")
                        || value.equalsIgnoreCase("00000000T"));
        rejectPlaceholder("tpv.verifactu.system-name", systemName,
                value -> value.toUpperCase(Locale.ROOT).contains("DEV")
                        || value.toUpperCase(Locale.ROOT).contains("TEST")
                        || value.toUpperCase(Locale.ROOT).contains("PLACEHOLDER"));
        rejectPlaceholder("tpv.verifactu.system-id", systemId,
                value -> value.toUpperCase(Locale.ROOT).contains("DEV")
                        || value.toUpperCase(Locale.ROOT).contains("TEST")
                        || value.toUpperCase(Locale.ROOT).contains("PLACEHOLDER"));
        rejectPlaceholder("tpv.verifactu.system-version", systemVersion,
                value -> value.equalsIgnoreCase("DEV")
                        || value.equalsIgnoreCase("0.0.1")
                        || value.toUpperCase(Locale.ROOT).contains("SNAPSHOT")
                        || value.equalsIgnoreCase("4.1.0"));
        requireReleaseBinding();
        if (releaseManifest.commitHash() == null
                || releaseManifest.manifestHash() == null
                || releaseManifest.declarationHash() == null) {
            throw new IllegalStateException(
                    "El manifiesto de release no contiene hashes completos para " + context);
        }
        if (!releaseManifest.declarationHash().equals(declarationHash)) {
            throw new IllegalStateException(
                    "La declaracion responsable no coincide con el manifiesto de release");
        }
        if (resolvedArtifactHash() == null) {
            throw new IllegalStateException(
                    "No existe sidecar verificable para el SHA-256 del CodeSource");
        }
    }

    private void validate() {
        var modeEnvironment = switch (endpointMode) {
            case TEST, TEST_SEAL -> FiscalEndpointEnvironment.TEST;
            case PRODUCTION, PRODUCTION_SEAL -> FiscalEndpointEnvironment.PRODUCTION;
        };
        if (endpointEnvironment != modeEnvironment) {
            throw new IllegalStateException(
                    "tpv.verifactu.endpoint-mode " + endpointMode
                            + " no coincide con endpoint-environment " + endpointEnvironment);
        }
        if (runtimeClass == FiscalRuntimeClass.SANDBOX && !sandboxEnabled) {
            throw new IllegalStateException(
                    "SANDBOX fiscal requiere tpv.verifactu.dev-sandbox.enabled=true");
        }
        if (runtimeClass == FiscalRuntimeClass.SANDBOX
                && endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION) {
            throw new IllegalStateException("SANDBOX nunca puede usar endpoints de produccion");
        }
        if (endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION && !productionEnabled) {
            throw new IllegalStateException(
                    "PRODUCTION permanece bloqueado hasta superar la validacion fiscal final");
        }
        if (runtimeClass == FiscalRuntimeClass.REAL
                && endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION
                && !workerEnabled) {
            throw new IllegalStateException(
                    "REAL/PRODUCTION requiere tpv.verifactu.worker-enabled=true");
        }
        if (runtimeClass == FiscalRuntimeClass.REAL
                && transportMode == FiscalTransportMode.SIMULATED) {
            throw new IllegalStateException("REAL nunca puede usar transporte simulado");
        }
        if (isAeatTest() && runtimeClass == FiscalRuntimeClass.SANDBOX
                && !aeatTestNetworkEnabled) {
            throw new IllegalStateException(
                    "AEAT TEST requiere opt-in de red explicito");
        }
        if (runtimeClass == FiscalRuntimeClass.REAL
                && endpointEnvironment == FiscalEndpointEnvironment.PRODUCTION) {
            requireProductionIdentity();
        }
    }

    private FiscalProductCapability capability(Environment environment) {
        var configured = environment.getProperty("tpv.verifactu.product-capability", "");
        if (!configured.isBlank()) {
            var value = parseCapability(configured);
            if (value != releaseManifest.capability()) {
                throw new IllegalStateException(
                        "La capacidad fiscal no puede alterarse por entorno ni perfil");
            }
        }
        return releaseManifest.capability();
    }

    private static FiscalProductCapability parseCapability(String value) {
        try {
            return FiscalProductCapability.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "tpv.verifactu.product-capability no es valida: " + value, exception);
        }
    }

    private static void rejectPlaceholder(String key, String value,
            java.util.function.Predicate<String> predicate) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || predicate.test(normalized)) {
            throw new IllegalStateException(
                    key + " contiene una identidad provisional; se bloquea REAL/PRODUCTION");
        }
    }

    private static String normalizeHash(String value) {
        var normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return null;
        }
        if (!normalized.matches("[0-9A-F]{64}")) {
            throw new IllegalStateException(
                    "tpv.verifactu.declaration-hash debe ser un SHA-256 de 64 hexadecimales");
        }
        return normalized;
    }

    /** Blocks any certificate-backed AEAT TEST request until explicitly enabled. */
    public void requireAeatTestNetwork() {
        if (isAeatTest() && !aeatTestNetworkEnabled) {
            throw new IllegalStateException(
                    "AEAT TEST requiere opt-in de red explicito");
        }
    }

    private static <T extends Enum<T>> T enumValue(
            Environment environment, String key, T defaultValue) {
        var value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(defaultValue.getDeclaringClass(), value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(key + " no es valido: " + value, exception);
        }
    }
}
