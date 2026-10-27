package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.backup.BackupRestoreFinalizationCommitService;
import com.tpverp.backend.verifactu.FiscalRuntimeGuardInitializer;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class DisasterRecoveryLicenseFinalizationTest {
    @TempDir Path keys;

    @Test void failedReconciliationPreservesHandoffAndDoesNotFinalizeFiscalDataOrComplete()throws Exception {
        Files.createFile(keys.resolve(RecoverySecretHandoff.FILE));
        UUID recovery=UUID.randomUUID(),installation=UUID.randomUUID();String digest="a".repeat(64);
        var state=new ObjectMapper().createObjectNode().put("phase","BOOTSTRAP_VERIFIED").put("recoveryId",recovery.toString())
                .put("installationId",installation.toString()).put("backupSha256",digest);
        var jdbc=mock(JdbcTemplate.class);var runtime=mock(FiscalRuntimeGuardInitializer.class);
        var fiscal=mock(BackupRestoreFinalizationCommitService.class);var manager=mock(PlatformTransactionManager.class);
        var licenses=mock(DisasterRecoveryLicenseReconciliation.class);
        when(jdbc.queryForMap(anyString(),eq(recovery))).thenReturn(Map.of("installation_id",installation,"backup_sha256",digest,"state","ACTIVE"));
        doThrow(new IllegalStateException("SaaS unavailable")).when(licenses).reconcile(state,false);
        var finalizer=new DisasterRecoveryStartupFinalizer(keys,jdbc,manager,runtime,fiscal,"https://saas.invalid",licenses);
        try(var handoff=mockStatic(RecoverySecretHandoff.class)) {
            handoff.when(()->RecoverySecretHandoff.read(keys)).thenReturn(state);
            assertThatThrownBy(()->finalizer.run(new DefaultApplicationArguments())).hasMessageContaining("unavailable");
            verifyNoInteractions(runtime,fiscal,manager);verify(jdbc,never()).update(anyString(),any(Object[].class));
            assertThat(Files.exists(keys.resolve(RecoverySecretHandoff.FILE))).isTrue();
        }
    }

    @Test void normalStartupWithoutALicenseHandoffDoesNotContactAuthorityOrWriteLicense()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var credentials=mock(com.tpverp.backend.licensing.LicenseSaasCredentialStore.class);
        var licenses=mock(com.tpverp.backend.licensing.LicenseRecoveryReconciler.class);
        var service=new DisasterRecoveryLicenseReconciliation(keys,jdbc,credentials,licenses,"https://saas.invalid");
        service.run(new DefaultApplicationArguments());
        verifyNoInteractions(jdbc,credentials,licenses);
    }
}
