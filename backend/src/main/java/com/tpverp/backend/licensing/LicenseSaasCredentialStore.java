package com.tpverp.backend.licensing;

import com.tpverp.backend.shared.crypto.SecretProtector;
import com.tpverp.backend.verifactu.TransferSecretDirectory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public class LicenseSaasCredentialStore {

    private static final String TOKEN_FILE = "saas-installation-token.dpapi";
    private static final String LINK_RECOVERY_TOKEN_FILE = "saas-link-recovery-token.dpapi";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path directory;
    private final SecretProtector protector;
    private final String serviceAccount;

    /** Portable callers retain their existing filesystem contract. */
    public LicenseSaasCredentialStore(Path directory, SecretProtector protector) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
        this.serviceAccount = null;
    }

    /** Strict NTFS uses the configured runtime account, including maintenance run by an administrator. */
    public LicenseSaasCredentialStore(Path directory, SecretProtector protector, String serviceAccount) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
        if (!com.sun.jna.Platform.isWindows()) {
            throw new IllegalStateException("La ACL STRICT de credenciales SaaS requiere Windows y NTFS");
        }
        if (serviceAccount == null || serviceAccount.isBlank()) {
            throw new IllegalArgumentException("La cuenta Windows del backend es obligatoria");
        }
        this.serviceAccount = serviceAccount.trim();
    }

    public synchronized void writeToken(String token) {
        writeSecret(TOKEN_FILE, token, "Token SaaS obligatorio",
                "No se pudo guardar el token SaaS");
    }

    public synchronized Optional<String> readToken() {
        return readSecret(TOKEN_FILE, "No se pudo leer el token SaaS");
    }

    /**
     * Returns the durable one-time-link recovery credential, creating it before
     * any network request when none exists. The 32 random bytes provide 256 bits
     * of entropy and are protected with the same configured protector as the
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
            String value,
            String requiredMessage,
            String failureMessage) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(requiredMessage);
        }
        byte[] plaintext = value.trim().getBytes(StandardCharsets.UTF_8);
        byte[] protectedToken = null;
        try {
            // The shared policy verifies an existing root and target, protects the
            // empty temporary before writing, then verifies the atomic replacement.
            // Its account is the runtime identity supplied by the installed configuration.
            var secrets = serviceAccount == null ? null : secretDirectory();
            protectedToken = protector.protect(plaintext);
            if (secrets != null) {
                secrets.write(fileName, protectedToken);
            } else {
                writePortable(fileName, protectedToken);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(failureMessage, exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (protectedToken != null) {
                Arrays.fill(protectedToken, (byte) 0);
            }
        }
    }

    private Optional<String> readSecret(String fileName, String failureMessage) {
        Path target = directory.resolve(fileName);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        byte[] token = null;
        try {
            token = protector.unprotect(Files.readAllBytes(target));
            String value = new String(token, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException exception) {
            throw new IllegalStateException(failureMessage, exception);
        } finally {
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private TransferSecretDirectory secretDirectory() {
        return new TransferSecretDirectory(directory, serviceAccount);
    }

    private void writePortable(String fileName, byte[] bytes) throws IOException {
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".saas-token-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Path target = directory.resolve(fileName);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
