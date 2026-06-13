package com.tpverp.backend.backup.application;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class BackupKeyStore {

    public static final String RECOVERY_FILE = "tpv-backup-recovery.key";
    private static final String PROTECTED_BRK_FILE = "backup-brk.dpapi";
    private static final int PBKDF2_ITERATIONS = 600_000;
    private final Path keyDirectory;
    private final SecretProtector protector;
    private final RecoveryKeyPackage recoveryKeyPackage = new RecoveryKeyPackage();

    public BackupKeyStore(Path keyDirectory, SecretProtector protector) {
        this.keyDirectory = keyDirectory;
        this.protector = protector;
    }

    public synchronized void initialize(char[] adminPassword, Path backupDirectory) {
        try {
            Files.createDirectories(keyDirectory);
            Files.createDirectories(backupDirectory);
            Path protectedBrk = keyDirectory.resolve(PROTECTED_BRK_FILE);
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            if (!Files.exists(protectedBrk) || !Files.exists(recovery)) {
                byte[] recoveryPackage = recoveryKeyPackage.create(adminPassword, PBKDF2_ITERATIONS);
                byte[] brk = recoveryKeyPackage.open(recoveryPackage, adminPassword);
                try {
                    Files.write(protectedBrk, protector.protect(brk));
                    Files.write(recovery, recoveryPackage);
                } finally {
                    Arrays.fill(brk, (byte) 0);
                }
            } else {
                byte[] verifiedBrk = recoveryKeyPackage.open(Files.readAllBytes(recovery), adminPassword);
                Arrays.fill(verifiedBrk, (byte) 0);
            }
            Files.copy(recovery, backupDirectory.resolve(RECOVERY_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo preparar la clave de backup", exception);
        }
    }

    public byte[] loadForScheduledBackup() {
        try {
            return protector.unprotect(
                    Files.readAllBytes(keyDirectory.resolve(PROTECTED_BRK_FILE)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo abrir la clave de backup con DPAPI", exception);
        }
    }

    public byte[] loadForRestore(Path recoveryFile, char[] adminPassword) {
        try {
            return recoveryKeyPackage.open(Files.readAllBytes(recoveryFile), adminPassword);
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "La contrasena ADMIN o el archivo de recuperacion no son validos", exception);
        }
    }

    public synchronized void rewrap(
            char[] currentAdminPassword,
            char[] newAdminPassword,
            Path backupDirectory) {
        try {
            Path recovery = keyDirectory.resolve(RECOVERY_FILE);
            byte[] updated = recoveryKeyPackage.rewrap(
                    Files.readAllBytes(recovery),
                    currentAdminPassword,
                    newAdminPassword,
                    PBKDF2_ITERATIONS);
            Path temporary = Files.createTempFile(keyDirectory, RECOVERY_FILE, ".tmp");
            try {
                Files.write(temporary, updated);
                Files.move(
                        temporary,
                        recovery,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            Files.copy(
                    recovery,
                    backupDirectory.resolve(RECOVERY_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo actualizar la proteccion de la clave de backup", exception);
        }
    }
}
