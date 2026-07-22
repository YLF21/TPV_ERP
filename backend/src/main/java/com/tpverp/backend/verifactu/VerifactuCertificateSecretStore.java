package com.tpverp.backend.verifactu;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import com.sun.jna.Platform;

public class VerifactuCertificateSecretStore {

    private static final String PRIVATE_KEY_FILE = "private-key.dpapi";

    private final Path root;
    private final SecretProtector protector;
    private final SecretDirectoryAccessPolicy accessPolicy;

    public VerifactuCertificateSecretStore(Path root, SecretProtector protector) {
        this(root, protector, defaultAccessPolicy());
    }

    VerifactuCertificateSecretStore(
            Path root,
            SecretProtector protector,
            SecretDirectoryAccessPolicy accessPolicy) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.protector = Objects.requireNonNull(protector, "protector");
        this.accessPolicy = Objects.requireNonNull(accessPolicy, "accessPolicy");
        SecretPathSafety.rejectLinksAndReparsePoints(this.root);
        this.accessPolicy.prepareRoot(this.root);
        SecretPathSafety.rejectLinksAndReparsePoints(this.root);
        this.accessPolicy.verifyDirectory(this.root);
    }

    // Protege, verifica y publica la clave mediante un movimiento atomico local.
    public String write(UUID companyId, UUID certificateId, byte[] privateKey) {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(certificateId, "certificateId");
        if (privateKey == null || privateKey.length == 0) {
            throw new IllegalArgumentException("La clave privada es obligatoria");
        }
        var target = resolve(companyId + "/" + certificateId + "/" + PRIVATE_KEY_FILE);
        byte[] encrypted = null;
        byte[] verified = null;
        Path temporary = null;
        try {
            prepareSecretDirectory(target.getParent());
            encrypted = protector.protect(privateKey.clone());
            verified = protector.unprotect(encrypted);
            if (!MessageDigest.isEqual(privateKey, verified)) {
                throw new IllegalStateException("No se pudo verificar la clave protegida");
            }
            temporary = Files.createTempFile(target.getParent(), ".private-key-", ".tmp");
            accessPolicy.secureFile(temporary);
            Files.write(temporary, encrypted);
            accessPolicy.verifyFile(temporary);
            moveAtomically(temporary, target);
            accessPolicy.secureFile(target);
            verifySecretPath(target);
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
        var target = resolve(relativePath);
        verifySecretPath(target);
        try {
            return protector.unprotect(readAllBytesWithoutFollowingLinks(target));
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo leer la clave privada", exception);
        }
    }

    public void delete(String relativePath) {
        var target = resolve(relativePath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        verifySecretPath(target);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo eliminar la clave privada", exception);
        }
    }

    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Ruta de certificado obligatoria");
        }
        var relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("La ruta de certificado debe ser relativa");
        }
        for (var part : relative) {
            if (part.toString().equals("..") || part.toString().equals(".")) {
                throw new IllegalArgumentException("Ruta de certificado fuera del directorio seguro");
            }
        }
        validateSchema(relative);
        var resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Ruta de certificado fuera del directorio seguro");
        }
        SecretPathSafety.rejectLinksAndReparsePoints(resolved);
        return resolved;
    }

    private static void validateSchema(Path relative) {
        if (relative.getNameCount() != 3
                || !PRIVATE_KEY_FILE.equals(relative.getFileName().toString())) {
            throw new IllegalArgumentException("Esquema de ruta de certificado no valido");
        }
        validateCanonicalUuid(relative.getName(0).toString());
        validateCanonicalUuid(relative.getName(1).toString());
    }

    private static void validateCanonicalUuid(String value) {
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("UUID no canonico");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Esquema de ruta de certificado no valido", exception);
        }
    }

    private void prepareSecretDirectory(Path certificateDirectory) throws IOException {
        accessPolicy.verifyDirectory(root);
        var companyDirectory = certificateDirectory.getParent();
        prepareDirectory(companyDirectory);
        prepareDirectory(certificateDirectory);
    }

    private void prepareDirectory(Path directory) throws IOException {
        SecretPathSafety.rejectLinksAndReparsePoints(directory);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            accessPolicy.verifyDirectory(directory);
            return;
        }
        Files.createDirectory(directory);
        SecretPathSafety.rejectLinksAndReparsePoints(directory);
        accessPolicy.secureDirectory(directory);
    }

    private void verifySecretPath(Path target) {
        SecretPathSafety.rejectLinksAndReparsePoints(target);
        accessPolicy.verifyDirectory(root);
        accessPolicy.verifyDirectory(target.getParent().getParent());
        accessPolicy.verifyDirectory(target.getParent());
        accessPolicy.verifyFile(target);
    }

    private static byte[] readAllBytesWithoutFollowingLinks(Path target) throws IOException {
        try (var channel = Files.newByteChannel(
                target,
                java.util.Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = Channels.newInputStream(channel)) {
            return input.readAllBytes();
        }
    }

    static SecretDirectoryAccessPolicy defaultAccessPolicy() {
        return Platform.isWindows()
                ? WindowsNtfsSecretDirectoryAccessPolicy.forCurrentAccount()
                : new PortableSecretDirectoryAccessPolicy();
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
