package com.tpverp.backend.servertransfer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.backup.BackupRestoreFinalizationCommitService;
import com.tpverp.backend.verifactu.FiscalRuntimeGuardInitializer;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Completes fiscal/audit finalization under the service account before opening normal operation. */
@Component
@Order(2)
public class ServerTransferStartupFinalizer implements ApplicationRunner {
    private final Path keys;
    private final JdbcTemplate jdbc;
    private final BackupRestoreFinalizationCommitService finalizer;
    private final FiscalRuntimeGuardInitializer runtimeGuard;
    public ServerTransferStartupFinalizer(@Value("${tpv.installation.key-directory}") Path keys,
            JdbcTemplate jdbc, BackupRestoreFinalizationCommitService finalizer, FiscalRuntimeGuardInitializer runtimeGuard) {
        this.keys = keys.toAbsolutePath().normalize(); this.jdbc = jdbc; this.finalizer = finalizer;
        this.runtimeGuard = runtimeGuard;
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        if (!Files.exists(keys.resolve(TransferSecretHandoff.FILE), LinkOption.NOFOLLOW_LINKS)) return;
        finalizeVerified(TransferSecretHandoff.read(keys));
    }

    void finalizeVerified(ObjectNode state) throws Exception {
        if (!"BOOTSTRAP_VERIFIED".equals(state.path("phase").asText())) {
            throw new IllegalStateException("El traslado no tiene una activación verificada bajo la cuenta del servicio");
        }
        UUID id = UUID.fromString(state.path("transferId").asText());
        UUID installation = UUID.fromString(state.path("installationId").asText());
        String hash = state.path("backupSha256").asText();
        var row = jdbc.queryForMap("select installation_id,state,backup_sha256,capsule_sha256,destination_key_fingerprint from server_transfer where id=?", id);
        if (!installation.equals(row.get("installation_id")) || !"ACTIVE".equals(row.get("state"))
                || !hash.equals(row.get("backup_sha256"))
                || !state.path("capsuleSha256").asText().equals(row.get("capsule_sha256"))
                || !state.path("destinationKeyFingerprint").asText().equals(row.get("destination_key_fingerprint"))) {
            throw new IllegalStateException("La activación del traslado no corresponde a la base de datos restaurada");
        }
        // Validate the restored runtime before appending any new fiscal evidence.
        // The regular runner can repeat this idempotent check later in startup.
        runtimeGuard.run(new org.springframework.boot.DefaultApplicationArguments(new String[0]));
        // Existing implementation creates event 07 for NO_VERIFACTU and operational audit in other modes.
        // Its durable idempotency marker also covers a crash after commit and before journal removal.
        finalizer.commit(id, hash);
        new ServerTransferJournal(keys).releaseAfterCentralCancellation(id);
        Files.deleteIfExists(keys.resolve(TransferSecretHandoff.FILE));
    }
}
