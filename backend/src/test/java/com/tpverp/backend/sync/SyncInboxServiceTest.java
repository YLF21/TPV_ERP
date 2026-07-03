package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.MemberLoyaltyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncInboxServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private SyncInboxEventRepository repository;
    @Mock
    private MemberLoyaltyService members;

    @Test
    void registraEventoNuevoComoPendienteDeProceso() {
        SyncInboxService service = new SyncInboxService(repository, members, clock);
        UUID eventId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        when(repository.findByEventId(eventId)).thenReturn(Optional.empty());

        SyncInboxReceipt receipt = service.receive(new SyncInboundEventRequest(
                eventId, companyId, storeId, "DOCUMENTO", UUID.randomUUID(),
                SyncOperation.CONFIRMAR, Map.of("numero", "T-1")));

        assertThat(receipt.result()).isEqualTo(SyncInboxResult.OK);
        assertThat(receipt.eventId()).isEqualTo(eventId);
        var event = ArgumentCaptor.forClass(SyncInboxEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().getEventId()).isEqualTo(eventId);
        assertThat(event.getValue().getCompanyId()).isEqualTo(companyId);
        assertThat(event.getValue().getStoreId()).isEqualTo(storeId);
        assertThat(event.getValue().isProcessed()).isFalse();
        assertThat(event.getValue().getReceivedAt()).isEqualTo(clock.instant());
    }

    @Test
    void recibirMismoEventIdNoDuplica() {
        SyncInboxService service = new SyncInboxService(repository, members, clock);
        UUID eventId = UUID.randomUUID();
        when(repository.findByEventId(eventId)).thenReturn(Optional.of(new SyncInboxEvent(
                eventId, UUID.randomUUID(), null, clock.instant())));

        SyncInboxReceipt receipt = service.receive(new SyncInboundEventRequest(
                eventId, UUID.randomUUID(), null, "DOCUMENTO", UUID.randomUUID(),
                SyncOperation.CONFIRMAR, Map.of()));

        assertThat(receipt.result()).isEqualTo(SyncInboxResult.DUPLICADO);
        verify(repository, never()).save(any());
    }

    @Test
    void aplicaEstadoOficialDeMiembroYMarcaEventoProcesado() {
        SyncInboxService service = new SyncInboxService(repository, members, clock);
        UUID eventId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(repository.findByEventId(eventId)).thenReturn(Optional.empty());

        SyncInboxReceipt receipt = service.receive(new SyncInboundEventRequest(
                eventId, UUID.randomUUID(), null, "MEMBER_OFFICIAL_STATE", memberId,
                SyncOperation.ACTUALIZAR, Map.of(
                "balance", "12.50",
                "points", 25,
                "categoryId", categoryId.toString(),
                "syncedAt", "2026-06-30T12:00:00Z")));

        assertThat(receipt.result()).isEqualTo(SyncInboxResult.OK);
        var command = ArgumentCaptor.forClass(MemberLoyaltyService.OfficialMemberStateCommand.class);
        verify(members).applyOfficialState(command.capture());
        assertThat(command.getValue().sourceEventId()).isEqualTo(eventId);
        assertThat(command.getValue().memberId()).isEqualTo(memberId);
        assertThat(command.getValue().balance()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(command.getValue().points()).isEqualTo(25);
        assertThat(command.getValue().categoryId()).isEqualTo(categoryId);
        var event = ArgumentCaptor.forClass(SyncInboxEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().isProcessed()).isTrue();
        assertThat(event.getValue().getResult()).isEqualTo(SyncInboxResult.OK);
    }

    @Test
    void falloAplicandoEstadoOficialSeGuardaComoError() {
        SyncInboxService service = new SyncInboxService(repository, members, clock);
        UUID eventId = UUID.randomUUID();
        when(repository.findByEventId(eventId)).thenReturn(Optional.empty());
        doThrow(new IllegalArgumentException("miembro no encontrado"))
                .when(members).applyOfficialState(any());

        SyncInboxReceipt receipt = service.receive(new SyncInboundEventRequest(
                eventId, UUID.randomUUID(), null, "MEMBER_OFFICIAL_STATE", UUID.randomUUID(),
                SyncOperation.ACTUALIZAR, Map.of(
                "balance", "12.50",
                "points", 25,
                "syncedAt", "2026-06-30T12:00:00Z")));

        assertThat(receipt.result()).isEqualTo(SyncInboxResult.ERROR);
        var event = ArgumentCaptor.forClass(SyncInboxEvent.class);
        verify(repository).save(event.capture());
        assertThat(event.getValue().isProcessed()).isTrue();
        assertThat(event.getValue().getResult()).isEqualTo(SyncInboxResult.ERROR);
    }
}
