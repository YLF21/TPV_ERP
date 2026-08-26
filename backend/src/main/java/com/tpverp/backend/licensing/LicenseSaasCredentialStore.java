package com.tpverp.backend.licensing;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

public class LicenseSaasCredentialStore {

    private static final String TOKEN_FILE = "saas-installation-token.dpapi";
    private static final String LINK_RECOVERY_TOKEN_FILE = "saas-link-recovery-token.dpapi";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path directory;
    private final SecretProtector protector;

    public LicenseSaasCredentialStore(Path directory, SecretProtector protector) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    public synchronized void writeToken(String token) {
        writeSecret(TOKEN_FILE, ".saas-token-", token, "Token SaaS obligatorio",
                "No se pudo guardar el token SaaS");
    }

    public synchronized Optional<String> readToken() {
        return readSecret(TOKEN_FILE, "No se pudo leer el token SaaS");
    }

    /**
     * Returns the durable one-time-link recovery credential, creating it before
     * any network request when none exists. The 32 random bytes provide 256 bits
     * of entropy and are protected with the same machine-bound protector as the
     * installation credential.
     */
    public synchronized String getOrCreateLinkRecoveryToken() {
        Optional<String> existing = readLinkRecoveryToken();
        if (existing.isPresent()) {
            return existing.get();
        }
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        writeSecret(
                LINK_RECOVERY_TOKEN_FILE,
                ".saas-link-recovery-",
                token,
                "Token de recuperacion de enlace obligatorio",
                "No se pudo guardar el token de recuperacion de enlace SaaS");
        return token;
    }

    public synchronized Optional<String> readLinkRecoveryToken() {
        return readSecret(
                LINK_RECOVERY_TOKEN_FILE,
                "No se pudo leer el token de recuperacion de enlace SaaS");
    }

    public synchronized void clearLinkRecoveryToken() {
        try {
            Files.deleteIfExists(directory.resolve(LINK_RECOVERY_TOKEN_FILE));
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "No se pudo eliminar el token de recuperacion de enlace SaaS", exception);
        }
    }

    private void writeSecret(
            String fileName,
            String temporaryPrefix,
            String value,
            String requiredMessage,
            String failureMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        Path target = directory.resolve(fileName);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            byte[] protectedToken = protector.protect(value.trim().getBytes(StandardCharsets.UTF_8));
            temporary = Files.createTempFile(directory, temporaryPrefix, ".tmp");
            Files.write(temporary, protectedToken);
            moveAtomically(temporary, target);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    private Optional<String> readSecret(String fileName, String failureMessage) {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] token = protector.unprotect(Files.readAllBytes(target));
            String value = new String(token, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException exception) {
            throw new IllegalStateException(failureMessage, exception);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Best effort cleanup for failed atomic writes.
            }
        }
    }
}
