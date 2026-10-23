package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.BackupRestoreFinalizationCommitService;
import com.tpverp.backend.verifactu.FiscalRuntimeGuardInitializer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class ServerTransferStartupFinalizerTest {
    @TempDir Path keys;
    @Test void fiscalFailureKeepsExternalFenceAndRecoveryMaterial() throws Exception {
        UUID id = UUID.randomUUID(), installation = UUID.randomUUID();
        var journal = new ServerTransferJournal(keys); journal.freeze(id);
        Path handoff = Files.writeString(keys.resolve(TransferSecretHandoff.FILE), "protected recovery material");
        var jdbc = mock(JdbcTemplate.class);
        var fiscal = mock(BackupRestoreFinalizationCommitService.class);
        String hash = "a".repeat(64), capsule = "b".repeat(64), fingerprint = "c".repeat(64);
        when(jdbc.queryForMap(anyString(), eq(id))).thenReturn(Map.of("installation_id", installation,
                "state", "ACTIVE", "backup_sha256", hash, "capsule_sha256", capsule, "destination_key_fingerprint", fingerprint));
        var state = new ObjectMapper().createObjectNode().put("phase", "BOOTSTRAP_VERIFIED")
                .put("transferId", id.toString()).put("installationId", installation.toString())
                .put("backupSha256", hash).put("capsuleSha256", capsule).put("destinationKeyFingerprint", fingerprint);
        var guard = mock(FiscalRuntimeGuardInitializer.class);
        var service = new ServerTransferStartupFinalizer(keys, jdbc, fiscal, guard);
        doThrow(new IllegalStateException("runtime mismatch")).when(guard).run(any());
        assertThatThrownBy(() -> service.finalizeVerified(state)).hasMessageContaining("runtime mismatch");
        assertThat(journal.frozen()).isTrue(); assertThat(handoff).exists(); verifyNoInteractions(fiscal);
        doNothing().when(guard).run(any());
        doThrow(new IllegalStateException("signer unavailable")).when(fiscal).commit(id, hash);
        assertThatThrownBy(() -> service.finalizeVerified(state)).hasMessageContaining("signer unavailable");
        assertThat(journal.frozen()).isTrue(); assertThat(handoff).exists();
        doNothing().when(fiscal).commit(id, hash);
        service.finalizeVerified(state);
        assertThat(journal.frozen()).isFalse(); assertThat(handoff).doesNotExist();
    }

    @Test void importedButUnverifiedDestinationCannotBeOpened() throws Exception {
        UUID id = UUID.randomUUID(); new ServerTransferJournal(keys).freeze(id);
        var fiscal = mock(BackupRestoreFinalizationCommitService.class);
        var service = new ServerTransferStartupFinalizer(keys, mock(JdbcTemplate.class), fiscal,
                mock(FiscalRuntimeGuardInitializer.class));
        assertThatThrownBy(() -> service.finalizeVerified(new ObjectMapper().createObjectNode().put("phase", "RECEIVED")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ServerTransferJournal(keys).frozen()).isTrue(); verifyNoInteractions(fiscal);
    }
}
