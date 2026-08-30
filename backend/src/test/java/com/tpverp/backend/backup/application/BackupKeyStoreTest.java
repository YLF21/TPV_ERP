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
