package com.tpverp.backend.party.loyalty.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotType;
import com.tpverp.backend.party.MemberMovement;
import com.tpverp.backend.party.PartyContext;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboundEventCommand;
import com.tpverp.backend.sync.SyncOutboxEvent;
import com.tpverp.backend.sync.SyncOutboxService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MemberWalletSyncPublisherTest {

    @Test
    void publicaInstantesIsoYDespiertaDespuesDeEncolar() {
        Instant createdAt = Instant.parse("2026-08-29T15:44:00.320545700Z");
        Instant expiresAt = Instant.parse("2027-08-29T15:44:00.000000001Z");
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        var company = mock(Company.class);
        var store = mock(Store.class);
        var context = mock(PartyContext.class);
        when(company.getId()).thenReturn(companyId);
        when(store.getId()).thenReturn(storeId);
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        var member = mock(Member.class);
        when(member.getId()).thenReturn(UUID.randomUUID());
        var movement = mock(MemberMovement.class);
        when(movement.getId()).thenReturn(UUID.randomUUID());
        var lot = mock(MemberBalanceLot.class);
        when(lot.getMember()).thenReturn(member);
        when(lot.getBalanceType()).thenReturn(MemberBalanceLotType.LOYALTY);
        when(lot.getAmountOriginal()).thenReturn(new BigDecimal("0.04"));
        when(lot.getCreatedAt()).thenReturn(createdAt);
        when(lot.getExpiresAt()).thenReturn(expiresAt);
        when(lot.getSourceMovement()).thenReturn(movement);
        when(lot.getDocumentId()).thenReturn(UUID.randomUUID());
        when(lot.getId()).thenReturn(UUID.randomUUID());
        var outbox = mock(SyncOutboxService.class);
        var queued = mock(SyncOutboxEvent.class);
        when(queued.getEventId()).thenReturn(eventId);
        when(outbox.enqueue(any())).thenReturn(queued);
        var events = mock(ApplicationEventPublisher.class);
        var publisher = new MemberWalletSyncPublisher(outbox, context, events);

        publisher.publishCreated(lot);

        var command = ArgumentCaptor.forClass(SyncOutboundEventCommand.class);
        verify(outbox).enqueue(command.capture());
        assertThat(command.getValue().operation()).isEqualTo(SyncOperation.CREAR);
        assertThat(command.getValue().payload().get("createdAt")).isEqualTo(createdAt.toString());
        assertThat(command.getValue().payload().get("expiresAt")).isEqualTo(expiresAt.toString());
        var order = inOrder(outbox, events);
        order.verify(outbox).enqueue(any());
        order.verify(events).publishEvent(new MemberWalletLotSyncRequested(eventId));
    }

    @Test
    void priorizaElWakeDeCreditoDevolucionTrasEncolarlo() {
        var context = mock(PartyContext.class);
        var company = mock(Company.class);
        var store = mock(Store.class);
        when(company.getId()).thenReturn(UUID.randomUUID());
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(context.currentCompany()).thenReturn(company);
        when(context.currentStore()).thenReturn(store);
        var member = mock(Member.class);
        when(member.getId()).thenReturn(UUID.randomUUID());
        var movement = mock(MemberMovement.class);
        when(movement.getId()).thenReturn(UUID.randomUUID());
        var lot = mock(MemberBalanceLot.class);
        when(lot.getMember()).thenReturn(member);
        when(lot.getBalanceType()).thenReturn(MemberBalanceLotType.RETURN_CREDIT);
        when(lot.getAmountOriginal()).thenReturn(new BigDecimal("10.00"));
        when(lot.getCreatedAt()).thenReturn(Instant.parse("2026-08-29T15:44:00Z"));
        when(lot.getExpiresAt()).thenReturn(null);
        when(lot.getSourceMovement()).thenReturn(movement);
        when(lot.getDocumentId()).thenReturn(UUID.randomUUID());
        when(lot.getId()).thenReturn(UUID.randomUUID());
        var outbox = mock(SyncOutboxService.class);
        var queued = mock(SyncOutboxEvent.class);
        var eventId = UUID.randomUUID();
        when(queued.getEventId()).thenReturn(eventId);
        when(outbox.enqueue(any())).thenReturn(queued);
        var events = mock(ApplicationEventPublisher.class);

        new MemberWalletSyncPublisher(outbox, context, events).publishCreated(lot);

        verify(events).publishEvent(new MemberReturnCreditSyncRequested(eventId));
    }
}
