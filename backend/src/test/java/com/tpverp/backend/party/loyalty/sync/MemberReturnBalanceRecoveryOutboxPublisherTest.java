package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.loyalty.central.MemberBalanceCentralGateway;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxService;
import com.tpverp.backend.sync.SyncOutboxEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MemberReturnBalanceRecoveryOutboxPublisherTest {

    @Test
    void publicaPayloadCanonicoYLaIdentidadDeReservaCompleta() {
        var outbox = mock(SyncOutboxService.class);
        var publisher = new MemberReturnBalanceRecoveryOutboxPublisher(outbox);
        UUID operationId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID terminalId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        var claim = new MemberBalanceCentralGateway.RetentionClaim(
                lotId, movementId, sourceDocumentId,
                new BigDecimal("1.00"), new BigDecimal("0.22"));
        String fingerprint = com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner.fingerprint(
                sourceDocumentId, new BigDecimal("0.22"), List.of(claim));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, terminalId, memberId,
                sourceDocumentId, returnDocumentId, new BigDecimal("0.22"),
                fingerprint, List.of(claim),
                new MemberReturnBalanceRecoveryCommand.ReservationIdentity(
                        reservationId, operationId.toString()));
        var queued = new SyncOutboxEvent(
                companyId, storeId, terminalId,
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                operationId, SyncOperation.CONFIRMAR, Map.of(), Instant.now());
        when(outbox.enqueue(any(SyncOutboundEventCommand.class))).thenReturn(queued);

        assertThat(publisher.publish(command)).isSameAs(queued);

        var captor = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(captor.capture());
        var event = captor.getValue();
        assertThat(event.entityType()).isEqualTo("MEMBER_RETURN_BALANCE_RECOVERY");
        assertThat(event.entityId()).isEqualTo(operationId);
        assertThat(event.operation()).isEqualTo(SyncOperation.CONFIRMAR);
        assertThat(event.companyId()).isEqualTo(companyId);
        assertThat(event.storeId()).isEqualTo(storeId);
        assertThat(event.terminalId()).isEqualTo(terminalId);
        assertThat(event.payload())
                .containsEntry("schemaVersion", 1)
                .containsEntry("companyId", companyId.toString())
                .containsEntry("storeId", storeId.toString())
                .containsEntry("memberId", memberId.toString())
                .containsEntry("sourceDocumentId", sourceDocumentId.toString())
                .containsEntry("returnDocumentId", returnDocumentId.toString())
                .containsEntry("attributedAmount", new BigDecimal("0.22"))
                .containsEntry("claimsFingerprint", fingerprint)
                .containsEntry("reservationId", reservationId.toString())
                .containsEntry("reservationSaleId", operationId.toString());
        assertThat(event.payload().get("claims")).isEqualTo(List.of(java.util.Map.of(
                "lotId", lotId.toString(),
                "sourceMovementId", movementId.toString(),
                "sourceDocumentId", sourceDocumentId.toString(),
                "amountOriginal", new BigDecimal("1.00"),
                "amount", new BigDecimal("0.22"))));
        var canonical = publisher.canonicalPayload(command);
        assertThatThrownBy(() -> canonical.put("operation", "CONFIRMAR"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void omiteLaReservaCuandoElComandoNoLaIncluye() {
        var outbox = mock(SyncOutboxService.class);
        var publisher = new MemberReturnBalanceRecoveryOutboxPublisher(outbox);
        UUID sourceDocumentId = UUID.randomUUID();
        String fingerprint = com.tpverp.backend.party.loyalty.central.MemberReturnBalanceRetentionPlanner.fingerprint(
                sourceDocumentId, new BigDecimal("0.00"), List.of());
        var command = new MemberReturnBalanceRecoveryCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                UUID.randomUUID(), sourceDocumentId, UUID.randomUUID(),
                new BigDecimal("0.00"), fingerprint, List.of(), null);
        when(outbox.enqueue(any(SyncOutboundEventCommand.class))).thenReturn(new SyncOutboxEvent(
                command.companyId(), command.storeId(), null,
                MemberReturnBalanceRecoveryOutboxPublisher.ENTITY_TYPE,
                command.operationId(), SyncOperation.CONFIRMAR, Map.of(), Instant.now()));

        publisher.publish(command);

        var captor = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(captor.capture());
        assertThat(captor.getValue().payload())
                .doesNotContainKeys("reservationId", "reservationSaleId")
                .containsEntry("claims", List.of());
    }

}
