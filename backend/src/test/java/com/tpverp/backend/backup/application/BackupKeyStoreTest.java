package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.shared.crypto.SecretProtector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class BackupKeyStoreTest {

    @TempDir
    private Path tempDir;

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void nativeWindowsDpapiBackupKeysAndIntegritySealRetainProtectedAcls() throws Exception {
        Path keys=tempDir.resolve("native-keys");
        var store=new BackupKeyStore(keys,new com.tpverp.backend.shared.crypto.WindowsDpapiSecretProtector());
        store.initializeV2("synthetic-test-recovery".toCharArray(),tempDir.resolve("native-backups"));
        var directory=com.tpverp.backend.verifactu.TransferSecretDirectory.forCurrentAccount(keys);
        for(String name:java.util.List.of("backup-brk.dpapi","tpv-backup-recovery.key","backup-recovery-integrity.dpapi")) {
            directory.verifyFile(name);
            assertThat(com.sun.jna.platform.win32.Advapi32Util.getFileSecurityDescriptor(keys.resolve(name).toFile(),false).Control
                    & com.sun.jna.platform.win32.WinNT.SE_DACL_PROTECTED).isNotZero();
        }
        byte[] brk=store.loadForScheduledBackup();
        byte[] wrapped=Files.readAllBytes(keys.resolve(BackupKeyStore.RECOVERY_FILE));
        try{store.recordVerifiedRecoveryMaterial(brk,wrapped);directory.verifyFile("backup-recovery-integrity.dpapi");}
        finally{Arrays.fill(brk,(byte)0);Arrays.fill(wrapped,(byte)0);}
    }

    @Test
    void createsMatchingMaterialAndRewrapsWithoutChangingBackupRootKey() throws Exception {
        Path keyDirectory = tempDir.resolve("keys");
        Path backupDirectory = tempDir.resolve("backups");
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());

        store.initialize("1234".toCharArray(), backupDirectory);
        byte[] scheduledKey = store.loadForScheduledBackup();
        byte[] recoveryKey = store.loadForRestore(
                backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE), "1234".toCharArray());

        assertThat(store.isConfigured()).isTrue();
        assertThat(recoveryKey).isEqualTo(scheduledKey);

        store.rewrap("1234".toCharArray(), "5678".toCharArray(), backupDirectory);
        byte[] rotatedRecoveryKey = store.loadForRestore(
                backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE), "5678".toCharArray());
        assertThat(rotatedRecoveryKey).isEqualTo(scheduledKey);
        assertThatThrownBy(() -> store.loadForRestore(
                backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE), "1234".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);

        Arrays.fill(scheduledKey, (byte) 0);
        Arrays.fill(recoveryKey, (byte) 0);
        Arrays.fill(rotatedRecoveryKey, (byte) 0);
    }

    @Test
    void refusesPartialMaterialInsteadOfSilentlyRotatingAwayExistingBackups() throws Exception {
        Path keyDirectory = tempDir.resolve("partial-keys");
        Files.createDirectories(keyDirectory);
        Path protectedKey = keyDirectory.resolve("backup-brk.dpapi");
        Files.write(protectedKey, new byte[32]);
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());

        assertThatThrownBy(() -> store.initialize("1234".toCharArray(), tempDir.resolve("partial-backups")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No se pudo preparar");
        assertThatThrownBy(store::isConfigured)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("incompleto");
        assertThat(Files.readAllBytes(protectedKey)).containsOnly(0);
        assertThat(keyDirectory.resolve(BackupKeyStore.RECOVERY_FILE)).doesNotExist();
    }

    @Test
    void detectsWhenProtectedAndRecoveryCopiesDoNotContainTheSameRootKey() throws Exception {
        Path keyDirectory = tempDir.resolve("mismatched-keys");
        Path backupDirectory = tempDir.resolve("mismatched-backups");
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());
        store.initialize("1234".toCharArray(), backupDirectory);
        Files.write(keyDirectory.resolve("backup-brk.dpapi"), new byte[32]);

        assertThatThrownBy(() -> store.initialize("1234".toCharArray(), backupDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Las dos copias de la clave de backup no coinciden");
    }

    @Test
    void changingAdminDoesNotRewrapOrBreakIndependentV2Recovery() throws Exception {
        Path keyDirectory = tempDir.resolve("v2-keys");
        Path backupDirectory = tempDir.resolve("v2-backups");
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());
        char[] recovery = "independent-recovery-secret".toCharArray();
        store.initializeV2(recovery, backupDirectory);
        byte[] before = Files.readAllBytes(backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE));
        assertThat(store.rewrapIfConfigured("old-admin".toCharArray(), "new-admin".toCharArray(), backupDirectory)).isTrue();
        assertThat(Files.readAllBytes(backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE))).isEqualTo(before);
        assertThat(store.loadForRestore(backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE), recovery)).hasSize(32);
    }

    @Test
    void automaticAdminChangeBlocksLegacyV1InsteadOfCreatingSplitBrain() throws Exception {
        Path keyDirectory = tempDir.resolve("v1-automatic-keys");
        Path backupDirectory = tempDir.resolve("v1-automatic-backups");
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());
        store.initialize("old-admin".toCharArray(), backupDirectory);
        byte[] before = Files.readAllBytes(backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE));

        assertThatThrownBy(() -> store.rewrapIfConfigured(
                "old-admin".toCharArray(), "new-admin".toCharArray(), backupDirectory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("migre manualmente a v2");
        assertThat(Files.readAllBytes(backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE))).isEqualTo(before);
        assertThat(store.loadForRestore(
                backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE), "old-admin".toCharArray())).hasSize(32);
    }

    @Test
    void rejectsCorruptOrReplacedWrappedKeyBeforeItCanReplaceGoodSaasBackups() throws Exception {
        Path keys = tempDir.resolve("integrity-keys"), backups = tempDir.resolve("integrity-backups");
        var store = new BackupKeyStore(keys, new CopyProtector());
        char[] secret = "independent-recovery-secret".toCharArray();
        store.initializeV2(secret, backups);
        byte[] original = store.exportWrappedRecoveryKey();
        byte[] corrupted = original.clone(); corrupted[corrupted.length - 1] ^= 1;
        Files.write(keys.resolve(BackupKeyStore.RECOVERY_FILE), corrupted);
        assertThat(new RecoveryKeyPackage().inspect(corrupted).version()).isEqualTo(2);
        assertThat(store.loadForScheduledBackup()).hasSize(32);
        assertThatThrownBy(store::exportWrappedRecoveryKey).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Las claves de recuperación cambiaron desde su última verificación; revise la configuración");
        Files.write(keys.resolve(BackupKeyStore.RECOVERY_FILE), new RecoveryKeyPackage().createV2(secret, 100_000));
        assertThatThrownBy(store::exportWrappedRecoveryKey).isInstanceOf(IllegalStateException.class);
        Files.write(keys.resolve(BackupKeyStore.RECOVERY_FILE), original);
        assertThat(store.exportWrappedRecoveryKey()).isEqualTo(original);
    }

    @Test
    void recoveryIntegrityAlsoDetectsReplacingTheLocallyProtectedBackupKey() throws Exception {
        Path keys = tempDir.resolve("brk-integrity-keys");
        var store = new BackupKeyStore(keys, new CopyProtector());
        store.initializeV2("independent-recovery-secret".toCharArray(), tempDir.resolve("brk-integrity-backups"));
        byte[] altered = Files.readAllBytes(keys.resolve("backup-brk.dpapi")); altered[0] ^= 1;
        Files.write(keys.resolve("backup-brk.dpapi"), altered);
        assertThatThrownBy(store::exportWrappedRecoveryKey).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Las claves de recuperación cambiaron desde su última verificación; revise la configuración");
    }

    @Test
    void existingV2MaterialRequiresPassphraseVerificationBeforeFirstCloudExport() throws Exception {
        Path keys = tempDir.resolve("pre-integrity-keys"), backups = tempDir.resolve("pre-integrity-backups");
        var store = new BackupKeyStore(keys, new CopyProtector());
        char[] secret = "independent-recovery-secret".toCharArray();
        store.initializeV2(secret, backups);
        byte[] original = store.exportWrappedRecoveryKey();
        Files.delete(keys.resolve("backup-recovery-integrity.dpapi"));
        assertThat(store.loadForScheduledBackup()).hasSize(32);
        assertThatThrownBy(store::exportWrappedRecoveryKey).isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Vuelva a validar la clave de recuperación en la configuración antes del envío a SaaS");
        assertThatThrownBy(() -> store.initializeV2("incorrect-recovery-secret".toCharArray(), backups))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(store::exportWrappedRecoveryKey).isInstanceOf(IllegalStateException.class);
        store.initializeV2(secret, backups);
        assertThat(store.exportWrappedRecoveryKey()).isEqualTo(original);
    }

    @Test
    void restoreRejectsOversizedOrSymlinkedRecoveryMaterial() throws Exception {
        Path oversized = tempDir.resolve("oversized-recovery.key");
        Files.write(oversized, new byte[1024 * 1024 + 1]);
        var store = new BackupKeyStore(tempDir.resolve("unused-keys"), new CopyProtector());
        assertThatThrownBy(() -> store.loadForRestore(oversized, "recovery-secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);

        Path target = tempDir.resolve("recovery-target.key"); Files.writeString(target, "not-a-package");
        Path link = tempDir.resolve("recovery-link.key");
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) { Assumptions.abort("symlinks unavailable"); }
        assertThatThrownBy(() -> store.loadForRestore(link, "recovery-secret".toCharArray()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copyingRecoveryDoesNotFollowAPreExistingDestinationLink() throws Exception {
        Path keyDirectory = tempDir.resolve("copy-keys");
        Path backupDirectory = tempDir.resolve("copy-backups");
        var store = new BackupKeyStore(keyDirectory, new CopyProtector());
        store.initialize("old-admin".toCharArray(), backupDirectory);
        Path outside = tempDir.resolve("outside-recovery.key"); Files.writeString(outside, "sentinel");
        Path destination = backupDirectory.resolve(BackupKeyStore.RECOVERY_FILE);
        Files.delete(destination);
        try { Files.createSymbolicLink(destination, outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) { Assumptions.abort("symlinks unavailable"); }

        store.initialize("old-admin".toCharArray(), backupDirectory);
        assertThat(Files.readString(outside)).isEqualTo("sentinel");
        assertThat(Files.isSymbolicLink(destination)).isFalse();
    }

    private static final class CopyProtector implements SecretProtector {
        @Override
        public byte[] protect(byte[] plaintext) {
            return Arrays.copyOf(plaintext, plaintext.length);
        }

        @Override
        public byte[] unprotect(byte[] protectedValue) {
            return Arrays.copyOf(protectedValue, protectedValue.length);
        }
    }
}
