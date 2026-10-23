package com.tpverp.backend.disasterrecovery;

import com.tpverp.backend.backup.BackupRestoreFinalizationCommitService;
import com.tpverp.backend.verifactu.FiscalRuntimeGuardInitializer;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Order(2)
public final class DisasterRecoveryStartupFinalizer implements ApplicationRunner {
    private final Path keys;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FiscalRuntimeGuardInitializer runtime;
    private final BackupRestoreFinalizationCommitService finalizer;
    private final String saasUrl;
    public DisasterRecoveryStartupFinalizer(@Value("${tpv.installation.key-directory}") Path keys,JdbcTemplate jdbc,
            org.springframework.transaction.PlatformTransactionManager manager,FiscalRuntimeGuardInitializer runtime,BackupRestoreFinalizationCommitService finalizer,
            @Value("${tpv.license.saas-url:}") String saasUrl) {
        this.keys=keys;this.jdbc=jdbc;transactions=new TransactionTemplate(manager);this.runtime=runtime;this.finalizer=finalizer;this.saasUrl=saasUrl;
    }
    @Override public void run(ApplicationArguments arguments)throws Exception {
        if(!Files.exists(keys.resolve(RecoverySecretHandoff.FILE),LinkOption.NOFOLLOW_LINKS))return;
        var state=RecoverySecretHandoff.read(keys);UUID id=UUID.fromString(state.path("recoveryId").asText());String digest=state.path("backupSha256").asText();
        if(!"BOOTSTRAP_VERIFIED".equals(state.path("phase").asText()))throw new IllegalStateException("Recovery bootstrap not verified");
        var row=jdbc.queryForMap("select installation_id,backup_sha256,state from disaster_recovery where id=?",id);
        if(!state.path("installationId").asText().equals(row.get("installation_id").toString())||!digest.equals(row.get("backup_sha256"))||!java.util.Set.of("ACTIVE","COMPLETE").contains(row.get("state")))throw new IllegalStateException("Recovery database proof mismatch");
        runtime.run(new DefaultApplicationArguments(new String[0]));
        transactions.executeWithoutResult(tx->{
            jdbc.queryForObject("select set_config('tpv.recovery.maintenance_id',?,true)",String.class,id.toString());
            finalizer.commit(id,digest);jdbc.update("update disaster_recovery set state='COMPLETE' where id=?",id);
        });
        byte[] encoded=state.path("destinationPrivate").binaryValue();
        try {
            var destination=java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(encoded));
            new RecoveryCentralClient(saasUrl).call(id,"acknowledge",digest,destination);
        }finally{java.util.Arrays.fill(encoded,(byte)0);}
        var journal=state.deepCopy();journal.remove(java.util.List.of("secrets","destinationPrivate","phase"));journal.put("state","COMPLETE");
        Path maintenance=OfflineDisasterRecoveryCli.safe(Path.of(state.path("maintenanceDirectory").asText()));
        RecoverySecretHandoff.writeFile(maintenance.resolve("disaster-recovery.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(journal));
        // Keep the external barrier until all durable finalization writes succeeded.
        if(Files.exists(keys.resolve(DisasterRecoveryJournal.FREEZE),LinkOption.NOFOLLOW_LINKS))DisasterRecoveryJournal.release(keys,id);
        Files.deleteIfExists(keys.resolve(RecoverySecretHandoff.FILE));
    }
}
