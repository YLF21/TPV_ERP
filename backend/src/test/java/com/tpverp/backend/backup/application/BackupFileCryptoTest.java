package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupFileCryptoTest {

    @TempDir
    Path tempDir;

    private final BackupFileCrypto crypto = new BackupFileCrypto(1024);

    @Test
    void encryptsAndRestoresAFileAcrossMultipleAuthenticatedChunks() throws Exception {
        byte[] plaintext = randomBytes(3_333);
        byte[] brk = randomBytes(32);
        Path source = write("database.dump", plaintext);
        Path backup = tempDir.resolve("database.tpvb");
        Path restored = tempDir.resolve("restored.dump");

        BackupFileCrypto.BackupInfo written = crypto.encrypt(source, backup, brk);
        BackupFileCrypto.BackupInfo read = crypto.inspect(backup);
        crypto.decrypt(backup, restored, brk);

        assertThat(Files.readAllBytes(restored)).isEqualTo(plaintext);
        assertThat(Files.readAllBytes(backup)).isNotEqualTo(plaintext);
        assertThat(written).isEqualTo(read);
        assertThat(read).isEqualTo(new BackupFileCrypto.BackupInfo(1, 1024, 3_333, 4));
    }

    @Test
    void usesFreshDekAndNoncesForEveryBackup() throws Exception {
        Path source = write("database.dump", randomBytes(2_048));
        byte[] brk = randomBytes(32);
        Path first = tempDir.resolve("first.tpvb");
        Path second = tempDir.resolve("second.tpvb");

        crypto.encrypt(source, first, brk);
        crypto.encrypt(source, second, brk);

        assertThat(Files.readAllBytes(first)).isNotEqualTo(Files.readAllBytes(second));
    }

    @Test
    void rejectsWrongBrkAndAnyCiphertextTampering() throws Exception {
        byte[] brk = randomBytes(32);
        Path source = write("database.dump", randomBytes(2_500));
        Path backup = tempDir.resolve("database.tpvb");
        crypto.encrypt(source, backup, brk);

        assertThatThrownBy(() -> crypto.decrypt(
            backup,
            tempDir.resolve("wrong-key.dump"),
            randomBytes(32)))
            .isInstanceOf(GeneralSecurityException.class);

        byte[] tamperedBytes = Files.readAllBytes(backup);
        tamperedBytes[tamperedBytes.length / 2] ^= 1;
        Path tampered = write("tampered.tpvb", tamperedBytes);
        assertThatThrownBy(() -> crypto.decrypt(
            tampered,
            tempDir.resolve("tampered.dump"),
            brk))
            .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void supportsEmptyBackupsWithAuthenticatedMetadataAndHash() throws Exception {
        byte[] brk = randomBytes(32);
        Path source = write("empty.dump", new byte[0]);
        Path backup = tempDir.resolve("empty.tpvb");
        Path restored = tempDir.resolve("empty-restored.dump");

        crypto.encrypt(source, backup, brk);
        crypto.decrypt(backup, restored, brk);

        assertThat(Files.size(restored)).isZero();
        assertThat(crypto.inspect(backup).chunkCount()).isZero();
    }

    @Test
    void validatesTheBrkLength() {
        assertThatThrownBy(() -> crypto.encrypt(
            tempDir.resolve("missing"),
            tempDir.resolve("backup"),
            new byte[16]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("32");
    }

    private Path write(String name, byte[] contents) throws Exception {
        Path path = tempDir.resolve(name);
        Files.write(path, contents);
        return path;
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
