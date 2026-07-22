package com.tpverp.backend.verifactu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class PortableSecretDirectoryAccessPolicy implements SecretDirectoryAccessPolicy {

    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);

    @Override
    public void prepareRoot(Path root) {
        try {
            Files.createDirectories(root);
            secureDirectory(root);
        } catch (IOException exception) {
            throw failure("No se pudo preparar el directorio de secretos VERI*FACTU", exception);
        }
    }

    @Override
    public void secureDirectory(Path directory) {
        applyPosixPermissions(directory, DIRECTORY_PERMISSIONS);
        verifyDirectory(directory);
    }

    @Override
    public void secureFile(Path file) {
        applyPosixPermissions(file, FILE_PERMISSIONS);
        verifyFile(file);
    }

    @Override
    public void verifyDirectory(Path directory) {
        verifyType(directory, true);
        verifyPosixPermissions(directory, DIRECTORY_PERMISSIONS);
    }

    @Override
    public void verifyFile(Path file) {
        verifyType(file, false);
        verifyPosixPermissions(file, FILE_PERMISSIONS);
    }

    private static void applyPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                Files.setPosixFilePermissions(path, permissions);
            } catch (IOException exception) {
                throw failure("No se pudieron proteger los permisos del secreto VERI*FACTU", exception);
            }
        }
    }

    private static void verifyPosixPermissions(Path path, Set<PosixFilePermission> expected) {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try {
                var actual = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
                if (!actual.equals(expected)) {
                    throw failure("Los permisos del secreto VERI*FACTU no son seguros", null);
                }
            } catch (IOException exception) {
                throw failure("No se pudieron verificar los permisos del secreto VERI*FACTU", exception);
            }
        }
    }

    private static void verifyType(Path path, boolean directory) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || directory != Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw failure("La ruta del secreto VERI*FACTU no tiene el tipo esperado", null);
        }
    }

    private static IllegalStateException failure(String message, Exception cause) {
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }
}
