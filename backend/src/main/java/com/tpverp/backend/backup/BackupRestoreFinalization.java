package com.tpverp.backend.backup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "backup_restore_finalization")
public class BackupRestoreFinalization {
    @Id
    @Column(name = "journal_id")
    private UUID journalId;
    @Column(name = "backup_sha256", nullable = false, length = 64)
    private String backupSha256;
    @Column(name = "fiscal_mode", nullable = false, length = 16)
    private String fiscalMode;
    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    protected BackupRestoreFinalization() { }

    public BackupRestoreFinalization(UUID journalId, String backupSha256, String fiscalMode, Instant finalizedAt) {
        this.journalId = Objects.requireNonNull(journalId);
        this.backupSha256 = Objects.requireNonNull(backupSha256);
        this.fiscalMode = Objects.requireNonNull(fiscalMode);
        this.finalizedAt = Objects.requireNonNull(finalizedAt);
    }

    public UUID getJournalId() { return journalId; }
    public String getBackupSha256() { return backupSha256; }
}
