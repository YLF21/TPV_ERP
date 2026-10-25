package com.tpverp.backend.disasterrecovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecoveryCompletedSecretMaintenanceTest {
    private final UUID recovery=UUID.randomUUID(),installation=UUID.randomUUID(),backup=UUID.randomUUID();
    private ObjectNode journal(String state) {
        return new ObjectMapper().createObjectNode().put("state",state).put("recoveryId",recovery.toString())
                .put("installationId",installation.toString()).put("backupId",backup.toString()).put("backupSha256","a".repeat(64));
    }
    @Test void refusesPendingOrCancelledRecoveryBeforeDatabaseOrPermissionChanges()throws Exception {
        var db=mock(Connection.class);
        for(String phase:new String[]{"NONE","QUARANTINED","ACTIVATED","CANCELLED"})
            assertThatThrownBy(()->OfflineDisasterRecoveryCli.verifyCompletedRecovery(db,journal(phase)))
                    .isInstanceOf(IOException.class).hasMessageContaining("COMPLETE");
        verifyNoInteractions(db);
    }
    @Test void acceptsOnlyTheSameCompletedRecoveryAndInstallation()throws Exception {
        var db=mock(Connection.class);var query=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(db.prepareStatement(anyString())).thenReturn(query);when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true,false);when(rows.getString(1)).thenReturn("COMPLETE");
        when(rows.getObject(2,UUID.class)).thenReturn(installation);when(rows.getObject(3,UUID.class)).thenReturn(backup);
        when(rows.getString(4)).thenReturn("a".repeat(64));
        var statement=mock(Statement.class);var installations=mock(ResultSet.class);
        when(db.createStatement()).thenReturn(statement);when(statement.executeQuery("select id from instalacion")).thenReturn(installations);
        when(installations.next()).thenReturn(true,false);when(installations.getObject(1,UUID.class)).thenReturn(installation);
        OfflineDisasterRecoveryCli.verifyCompletedRecovery(db,journal("COMPLETE"));
        verify(query).setObject(1,recovery);verify(db,never()).commit();
    }
    @Test void rejectsMismatchedBackupWithoutReachingInstallationOrChangingData()throws Exception {
        var db=mock(Connection.class);var query=mock(PreparedStatement.class);var rows=mock(ResultSet.class);
        when(db.prepareStatement(anyString())).thenReturn(query);when(query.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);when(rows.getString(1)).thenReturn("COMPLETE");
        when(rows.getObject(2,UUID.class)).thenReturn(installation);when(rows.getObject(3,UUID.class)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(()->OfflineDisasterRecoveryCli.verifyCompletedRecovery(db,journal("COMPLETE")))
                .isInstanceOf(IOException.class).hasMessageContaining("does not match");
        verify(db,never()).createStatement();verify(db,never()).commit();
    }
}
