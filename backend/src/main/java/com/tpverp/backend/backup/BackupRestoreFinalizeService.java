package com.tpverp.backend.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Properties;
import org.springframework.stereotype.Service;

/** Finalizes an offline restore before normal production startup is allowed. */
@Service
public class BackupRestoreFinalizeService {
    private final BackupRestoreFinalizationCommitService commitService;

    public BackupRestoreFinalizeService(BackupRestoreFinalizationCommitService commitService) {
        this.commitService = commitService;
    }

    public void finalizeRestore(Path journal) {
        Path normalizedJournal = BackupRestoreJournalReader.normalize(journal);
        Properties state = BackupRestoreJournalReader.read(normalizedJournal);
        String phase = BackupRestoreJournalReader.phase(state);
        String backupHash = BackupRestoreJournalReader.hash(state);
        if ("FINALIZED".equals(phase)) {
            // FINALIZED is self-contained: the original media may have been archived.
            commitService.verifyMarker(BackupRestoreJournalReader.id(state), backupHash);
            return;
        }
        if (!"FILES_PROMOTED".equals(phase)) {
            throw new IllegalStateException("La restauración no terminó la promoción de ficheros; no se arranca el backend");
        }
        Path backup = BackupRestoreJournalReader.normalize(Path.of(
                BackupRestoreJournalReader.required(state, "backup")));
        if (!safeRegular(backup) || !backupHash.equals(BackupRestoreJournalReader.sha256(backup))) {
            throw new IllegalStateException("La huella del backup del journal no coincide");
        }
        commitService.commit(BackupRestoreJournalReader.id(state), backupHash);
        state.setProperty("phase", "FINALIZED");
        state.setProperty("finalizedAt", java.time.Instant.now().toString());
        write(normalizedJournal, state);
    }

    private static void write(Path path, Properties properties) {
        try {
            Path normalized = BackupRestoreJournalReader.normalize(path);
            Path parent = normalized.getParent();
            if (parent == null) throw new java.io.IOException("Journal sin directorio padre");
            if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new IOException("Journal inseguro");
            }
            Path temp = Files.createTempFile(parent, ".tpv-restore-finalize-", ".tmp");
            try {
                try (var output = Files.newOutputStream(temp, java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                    properties.store(output, "TPV ERP offline restore; no secrets");
                }
                try { Files.move(temp, normalized, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temp, normalized, StandardCopyOption.REPLACE_EXISTING); }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (java.io.IOException ex) { throw new IllegalStateException("No se pudo sellar el journal de restauración", ex); }
    }

    private static boolean safeRegular(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException ex) { return false; }
    }

}
