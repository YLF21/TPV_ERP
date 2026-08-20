package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalMemberBalanceReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private final UUID storeId = UUID.randomUUID();
    private final UUID terminalId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private LocalMemberBalanceReservationRepository reservations;
    private MemberBalanceReservationCoordinator coordinator;
    private LocalMemberBalanceReservationService service;

    @BeforeEach
    void setUp() {
        reservations = mock(LocalMemberBalanceReservationRepository.class);
        TerminalRepository terminals = mock(TerminalRepository.class);
        coordinator = mock(MemberBalanceReservationCoordinator.class);
        when(terminals.findByIdAndTiendaId(terminalId, storeId)).thenReturn(Optional.of(mock(Terminal.class)));
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new LocalMemberBalanceReservationService(
                reservations,
                terminals,
                coordinator,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void persisteLaReservaCentralConSuLease() {
        when(reservations.findFirstByStoreIdAndTerminalIdAndSaleIdOrderByCreatedAtDesc(
                storeId, terminalId, "sale-1")).thenReturn(Optional.empty());
        when(coordinator.reserve(storeId, terminalId, memberId, "sale-1"))
                .thenReturn(central("ACTIVE"));

        LocalMemberBalanceReservation reservation = service.reserve(
                storeId, terminalId, memberId, "sale-1");

        assertThat(reservation.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.ACTIVE);
        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("10.00");
        assertThat(reservation.getLeaseExpiresAt()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void liberacionSinConexionQuedaPendienteSinBloquearElCierreLocal() {
        LocalMemberBalanceReservation reservation = LocalMemberBalanceReservation.create(
                storeId, terminalId, memberId, "sale-1", central("ACTIVE"), NOW);
        when(reservations.findForUpdate(reservation.getId())).thenReturn(Optional.of(reservation));
        when(coordinator.release(
                reservation.getCentralReservationId(), storeId, terminalId, "sale-1"))
                .thenThrow(new MemberBalanceCentralException(
                        MemberBalanceCentralException.Kind.UNAVAILABLE,
                        "sin conexion"));

        LocalMemberBalanceReservation released = service.release(
                reservation.getId(), storeId, terminalId, "sale-1");

        assertThat(released.getStatus()).isEqualTo(LocalMemberBalanceReservationStatus.RELEASE_PENDING);
    }

    private MemberBalanceCentralGateway.ReservationResponse central(String status) {
        return new MemberBalanceCentralGateway.ReservationResponse(
                UUID.randomUUID(),
                memberId,
                status,
                new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2),
                null,
                BigDecimal.ZERO.setScale(2),
                new BigDecimal("10.00"),
                NOW,
                NOW.plusSeconds(120),
                30,
                120);
    }
}
