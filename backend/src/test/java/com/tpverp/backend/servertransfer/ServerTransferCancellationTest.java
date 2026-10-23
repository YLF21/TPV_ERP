package com.tpverp.backend.servertransfer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.installation.InstallationRepository;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;

class ServerTransferCancellationTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;ServerTransferSaasClient central;ServerTransferJournal journal;ServerTransferService service;
    UUID id=UUID.randomUUID(),installation=UUID.randomUUID();
    @BeforeEach void prepare(){
        jdbc=mock(JdbcTemplate.class);central=mock(ServerTransferSaasClient.class);journal=new ServerTransferJournal(directory);journal.freeze(id);
        var local=new ServerTransferService.Status(id,installation,"FROZEN","a".repeat(64),null);
        doReturn(List.of(local)).when(jdbc).query(anyString(),any(RowMapper.class));
        service=new ServerTransferService(jdbc,mock(PlatformTransactionManager.class),mock(InstallationRepository.class),journal,new ServerTransferFence(journal),central,mock(ServerTransferBackupPort.class),mock(ServerTransferSecrets.class),new ObjectMapper());
    }
    @Test void timeoutNeverReopensSource(){
        when(central.cancel(id,installation)).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(()->service.cancel(id)).hasMessageContaining("timeout");assertThat(journal.frozen()).isTrue();
        verify(jdbc,never()).update("update server_transfer set state='CANCELLED' where id=?",id);
    }
    @Test void activatedOrForeignCentralResultNeverReopensSource(){
        when(central.cancel(id,installation)).thenReturn(new ServerTransferSaasClient.Status(id,installation,"ACTIVATED","a".repeat(64),null,null));
        assertThatThrownBy(()->service.cancel(id)).hasMessageContaining("TRANSFER_CENTRAL_PROOF_INVALID");assertThat(journal.frozen()).isTrue();
        when(central.cancel(id,installation)).thenReturn(new ServerTransferSaasClient.Status(UUID.randomUUID(),installation,"CANCELLED","a".repeat(64),null,null));
        assertThatThrownBy(()->service.cancel(id)).hasMessageContaining("TRANSFER_CENTRAL_PROOF_INVALID");assertThat(journal.frozen()).isTrue();
    }
    @Test void exactCentralCancellationProofReleasesPersistentFence(){
        when(central.cancel(id,installation)).thenReturn(new ServerTransferSaasClient.Status(id,installation,"CANCELLED","a".repeat(64),null,null));
        service.cancel(id);assertThat(journal.frozen()).isFalse();verify(jdbc).update("update server_transfer set state='CANCELLED' where id=?",id);
    }
    @Test void unfinishedPreparationSuppliesDestinationForDurableCentralCancellation(){
        journal.releaseAfterCentralCancellation(id);
        doReturn(List.of(new ServerTransferService.Status(id,installation,"PREPARED","a".repeat(64),null))).when(jdbc).query(anyString(),any(RowMapper.class));
        when(jdbc.queryForObject("select destination_public_key from server_transfer where id=?",String.class,id)).thenReturn("persisted-destination-key");
        when(central.cancel(id,installation,"persisted-destination-key")).thenReturn(new ServerTransferSaasClient.Status(id,installation,"CANCELLED","a".repeat(64),null,null));
        service.cancel(id);
        verify(central).cancel(id,installation,"persisted-destination-key");
        verify(jdbc).update("update server_transfer set state='CANCELLED' where id=?",id);
        assertThat(journal.frozen()).isFalse();
    }
}
