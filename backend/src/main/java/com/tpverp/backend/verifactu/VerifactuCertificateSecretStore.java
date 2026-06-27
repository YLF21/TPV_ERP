package com.tpverp.backend.verifactu;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public class VerifactuCertificateSecretStore {

    private static final String PRIVATE_KEY_FILE = "private-key.dpapi";

    private final Path root;
    private final SecretProtector protector;

    public VerifactuCertificateSecretStore(Path root, SecretProtector protector) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
    }

    // Protege, verifica y publica la clave mediante un movimiento atomico local.
    public String write(UUID companyId, UUID certificateId, byte[] privateKey) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(certificateId, "certificateId");
        if (privateKey == null || privateKey.length == 0) {
            throw new IllegalArgumentException("La clave privada es obligatoria");
        }
        var relative = Path.of(companyId.toString(), certificateId.toString(), PRIVATE_KEY_FILE);
        var target = resolve(relative.toString());
        byte[] encrypted = null;
        byte[] verified = null;
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            encrypted = protector.protect(privateKey.clone());
            verified = protector.unprotect(encrypted);
            if (!MessageDigest.isEqual(privateKey, verified)) {
                throw new IllegalStateException("No se pudo verificar la clave protegida");
            }
            temporary = Files.createTempFile(target.getParent(), ".private-key-", ".tmp");
            Files.write(temporary, encrypted);
            moveAtomically(temporary, target);
            return root.relativize(target).toString().replace('\\', '/');
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new IllegalStateException("No se pudo guardar la clave privada", exception);
        } finally {
            clear(encrypted);
            clear(verified);
        }
    }

    public byte[] read(String relativePath) {
        try {
            return protector.unprotect(Files.readAllBytes(resolve(relativePath)));
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer la clave privada", exception);
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo eliminar la clave privada", exception);
        }
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Ruta de certificado obligatoria");
        }
        var relative = Path.of(relativePath);
        var resolved = relative.isAbsolute() ? relative.normalize() : root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Ruta de certificado fuera del directorio seguro");
        }
        return resolved;
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
                // The temporary file will be cleaned up during the next directory maintenance.
            }
        }
    }

    private static void clear(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }
}
