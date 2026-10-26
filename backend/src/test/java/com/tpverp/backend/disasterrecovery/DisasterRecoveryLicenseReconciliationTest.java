package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.licensing.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DisasterRecoveryLicenseReconciliationTest {
    private final UUID recovery=UUID.randomUUID(),installation=UUID.randomUUID(),backup=UUID.randomUUID();
    private final ObjectNode state=new ObjectMapper().createObjectNode().put("recoveryId",recovery.toString())
            .put("installationId",installation.toString()).put("backupId",backup.toString())
            .put("backupSha256","a".repeat(64)).put("destinationKeyFingerprint","b".repeat(64));

    private Map<String,Object> row(String phase) {return Map.of("state",phase,"installation_id",installation,"backup_id",backup,"backup_sha256","a".repeat(64));}
    private RecoveredLicenseSnapshot snapshot(UUID id,String fingerprint) {
        return new RecoveredLicenseSnapshot(id,installation,backup,"a".repeat(64),fingerprint,null,null,null,null,null,null,null,null,null,null,null,null,0,0,0,null,0,null,null);
    }

    @Test void requiresExactCompletedDatabaseForUpgradeAndNeverAllowsQuarantine() {
        assertThatCode(()->DisasterRecoveryLicenseReconciliation.verifyDatabase(state,row("COMPLETE"),true)).doesNotThrowAnyException();
        for(String phase:List.of("ACTIVE","QUARANTINED","CANCELLED"))
            assertThatThrownBy(()->DisasterRecoveryLicenseReconciliation.verifyDatabase(state,row(phase),true)).hasMessageContaining("database proof");
        assertThatCode(()->DisasterRecoveryLicenseReconciliation.verifyDatabase(state,row("ACTIVE"),false)).doesNotThrowAnyException();
        state.put("backupId",UUID.randomUUID().toString());
        assertThatThrownBy(()->DisasterRecoveryLicenseReconciliation.verifyDatabase(state,row("COMPLETE"),true)).hasMessageContaining("database proof");
    }

    @Test void rejectsForeignRecoveryAndDestinationBeforeCallingLicensing() {
        assertThatCode(()->DisasterRecoveryLicenseReconciliation.verifyAuthority(state,snapshot(recovery,"b".repeat(64)))).doesNotThrowAnyException();
        assertThatThrownBy(()->DisasterRecoveryLicenseReconciliation.verifyAuthority(state,snapshot(UUID.randomUUID(),"b".repeat(64)))).hasMessageContaining("authority binding");
        assertThatThrownBy(()->DisasterRecoveryLicenseReconciliation.verifyAuthority(state,snapshot(recovery,"c".repeat(64)))).hasMessageContaining("authority binding");
    }

    @Test void unavailableAuthorityDoesNotWriteLicenseOrDatabaseState()throws Exception {
        var jdbc=mock(JdbcTemplate.class);var credentials=mock(LicenseSaasCredentialStore.class);var licenses=mock(LicenseRecoveryReconciler.class);
        when(jdbc.queryForMap(anyString(),eq(recovery))).thenReturn(row("COMPLETE"));when(credentials.readToken()).thenReturn(Optional.of("activated-token"));
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(3072);
        state.put("destinationPrivate",generator.generateKeyPair().getPrivate().getEncoded());
        int closedPort;
        try(var reservation=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){closedPort=reservation.getLocalPort();}
        var service=new DisasterRecoveryLicenseReconciliation(Path.of("unused"),jdbc,credentials,licenses,"https://localhost:"+closedPort);
        assertThatThrownBy(()->service.reconcile(state,true)).isInstanceOf(Exception.class);
        verifyNoInteractions(licenses);verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
