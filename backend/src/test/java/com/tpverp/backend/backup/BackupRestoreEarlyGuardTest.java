package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;

class BackupRestoreEarlyGuardTest {
    @TempDir Path temp;

    @Test
    void blocksPendingJournalBeforeContextAndAllowsFinalizedJournal() throws Exception {
        Path journal = temp.resolve("journal.properties");
        write(journal, "DATABASE_RESTORED");
        var environment = new MockEnvironment().withProperty("tpv.backup.restore-journal-path", journal.toString());
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new BackupRestoreEarlyGuard().postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("pendiente");
        write(journal, "FINALIZED");
        new BackupRestoreEarlyGuard().postProcessEnvironment(environment, new SpringApplication());
    }

    @Test
    void finalizeArgumentMustMatchAuthorizedJournalAndAllowedPhase() throws Exception {
        Path journal = temp.resolve("journal-finalize.properties");
        Path backup = temp.resolve("backup.tpvb");
        Files.writeString(backup, "backup");
        Properties properties = new Properties();
        properties.setProperty("format", "TPV-RESTORE-JOURNAL-V2");
        properties.setProperty("id", UUID.randomUUID().toString());
        properties.setProperty("phase", "FILES_PROMOTED");
        properties.setProperty("backup", backup.toAbsolutePath().toString());
        properties.setProperty("backupSha256", java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(backup))));
        try (var output = Files.newOutputStream(journal)) { properties.store(output, "test"); }
        var allowed = new MockEnvironment().withProperty("tpv.backup.restore-journal-path", journal.toString())
                .withProperty("tpv.restore-finalize", journal.toString());
        allowed.setActiveProfiles("prod");
        new BackupRestoreEarlyGuard().postProcessEnvironment(allowed, new SpringApplication());

        var wrong = new MockEnvironment().withProperty("tpv.backup.restore-journal-path", journal.toString())
                .withProperty("tpv.restore-finalize", temp.resolve("other.properties").toString());
        wrong.setActiveProfiles("prod");
        assertThatThrownBy(() -> new BackupRestoreEarlyGuard().postProcessEnvironment(wrong, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("journal productivo autorizado");
    }

    @Test
    void rejectsSymlinkedProductionJournal() throws Exception {
        Path target = temp.resolve("real-journal.properties");
        write(target, "FINALIZED");
        Path link = temp.resolve("journal-link.properties");
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException ex) { Assumptions.abort("symlinks unavailable"); }
        var environment = new MockEnvironment().withProperty("tpv.backup.restore-journal-path", link.toString());
        environment.setActiveProfiles("prod");
        assertThatThrownBy(() -> new BackupRestoreEarlyGuard().postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runsAfterConfigDataAndMatchesProdProfileExactly() {
        assertThat(new BackupRestoreEarlyGuard().getOrder())
                .isEqualTo(ConfigDataEnvironmentPostProcessor.ORDER + 1);
        var environment = new MockEnvironment().withProperty("spring.profiles.active", "production");
        environment.setActiveProfiles("production");
        new BackupRestoreEarlyGuard().postProcessEnvironment(environment, new SpringApplication());
    }

    private static void write(Path path, String phase) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("format", "TPV-RESTORE-JOURNAL-V2");
        properties.setProperty("id", UUID.randomUUID().toString());
        properties.setProperty("phase", phase);
        properties.setProperty("backup", path.resolveSibling("archived-backup.tpvb").toAbsolutePath().toString());
        properties.setProperty("backupSha256", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        try (var output = Files.newOutputStream(path)) { properties.store(output, "test"); }
    }
}
