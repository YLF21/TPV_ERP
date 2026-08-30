package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.tpverp.backend.party.Member;
import com.tpverp.backend.party.MemberBalanceLot;
import com.tpverp.backend.party.MemberBalanceLotRepository;
import com.tpverp.backend.party.MemberRepository;
import com.tpverp.backend.party.MemberBalanceLotType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DevMemberBalanceCentralGatewayTest {

    @Test
    void configureRequestRejectsClaimFromAnotherSourceDocument() {
        UUID sourceDocumentId = UUID.randomUUID();
        assertThatThrownBy(() -> new MemberBalanceCentralGateway.ConfigureRetentionRequest(
                UUID.randomUUID(), UUID.randomUUID(), "terminal-1", "sale-1", UUID.randomUUID(),
                sourceDocumentId, new BigDecimal("1.00"),
                List.of(new MemberBalanceCentralGateway.RetentionClaim(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        new BigDecimal("1.00"), new BigDecimal("1.00")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void abortPreparedIsIdempotentAndPreservesOperationIdentity() {
        var lots = mock(MemberBalanceLotRepository.class);
        var members = mock(MemberRepository.class);
        var localReservations = mock(LocalMemberBalanceReservationRepository.class);
        var memberId = UUID.randomUUID();
        var lot = mock(MemberBalanceLot.class);
        when(lot.getId()).thenReturn(UUID.randomUUID());
        when(lot.getBalanceType()).thenReturn(MemberBalanceLotType.LOYALTY);
        when(lot.getAmountRemaining()).thenReturn(new BigDecimal("10.00"));
        when(lot.getCreatedAt()).thenReturn(Instant.parse("2026-01-01T00:00:00Z"));
        when(lot.getExpiredAt()).thenReturn(null);
        when(lot.getExpiresAt()).thenReturn(null);
        when(lot.getSourceMovement()).thenReturn(null);
        when(lot.getDocumentId()).thenReturn(null);
        when(lots.findByMemberIdAndAmountRemainingGreaterThan(eq(memberId), any(BigDecimal.class)))
                .thenReturn(List.of(lot));
        when(members.findById(memberId)).thenReturn(Optional.of(mock(Member.class)));
        var gateway = new DevMemberBalanceCentralGateway(
                lots, members, localReservations,
                Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC));
        var companyId = UUID.randomUUID();
        var storeId = UUID.randomUUID();
        var reservation = gateway.reserve(new MemberBalanceCentralGateway.ReserveRequest(
                companyId, storeId, memberId, "terminal-1", "sale-1"));
        var operationId = UUID.randomUUID();
        var prepared = gateway.prepare(reservation.reservationId(),
                new MemberBalanceCentralGateway.PrepareRequest(
                        companyId, storeId, "terminal-1", "sale-1", operationId,
                        new BigDecimal("10.00"), BigDecimal.ZERO));
        var owner = new MemberBalanceCentralGateway.PreparedOwnerRequest(
                companyId, storeId, "terminal-1", "sale-1", operationId);

        var first = gateway.abortPrepared(prepared.reservationId(), owner);
        var second = gateway.abortPrepared(prepared.reservationId(), owner);

        assertThat(first.status()).isEqualTo("RELEASED");
        assertThat(second.status()).isEqualTo("RELEASED");
        assertThat(second.prepareOperationId()).isEqualTo(operationId);
    }
}
