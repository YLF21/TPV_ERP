package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberPointsOperation;
import com.tpverp.backend.party.MemberPointsOperationRepository;
import com.tpverp.backend.party.MemberPointsOperationType;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberPointsSyncPublisherTest {
    @Test
    void publicaFechaIsoExactaAunqueElSerializadorUseTimestamps() throws Exception {
        var operations = mock(MemberPointsOperationRepository.class);
        var outbox = mock(SyncOutboxService.class);
        var member = mock(Member.class);
        when(member.getId()).thenReturn(UUID.randomUUID());
        var occurred = Instant.parse("2026-08-26T13:37:50.975587801Z");
        var operation = new MemberPointsOperation(UUID.randomUUID(), member, UUID.randomUUID(),
                UUID.randomUUID(), 7L, MemberPointsOperationType.SALE_EARN, 10,
                UUID.randomUUID(), null, occurred, 10, 0, "checkpoint-1");
        when(operations.insertIfAbsent(operation.getOperationId(), member.getId(), operation.getCompanyId(),
                operation.getStoreId(), 7L, "SALE_EARN", 10, operation.getSourceDocumentId(), null,
                occurred, 10, 0, "checkpoint-1", operation.getPayloadHash())).thenReturn(1);

        new MemberPointsSyncPublisher(operations, outbox).publishCreated(operation);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().payload().get("occurredAt")).isEqualTo(occurred.toString());
        var mapper = JsonMapper.builder().findAndAddModules().build();
        var persisted = mapper.readTree(mapper.writeValueAsString(command.getValue().payload()));
        assertThat(persisted.get("occurredAt").isTextual()).isTrue();
        assertThat(Instant.parse(persisted.get("occurredAt").textValue())).isEqualTo(occurred);
        assertThat(command.getValue().entityId()).isEqualTo(operation.getOperationId());
        assertThat(command.getValue().storeSequence()).isEqualTo(7L);
    }
}
