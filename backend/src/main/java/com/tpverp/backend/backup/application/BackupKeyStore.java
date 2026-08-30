package com.tpverp.backend.backup.application;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;

public final class BackupKeyStore {

    public static final String RECOVERY_FILE = "tpv-backup-recovery.key";
    private static final String PROTECTED_BRK_FILE = "backup-brk.dpapi";
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final long MAX_KEY_FILE_BYTES = 1024L * 1024L;
    private final Path keyDirectory;
    private final SecretProtector protector;
    private final RecoveryKeyPackage recoveryKeyPackage = new RecoveryKeyPackage();

    public BackupKeyStore(Path keyDirectory, SecretProtector protector) {
        this.keyDirectory = keyDirectory;
        this.protector = protector;
    }

    /** @deprecated Compatibility provisioning for installations created before recovery v2. */
    @Deprecated
    public synchronized void initialize(char[] adminPassword, Path backupDirectory) {
        try {
            Files.createDirectories(keyDirectory);
            Files.createDirectories(backupDirectory);
            Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            boolean protectedExists = isSafeRegularFile(protectedBrk);
            boolean recoveryExists = isSafeRegularFile(recovery);
            rejectPartialOrUnsafeState(protectedBrk, recovery, protectedExists, recoveryExists);
            if (!protectedExists) {
                byte[] recoveryPackage = recoveryKeyPackage.create(adminPassword, PBKDF2_ITERATIONS);
                byte[] brk = recoveryKeyPackage.open(recoveryPackage, adminPassword);
                try {
                    byte[] protectedValue = protector.protect(brk);
                    try {
                        writeNewPairAtomically(protectedBrk, protectedValue, recovery, recoveryPackage);
                    } finally {
                        Arrays.fill(protectedValue, (byte) 0);
                    }
                } finally {
                    Arrays.fill(brk, (byte) 0);
                    Arrays.fill(recoveryPackage, (byte) 0);
                }
            } else {
                verifyMatchingKeyMaterial(protectedBrk, recovery, adminPassword);
            }
            copyAtomically(recovery, backupDirectory.resolve(RECOVERY_FILE));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar la clave de backup", exception);
        }
    }

    /** Provisions the current v2 recovery package, independent of ADMIN. */
    public synchronized void initializeV2(char[] recoverySecret, Path backupDirectory) {
        try {
            Files.createDirectories(keyDirectory);
            Files.createDirectories(backupDirectory);
            Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            boolean protectedExists = isSafeRegularFile(protectedBrk);
            boolean recoveryExists = isSafeRegularFile(recovery);
            rejectPartialOrUnsafeState(protectedBrk, recovery, protectedExists, recoveryExists);
            byte[] brk;
            byte[] packageBytes;
            if (!protectedExists) {
                packageBytes = recoveryKeyPackage.createV2(recoverySecret, PBKDF2_ITERATIONS);
                brk = recoveryKeyPackage.openAny(packageBytes, recoverySecret);
                try {
                    byte[] protectedValue = protector.protect(brk);
                    try { writeNewPairAtomically(protectedBrk, protectedValue, recovery, packageBytes); }
                    finally { Arrays.fill(protectedValue, (byte) 0); }
                } finally {
                    Arrays.fill(brk, (byte) 0);
                    Arrays.fill(packageBytes, (byte) 0);
                }
            } else {
                brk = unprotectSafe(protectedBrk);
                try {
                    byte[] packageBytesOnDisk = readBoundedNoFollow(recovery);
                    boolean current;
                    try { current = recoveryKeyPackage.inspect(packageBytesOnDisk).version() == 2; }
                    finally { Arrays.fill(packageBytesOnDisk, (byte) 0); }
                    if (current) {
                        verifyMatchingKeyMaterialV2(protectedBrk, recovery, recoverySecret);
                    } else {
                        // Legacy packages remain readable but are upgraded in place.
                        packageBytes = recoveryKeyPackage.createV2ForKey(
                                brk, recoverySecret, PBKDF2_ITERATIONS);
                        try { replaceAtomically(recovery, packageBytes); }
                        finally { Arrays.fill(packageBytes, (byte) 0); }
                    }
                } finally { Arrays.fill(brk, (byte) 0); }
            }
            copyAtomically(recovery, backupDirectory.resolve(RECOVERY_FILE));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar la recuperación v2", exception);
        }
    }

    public byte[] loadForScheduledBackup() {
        try {
            requireConfiguredPair();
            return unprotectSafe(keyDirectory.resolve(PROTECTED_BRK_FILE));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo abrir la clave de backup con DPAPI", exception);
        }
    }

    /** Returns whether this installation has explicitly provisioned backup key material. */
    public boolean isConfigured() {
        try {
            Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            boolean protectedExists = isSafeRegularFile(protectedBrk);
            boolean recoveryExists = isSafeRegularFile(recovery);
            rejectPartialOrUnsafeState(protectedBrk, recovery, protectedExists, recoveryExists);
            return protectedExists;
        } catch (Exception exception) {
            throw new IllegalStateException("El material de clave de backup es incompleto o inseguro", exception);
        }
    }

    public byte[] loadForRestore(Path recoveryFile, char[] recoverySecret) {
        try {
            byte[] packageBytes = readBoundedNoFollow(recoveryFile);
            try { return recoveryKeyPackage.openAny(packageBytes, recoverySecret); }
            finally { Arrays.fill(packageBytes, (byte) 0); }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "La clave de recuperación o el archivo de recuperación no son válidos", exception);
        }
    }

    private void verifyMatchingKeyMaterialV2(Path protectedBrk, Path recovery, char[] secret) throws Exception {
        byte[] recoveryBytes = readBoundedNoFollow(recovery);
        byte[] fromRecovery = null;
        byte[] fromProtector = null;
        try {
            fromRecovery = recoveryKeyPackage.openAny(recoveryBytes, secret);
            fromProtector = unprotectSafe(protectedBrk);
            if (!MessageDigest.isEqual(fromRecovery, fromProtector)) {
                throw new IllegalStateException("Las dos copias de la clave de backup no coinciden");
            }
        } finally {
            if (fromRecovery != null) Arrays.fill(fromRecovery, (byte) 0);
            if (fromProtector != null) Arrays.fill(fromProtector, (byte) 0);
            Arrays.fill(recoveryBytes, (byte) 0);
        }
    }

    public synchronized void rewrap(
            char[] currentAdminPassword,
            char[] newAdminPassword,
            Path backupDirectory) {
        try {
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
            requireConfiguredPair();
            verifyMatchingKeyMaterial(protectedBrk, recovery, currentAdminPassword);
            byte[] recoveryBytes = readBoundedNoFollow(recovery);
            byte[] updated;
            try { updated = recoveryKeyPackage.rewrap(
                    recoveryBytes,
                    currentAdminPassword,
                    newAdminPassword,
                    PBKDF2_ITERATIONS); }
            finally { Arrays.fill(recoveryBytes, (byte) 0); }
            try {
                replaceAtomically(recovery, updated);
                copyAtomically(recovery, backupDirectory.resolve(RECOVERY_FILE));
            } finally {
                Arrays.fill(updated, (byte) 0);
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo actualizar la proteccion de la clave de backup", exception);
        }
    }

    /** Automatic ADMIN changes are a no-op for v2 and fail closed for legacy v1. */
    public synchronized boolean rewrapIfConfigured(
            char[] currentAdminPassword, char[] newAdminPassword, Path backupDirectory) {
        if (!isConfigured()) {
            return false;
        }
        try {
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            byte[] packageBytes = readBoundedNoFollow(recovery);
            boolean v2;
            try { v2 = recoveryKeyPackage.inspect(packageBytes).version() == 2; }
            finally { Arrays.fill(packageBytes, (byte) 0); }
            if (v2) {
                // v2 is independent from ADMIN: password changes must not
                // rotate, copy, or touch recovery material.
                return true;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo inspeccionar la clave de recuperación", exception);
        }
        throw new IllegalStateException(
                "La recuperación v1 está ligada a ADMIN; migre manualmente a v2 antes de cambiar la contraseña de ADMIN");
    }

    private void requireConfiguredPair() throws Exception {
        Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
        Path recovery = keyDirectory.resolve(RECOVERY_FILE);
        boolean protectedExists = isSafeRegularFile(protectedBrk);
        boolean recoveryExists = isSafeRegularFile(recovery);
        rejectPartialOrUnsafeState(protectedBrk, recovery, protectedExists, recoveryExists);
        if (!protectedExists) throw new IllegalStateException("La clave de backup no esta configurada");
    }

    private void verifyMatchingKeyMaterial(Path protectedBrk, Path recovery, char[] adminPassword) throws Exception {
        byte[] recoveryBytes = readBoundedNoFollow(recovery);
        byte[] fromRecovery = null;
        byte[] fromProtector = null;
        try {
            fromRecovery = recoveryKeyPackage.open(recoveryBytes, adminPassword);
            fromProtector = unprotectSafe(protectedBrk);
            if (!MessageDigest.isEqual(fromRecovery, fromProtector)) {
                throw new IllegalStateException("Las dos copias de la clave de backup no coinciden");
            }
        } finally {
            if (fromRecovery != null) Arrays.fill(fromRecovery, (byte) 0);
            if (fromProtector != null) Arrays.fill(fromProtector, (byte) 0);
            Arrays.fill(recoveryBytes, (byte) 0);
        }
    }

    private static boolean isSafeRegularFile(Path path) throws Exception {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IllegalStateException("El material de clave no es un archivo regular seguro: " + path);
        }
        return true;
    }

    private static void rejectPartialOrUnsafeState(
            Path protectedBrk,
            Path recovery,
            boolean protectedExists,
            boolean recoveryExists) {
        if (protectedExists != recoveryExists) {
            throw new IllegalStateException(
                    "El material de clave de backup esta incompleto; no se regenerara automaticamente: "
                            + protectedBrk + " / " + recovery);
        }
    }

    private static void writeNewPairAtomically(
            Path protectedTarget,
            byte[] protectedValue,
            Path recoveryTarget,
            byte[] recoveryValue) throws Exception {
        Path protectedTemp = Files.createTempFile(protectedTarget.getParent(), ".backup-brk-", ".tmp");
        Path recoveryTemp = Files.createTempFile(recoveryTarget.getParent(), ".backup-recovery-", ".tmp");
        boolean protectedInstalled = false;
        boolean recoveryInstalled = false;
        try {
            Files.write(protectedTemp, protectedValue, StandardOpenOption.TRUNCATE_EXISTING);
            Files.write(recoveryTemp, recoveryValue, StandardOpenOption.TRUNCATE_EXISTING);
            moveNew(protectedTemp, protectedTarget);
            protectedInstalled = true;
            moveNew(recoveryTemp, recoveryTarget);
            recoveryInstalled = true;
        } finally {
            Files.deleteIfExists(protectedTemp);
            Files.deleteIfExists(recoveryTemp);
            if (protectedInstalled != recoveryInstalled) {
                Files.deleteIfExists(protectedTarget);
                Files.deleteIfExists(recoveryTarget);
            }
        }
    }

    private static void moveNew(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void replaceAtomically(Path target, byte[] value) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), ".backup-recovery-", ".tmp");
        try {
            Files.write(temporary, value, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyAtomically(Path source, Path target) throws Exception {
        Path normalizedSource = source.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalizedSource,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > MAX_KEY_FILE_BYTES) {
            throw new IllegalStateException("El origen de recovery no es un fichero regular seguro");
        }
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) throw new IllegalStateException("El destino de recovery no tiene directorio padre");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".backup-recovery-copy-", ".tmp");
        try {
            try (FileChannel input = FileChannel.open(normalizedSource, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
                    FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                long size = input.size();
                if (size > MAX_KEY_FILE_BYTES) throw new IllegalStateException("Origen de recovery demasiado grande");
                long position = 0;
                ByteBuffer fallback = ByteBuffer.allocate(64 * 1024);
                while (position < size) {
                    long moved = input.transferTo(position, size - position, output);
                    if (moved <= 0) {
                        input.position(position);
                        fallback.clear();
                        int read = input.read(fallback);
                        if (read <= 0) throw new IllegalStateException("Copia sin progreso del recovery");
                        fallback.flip();
                        while (fallback.hasRemaining()) output.write(fallback);
                        moved = read;
                    }
                    position += moved;
                }
                if (output.size() != size) throw new IllegalStateException("Copia incompleta del recovery");
            }
            try {
                Files.move(temporary, normalizedTarget, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBoundedNoFollow(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalized,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() > MAX_KEY_FILE_BYTES) {
            throw new IllegalStateException("El material de clave no es un fichero regular seguro o excede 1 MiB");
        }
        try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > MAX_KEY_FILE_BYTES) throw new IllegalStateException("Material de clave demasiado grande");
            byte[] bytes = new byte[(int) size];
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) throw new IllegalStateException("Material de clave truncado");
                if (read == 0) throw new IllegalStateException("Lectura sin progreso del material de clave");
            }
            return bytes;
        }
    }

    private byte[] unprotectSafe(Path path) throws Exception {
        byte[] protectedBytes = readBoundedNoFollow(path);
        try { return protector.unprotect(protectedBytes); }
        finally { Arrays.fill(protectedBytes, (byte) 0); }
    }
}
