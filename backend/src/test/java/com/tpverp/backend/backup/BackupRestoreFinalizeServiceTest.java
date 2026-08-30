package com.tpverp.backend.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.verifactu.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupRestoreFinalizeServiceTest {
    @TempDir Path temp;

    @Test
    void emitsEvent07OnlyForNoVerifactu() throws Exception {
        Path backup = temp.resolve("backup.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FILES_PROMOTED");
        var installation = new Installation("I", "PUBLIC", Instant.now());
        var installations = mock(InstallationRepository.class); when(installations.findAll()).thenReturn(List.of(installation));
        var configs = mock(VerifactuConfigurationRepository.class);
        var no = new VerifactuConfiguration(UUID.randomUUID(), FiscalMode.NO_VERIFACTU);
        when(configs.findAll()).thenReturn(List.of(no));
        var events = mock(FiscalEventService.class); var audit = mock(AuditService.class);
        var marker = mock(BackupRestoreFinalizationRepository.class); when(marker.findById(any())).thenReturn(java.util.Optional.empty());
        new BackupRestoreFinalizeService(new BackupRestoreFinalizationCommitService(marker, installations, configs, events, audit)).finalizeRestore(journal);
        verify(events).create(eq(no.getCompanyId()), eq(installation.getId()), eq(FiscalMode.NO_VERIFACTU),
                eq(FiscalEventType.BACKUP_RESTORED), contains("Restauración offline"));
        verifyNoInteractions(audit);
        assertThat(read(journal).getProperty("phase")).isEqualTo("FINALIZED");
    }

    @Test
    void refusesUndeterminedFiscalMode() throws Exception {
        Path backup = temp.resolve("backup.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FILES_PROMOTED");
        var installations = mock(InstallationRepository.class);
        when(installations.findAll()).thenReturn(List.of(new Installation("I", "PUBLIC", Instant.now())));
        var marker = mock(BackupRestoreFinalizationRepository.class); when(marker.findById(any())).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> new BackupRestoreFinalizeService(new BackupRestoreFinalizationCommitService(marker, installations,
                mock(VerifactuConfigurationRepository.class), mock(FiscalEventService.class), mock(AuditService.class)))
                .finalizeRestore(journal)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void usesOperationalAuditWithoutFiscalEventForVerifactu() throws Exception {
        Path backup = temp.resolve("backup-verifactu.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FILES_PROMOTED");
        var installation = new Installation("I", "PUBLIC", Instant.now());
        var installations = mock(InstallationRepository.class); when(installations.findAll()).thenReturn(List.of(installation));
        var configs = mock(VerifactuConfigurationRepository.class);
        var verifactu = new VerifactuConfiguration(UUID.randomUUID(), FiscalMode.VERIFACTU);
        when(configs.findAll()).thenReturn(List.of(verifactu));
        var events = mock(FiscalEventService.class); var audit = mock(AuditService.class);
        var marker = mock(BackupRestoreFinalizationRepository.class); when(marker.findById(any())).thenReturn(java.util.Optional.empty());
        new BackupRestoreFinalizeService(new BackupRestoreFinalizationCommitService(marker, installations, configs, events, audit)).finalizeRestore(journal);
        verifyNoInteractions(events);
        verify(audit).record(eq("BACKUP_RESTORED"), eq(com.tpverp.backend.audit.AuditResult.EXITO), anyMap());
    }

    @Test
    void preSifUsesOperationalAuditWithoutFiscalEvent() throws Exception {
        Path backup = temp.resolve("backup-pre-sif.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FILES_PROMOTED");
        var installation = new Installation("I", "PUBLIC", Instant.now());
        var installations = mock(InstallationRepository.class); when(installations.findAll()).thenReturn(List.of(installation));
        var configs = mock(VerifactuConfigurationRepository.class);
        var pre = new VerifactuConfiguration(UUID.randomUUID(), FiscalMode.PRE_SIF);
        when(configs.findAll()).thenReturn(List.of(pre));
        var events = mock(FiscalEventService.class); var audit = mock(AuditService.class);
        var marker = mock(BackupRestoreFinalizationRepository.class); when(marker.findById(any())).thenReturn(java.util.Optional.empty());
        new BackupRestoreFinalizeService(new BackupRestoreFinalizationCommitService(marker, installations, configs, events, audit)).finalizeRestore(journal);
        verifyNoInteractions(events);
        verify(audit).record(eq("BACKUP_RESTORED"), eq(com.tpverp.backend.audit.AuditResult.EXITO), anyMap());
    }

    @Test
    void existingDatabaseMarkerMakesRetryOnlySealJournal() throws Exception {
        Path backup = temp.resolve("backup-marker.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FILES_PROMOTED");
        UUID id = UUID.fromString(read(journal).getProperty("id"));
        var commit = mock(BackupRestoreFinalizationCommitService.class);
        new BackupRestoreFinalizeService(commit).finalizeRestore(journal);
        verify(commit).commit(id, sha256(backup));
        assertThat(read(journal).getProperty("phase")).isEqualTo("FINALIZED");
    }

    @Test
    void finalizedJournalCanBeVerifiedAfterOriginalBackupIsArchived() throws Exception {
        Path backup = temp.resolve("backup-archived.tpvb"); Files.writeString(backup, "backup");
        Path journal = journal(backup, "FINALIZED");
        Properties state = read(journal);
        Files.delete(backup);
        var commit = mock(BackupRestoreFinalizationCommitService.class);
        new BackupRestoreFinalizeService(commit).finalizeRestore(journal);
        verify(commit).verifyMarker(UUID.fromString(state.getProperty("id")), state.getProperty("backupSha256"));
    }

    private Path journal(Path backup, String phase) throws Exception {
        Path path = temp.resolve("tpv-restore-journal.properties"); Properties p = new Properties();
        p.setProperty("format", "TPV-RESTORE-JOURNAL-V2"); p.setProperty("id", UUID.randomUUID().toString());
        p.setProperty("phase", phase); p.setProperty("backup", backup.toString());
        p.setProperty("backupSha256", sha256(backup)); try (var out = Files.newOutputStream(path)) { p.store(out, "no secrets"); }
        return path;
    }
    private static Properties read(Path p) throws Exception { Properties v = new Properties(); try (var in = Files.newInputStream(p)) { v.load(in); } return v; }
    private static String sha256(Path p) throws Exception { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))); }
}
