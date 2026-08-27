package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.party.MemberRepository;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberBalanceCheckoutProtocolServiceTest {

    @Test
    void releasedReservationCannotBeBoundToAnAlreadyFinalizedTicket() {
        var reservations = mock(LocalMemberBalanceReservationRepository.class);
        var reservation = mock(LocalMemberBalanceReservation.class);
        UUID reservationId = UUID.randomUUID();
        when(reservations.findForUpdate(reservationId)).thenReturn(Optional.of(reservation));
        when(reservation.getStatus()).thenReturn(LocalMemberBalanceReservationStatus.RELEASED);
        var service = new MemberBalanceCheckoutProtocolService(
                reservations,
                mock(MemberBalanceReservationCoordinator.class),
                mock(MemberRepository.class),
                Clock.systemUTC());

        assertThatThrownBy(() -> service.markTicketCommitted(
                reservationId, UUID.randomUUID()))
                .isInstanceOf(MemberBalanceManualReconciliationRequiredException.class)
                .hasMessageContaining("RELEASED");
    }
}
