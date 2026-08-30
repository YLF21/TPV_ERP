package com.tpverp.backend.backup;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Fails before bean/JPA creation when production has an unfinished restore. */
public final class BackupRestoreEarlyGuard implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
            SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;
        Path journal = authorizedJournal(environment);
        String finalizeArgument = environment.getProperty("tpv.restore-finalize");
        boolean exists = Files.exists(journal, LinkOption.NOFOLLOW_LINKS);
        if (finalizeArgument != null && !finalizeArgument.isBlank()) {
            Path requested = BackupRestoreJournalReader.normalize(Path.of(finalizeArgument));
            if (!requested.equals(journal)) {
                throw new IllegalStateException("El finalize solo puede usar el journal productivo autorizado");
            }
            if (!exists) throw new IllegalStateException("No existe el journal autorizado para finalizar");
        }
        if (!exists) return;
        Properties state = BackupRestoreJournalReader.read(journal);
        String phase = BackupRestoreJournalReader.phase(state);
        if (finalizeArgument != null && !finalizeArgument.isBlank()) {
            if (!"FILES_PROMOTED".equals(phase) && !"FINALIZED".equals(phase)) {
                throw new IllegalStateException("El finalize requiere fase FILES_PROMOTED o FINALIZED");
            }
            if ("FILES_PROMOTED".equals(phase)) {
                Path backup = BackupRestoreJournalReader.normalize(Path.of(
                        BackupRestoreJournalReader.required(state, "backup")));
                if (!BackupRestoreJournalReader.hash(state).equals(BackupRestoreJournalReader.sha256(backup))) {
                    throw new IllegalStateException("La huella del backup del journal no coincide");
                }
            }
            return;
        }
        if (!"FINALIZED".equals(phase)) {
            throw new IllegalStateException("Existe un journal de restauración pendiente; producción no puede arrancar");
        }
    }

    static Path authorizedJournal(ConfigurableEnvironment environment) {
        String configured = environment.getProperty("TPV_BACKUP_RESTORE_JOURNAL_PATH");
        if (configured == null || configured.isBlank()) {
            configured = environment.getProperty("tpv.backup.restore-journal-path");
        }
        Path raw = Path.of(configured == null || configured.isBlank()
                ? BackupRestoreJournalReader.DEFAULT_PRODUCTION_PATH : configured);
        if (!raw.isAbsolute()) throw new IllegalStateException("La ruta productiva del journal debe ser absoluta");
        return BackupRestoreJournalReader.normalize(raw);
    }

    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
}
