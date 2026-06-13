package com.tpverp.backend.backup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicBackupFileTest {

    @TempDir
    Path tempDir;

    private final BackupFileCrypto crypto = new BackupFileCrypto(1024);

    @Test
    void failedRestoreKeepsExistingDestinationAndRemovesTemporaryFile() throws Exception {
        byte[] brk = randomBytes(32);
        Path source = write("source.dump", randomBytes(2_400));
        Path backup = tempDir.resolve("source.tpvb");
        Path destination = write("database.dump", "existing-data".getBytes());
        crypto.encrypt(source, backup, brk);
        byte[] tampered = Files.readAllBytes(backup);
        tampered[tampered.length - 1] ^= 1;
        Files.write(backup, tampered);

        assertThatThrownBy(() -> crypto.decrypt(backup, destination, brk))
            .isInstanceOf(GeneralSecurityException.class);

        assertThat(Files.readString(destination)).isEqualTo("existing-data");
        assertThat(temporarySiblings(destination)).isZero();
    }

    @Test
    void successfulEncryptionAndRestoreLeaveNoTemporaryFiles() throws Exception {
        byte[] plaintext = randomBytes(4_000);
        byte[] brk = randomBytes(32);
        Path source = write("source.dump", plaintext);
        Path backup = tempDir.resolve("source.tpvb");
        Path destination = tempDir.resolve("database.dump");

        crypto.encrypt(source, backup, brk);
        crypto.decrypt(backup, destination, brk);

        assertThat(Files.readAllBytes(destination)).isEqualTo(plaintext);
        assertThat(temporarySiblings(backup)).isZero();
        assertThat(temporarySiblings(destination)).isZero();
    }

    private long temporarySiblings(Path destination) throws Exception {
        try (Stream<Path> siblings = Files.list(destination.getParent())) {
            return siblings
                .filter(path -> path.getFileName().toString()
                    .startsWith("." + destination.getFileName() + "."))
                .count();
        }
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
