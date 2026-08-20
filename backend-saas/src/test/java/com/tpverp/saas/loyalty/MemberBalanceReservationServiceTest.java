package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.web.server.ResponseStatusException;

class MemberBalanceReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final UUID COMPANY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID INSTALLATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private SaasInstallationRepository installations;
    private SaasMemberLoyaltyBootstrapRepository bootstraps;
    private SaasMemberWalletBootstrapRepository walletBootstraps;
    private SaasMemberBalanceAccountRepository accounts;
    private SaasMemberBalanceLotRepository lots;
    private SaasMemberBalanceReservationRepository reservations;
    private SaasMemberBalanceReservationLotRepository reservationLots;
    private MemberBalanceReservationService service;
    private SaasInstallation installation;

    @BeforeEach
    void setUp() {
        installations = mock(SaasInstallationRepository.class);
        InstallationAuthenticator authenticator = mock(InstallationAuthenticator.class);
        bootstraps = mock(SaasMemberLoyaltyBootstrapRepository.class);
        walletBootstraps = mock(SaasMemberWalletBootstrapRepository.class);
        accounts = mock(SaasMemberBalanceAccountRepository.class);
        lots = mock(SaasMemberBalanceLotRepository.class);
        reservations = mock(SaasMemberBalanceReservationRepository.class);
        reservationLots = mock(SaasMemberBalanceReservationLotRepository.class);
        EntityManager entityManager = mock(EntityManager.class);
        installation = mock(SaasInstallation.class);
        SaasStore store = mock(SaasStore.class);
        when(installation.getId()).thenReturn(INSTALLATION_ID);
        when(installation.getStore()).thenReturn(store);
        when(store.getId()).thenReturn(STORE_ID);
        when(installations.findByCompany_Id(COMPANY_ID)).thenReturn(List.of(installation));
        when(authenticator.requireLinkedInstallation(any(), any(), any(), any()))
                .thenReturn(installation);
        SaasMemberLoyaltyBootstrap bootstrap = mock(SaasMemberLoyaltyBootstrap.class);
        when(bootstrap.isCompleted()).thenReturn(true);
        when(bootstraps.findById(COMPANY_ID)).thenReturn(Optional.of(bootstrap));
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationLots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        service = new MemberBalanceReservationService(
                installations,
                authenticator,
                bootstraps,
                walletBootstraps,
                accounts,
                lots,
                reservations,
                reservationLots,
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void checksumEconomicoNoDependeDelInstanteDelSnapshot() {
        LoyaltyApiModels.BootstrapAccount account = new LoyaltyApiModels.BootstrapAccount(
                MEMBER_ID,
                new BigDecimal("10.00"),
                new BigDecimal("3.5000"),
                List.of(new LoyaltyApiModels.BootstrapLot(
                        UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        new BigDecimal("10.00"),
                        NOW.minusSeconds(60),
                        NOW.plus(Duration.ofDays(1)),
                        null)));
        LoyaltyApiModels.BootstrapRequest first = new LoyaltyApiModels.BootstrapRequest(
                COMPANY_ID, STORE_ID, NOW.minusSeconds(30), "ignored", List.of(account));
        LoyaltyApiModels.BootstrapRequest second = new LoyaltyApiModels.BootstrapRequest(
                COMPANY_ID, STORE_ID, NOW, "ignored", List.of(account));

        assertThat(service.calculateChecksum(first)).isEqualTo(service.calculateChecksum(second));
    }

    @Test
    void bloqueoPesimistaProtegeLaCuentaAntesDeReservar() throws Exception {
        Lock lock = SaasMemberBalanceAccountRepository.class
                .getMethod("findForUpdate", UUID.class, UUID.class)
                .getAnnotation(Lock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void rechazaReservaCuandoOtraCajaMantieneElLease() {
        SaasMemberBalanceAccount account = account("10.00");
        SaasMemberBalanceReservation active = new SaasMemberBalanceReservation(
                UUID.randomUUID(),
                account,
                STORE_ID,
                UUID.randomUUID(),
                "CAJA-2",
                "VENTA-2",
                new BigDecimal("10.00"),
                NOW.minusSeconds(30),
                Duration.ofSeconds(120));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservations.findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
                account.getId(), List.of(
                        SaasMemberBalanceReservation.ACTIVE,
                        SaasMemberBalanceReservation.PREPARED))).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.reserve(reserveRequest("CAJA-1", "VENTA-1"), "token"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void leaseCaducadoPermiteCrearNuevaReserva() {
        SaasMemberBalanceAccount account = account("10.00");
        SaasMemberBalanceReservation expired = new SaasMemberBalanceReservation(
                UUID.randomUUID(),
                account,
                STORE_ID,
                UUID.randomUUID(),
                "CAJA-2",
                "VENTA-2",
                new BigDecimal("10.00"),
                NOW.minusSeconds(180),
                Duration.ofSeconds(120));
        SaasMemberBalanceLot lot = lot(account, "10.00", NOW.minusSeconds(600));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservations.findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
                account.getId(), List.of(
                        SaasMemberBalanceReservation.ACTIVE,
                        SaasMemberBalanceReservation.PREPARED))).thenReturn(Optional.of(expired));
        when(lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId())).thenReturn(List.of(lot));

        LoyaltyApiModels.ReservationResponse response = service.reserve(
                reserveRequest("CAJA-1", "VENTA-1"), "token");

        assertThat(expired.getStatus()).isEqualTo(SaasMemberBalanceReservation.EXPIRED);
        assertThat(response.status()).isEqualTo(SaasMemberBalanceReservation.ACTIVE);
        assertThat(response.reservedTotal()).isEqualByComparingTo("10.00");
        assertThat(response.leaseSeconds()).isEqualTo(120);
    }

    @Test
    void consumoParcialDescuentaLotesEnOrdenFifoYLiberaElResto() {
        SaasMemberBalanceAccount account = account("10.00");
        SaasMemberBalanceLot oldest = lot(account, "4.00", NOW.minusSeconds(600));
        SaasMemberBalanceLot newest = lot(account, "6.00", NOW.minusSeconds(300));
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(),
                account,
                STORE_ID,
                INSTALLATION_ID,
                "CAJA-1",
                "VENTA-1",
                new BigDecimal("10.00"),
                NOW.minusSeconds(30),
                Duration.ofSeconds(120));
        SaasMemberBalanceReservationLot first = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, oldest, new BigDecimal("4.00"));
        SaasMemberBalanceReservationLot second = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, newest, new BigDecimal("6.00"));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(first, second));

        UUID operationId = UUID.randomUUID();
        service.prepare(
                reservation.getId(),
                new LoyaltyApiModels.PrepareRequest(
                        COMPANY_ID, STORE_ID, "CAJA-1", "VENTA-1", operationId, new BigDecimal("5.00")),
                "token");
        LoyaltyApiModels.ReservationResponse response = service.finalizePrepared(
                reservation.getId(),
                new LoyaltyApiModels.PreparedOwnerRequest(
                        COMPANY_ID, STORE_ID, "CAJA-1", "VENTA-1", operationId),
                "token");

        assertThat(oldest.getRemainingAmount()).isEqualByComparingTo("0.00");
        assertThat(newest.getRemainingAmount()).isEqualByComparingTo("5.00");
        assertThat(account.getBalance()).isEqualByComparingTo("5.00");
        assertThat(response.status()).isEqualTo(SaasMemberBalanceReservation.CONSUMED);
        assertThat(response.consumedTotal()).isEqualByComparingTo("5.00");
    }

    private SaasMemberBalanceAccount account(String balance) {
        return new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, new BigDecimal(balance), BigDecimal.ZERO, NOW);
    }

    private SaasMemberBalanceLot lot(SaasMemberBalanceAccount account, String amount, Instant createdAt) {
        return new SaasMemberBalanceLot(
                UUID.randomUUID(), account, new BigDecimal(amount), createdAt, NOW.plus(Duration.ofDays(1)), null);
    }

    private LoyaltyApiModels.ReserveRequest reserveRequest(String terminalId, String saleId) {
        return new LoyaltyApiModels.ReserveRequest(
                COMPANY_ID, STORE_ID, MEMBER_ID, terminalId, saleId);
    }
}
