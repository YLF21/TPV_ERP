package com.tpverp.backend.licensing;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

public class LicenseSaasCredentialStore {

    private static final String TOKEN_FILE = "saas-installation-token.dpapi";

    private final Path directory;
    private final SecretProtector protector;

    public LicenseSaasCredentialStore(Path directory, SecretProtector protector) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    public synchronized void writeToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Token SaaS obligatorio");
        }
        Path target = directory.resolve(TOKEN_FILE);
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            byte[] protectedToken = protector.protect(token.trim().getBytes(StandardCharsets.UTF_8));
            temporary = Files.createTempFile(directory, ".saas-token-", ".tmp");
            Files.write(temporary, protectedToken);
            moveAtomically(temporary, target);
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("No se pudo guardar el token SaaS", exception);
        }
    }

    public synchronized Optional<String> readToken() {
        Path target = directory.resolve(TOKEN_FILE);
        if (!Files.exists(target)) {
            return Optional.empty();
        }
        try {
            byte[] token = protector.unprotect(Files.readAllBytes(target));
            String value = new String(token, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer el token SaaS", exception);
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
