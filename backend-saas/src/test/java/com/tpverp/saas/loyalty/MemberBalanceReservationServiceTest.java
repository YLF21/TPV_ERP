package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.SaasCompany;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.sync.MemberReturnBalanceRecoveryProjector;
import com.tpverp.saas.sync.SaasSyncEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.mockito.InOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

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
    private SaasMemberBalanceRetentionClaimRepository retentionClaims;
    private SaasMemberBalanceRetentionReceiptRepository retentionReceipts;
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
        retentionClaims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        retentionReceipts = mock(SaasMemberBalanceRetentionReceiptRepository.class);
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
                retentionClaims,
                entityManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
        service.setRetentionReceipts(retentionReceipts);
    }

    @Test
    void configureRetentionLateKnownLotExpandsLinkFromFiveToEightAndKeepsSpendable() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, new BigDecimal("12.86"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), NOW);
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, new BigDecimal("10.00"),
                new BigDecimal("8.00"), NOW, null,
                movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                new BigDecimal("12.86"), NOW, Duration.ofMinutes(2));
        List<SaasMemberBalanceRetentionClaim> stored = new ArrayList<>();
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId())).thenReturn(stored);
        when(retentionClaims.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceRetentionClaim value = invocation.getArgument(0);
            if (!stored.contains(value)) stored.add(value);
            return value;
        });
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId())).thenReturn(List.of(lot));
        List<SaasMemberBalanceReservationLot> storedLinks = new ArrayList<>();
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(storedLinks);
        when(reservationLots.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceReservationLot value = invocation.getArgument(0);
            if (!storedLinks.contains(value)) storedLinks.add(value);
            return value;
        });

        LoyaltyApiModels.RetentionConfigureRequest first = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", new BigDecimal("5.00"),
                List.of(new LoyaltyApiModels.RetentionClaim(lotId, movementId, sourceDocumentId,
                        new BigDecimal("10.00"), new BigDecimal("5.00"))), sourceDocumentId);
        LoyaltyApiModels.WalletReservationResponse firstResponse = service.configureRetention(
                reservation.getId(), first, "token");
        long revision = reservation.getRetentionRevision();
        service.configureRetention(reservation.getId(), first, "token");
        assertThat(reservation.getRetentionRevision()).isEqualTo(revision);
        LoyaltyApiModels.RetentionConfigureRequest changed = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", new BigDecimal("8.00"),
                List.of(new LoyaltyApiModels.RetentionClaim(lotId, movementId,
                        sourceDocumentId, new BigDecimal("10.00"), new BigDecimal("8.00"))), sourceDocumentId);
        LoyaltyApiModels.WalletReservationResponse changedResponse = service.configureRetention(
                reservation.getId(), changed, "token");
        assertThat(reservation.getRetentionRevision()).isEqualTo(revision + 1);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getAmount()).isEqualByComparingTo("8.00");
        assertThat(storedLinks).hasSize(1);
        assertThat(storedLinks.get(0).getReservedAmount()).isEqualByComparingTo("8.00");
        assertThat(reservation.getReservedLoyaltyAmount()).isEqualByComparingTo("20.86");
        assertThat(firstResponse.spendable()).isEqualByComparingTo("12.86");
        assertThat(changedResponse.heldKnown()).isEqualByComparingTo("8.00");
        assertThat(changedResponse.spendable()).isEqualByComparingTo("12.86");
    }

    @Test
    void typedReservationKeepsReturnCreditAndSubtractsKnownRetentionFromSpendable() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("10"), money("3"), money("0"), NOW);
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("2"), money("2"), NOW,
                null, movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10"), money("3"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("2"));
        List<SaasMemberBalanceRetentionClaim> storedClaims = new ArrayList<>();
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(storedClaims);
        when(retentionClaims.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceRetentionClaim value = invocation.getArgument(0);
            if (!storedClaims.contains(value)) storedClaims.add(value);
            return value;
        });
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId())).thenReturn(List.of(lot));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));
        var request = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", money("2"),
                List.of(new LoyaltyApiModels.RetentionClaim(
                        lotId, movementId, sourceDocumentId, money("2"), money("2"))), sourceDocumentId);

        var response = service.configureRetention(reservation.getId(), request, "token");

        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("10.00");
        assertThat(reservation.getReservedReturnCreditAmount()).isEqualByComparingTo("3.00");
        assertThat(response.reservedLoyaltyAmount().add(response.reservedReturnCreditAmount()))
                .isEqualByComparingTo("13.00");
        assertThat(response.heldKnown()).isEqualByComparingTo("2.00");
        assertThat(response.spendable()).isEqualByComparingTo("11.00");
    }

    @Test
    void spendableUsesBothTypedBucketsWhenNoRetentionClaimsExist() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("17.63"),
                money("13.21"), money("4.42"), NOW);
        SaasMemberBalanceReservation returnOnly = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "return-only",
                money("0"), money("4.42"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservation mixed = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "mixed",
                money("13.21"), money("4.42"), NOW, Duration.ofMinutes(2));

        LoyaltyApiModels.WalletReservationResponse returnOnlyResponse = configureWithoutClaims(returnOnly);
        LoyaltyApiModels.WalletReservationResponse mixedResponse = configureWithoutClaims(mixed);

        assertThat(returnOnlyResponse.spendable()).isEqualByComparingTo("4.42");
        assertThat(mixedResponse.spendable()).isEqualByComparingTo("17.63");
    }

    @Test
    void reserveWalletPersistsLoyaltyOnlyInLegacyAliasAndTypedBucket() {
        WalletReservationScenario scenario = reserveWalletWithLots(MemberBalanceType.LOYALTY);

        assertWalletReservation(scenario, "13.21", "0.00", "13.21", MemberBalanceType.LOYALTY);
    }

    @Test
    void reserveWalletPersistsReturnCreditOnlyOutsideLegacyAlias() {
        WalletReservationScenario scenario = reserveWalletWithLots(MemberBalanceType.RETURN_CREDIT);

        assertWalletReservation(scenario, "0.00", "4.42", "4.42", MemberBalanceType.RETURN_CREDIT);
    }

    @Test
    void reserveWalletPersistsMixedBucketsAndSpendableTotal() {
        WalletReservationScenario scenario = reserveWalletWithLots(
                MemberBalanceType.LOYALTY, MemberBalanceType.RETURN_CREDIT);

        assertWalletReservation(scenario, "13.21", "4.42", "17.63",
                MemberBalanceType.LOYALTY, MemberBalanceType.RETURN_CREDIT);
    }

    @Test
    void reserveWalletCreatesOwnershipLockEvenWhenTheWalletHasNoBalance() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID,
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(4), NOW);
        SaasMemberWalletBootstrap walletBootstrap = completedWalletBootstrap();
        List<SaasMemberBalanceReservation> savedReservations = new ArrayList<>();
        when(walletBootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(COMPANY_ID))
                .thenReturn(Optional.of(walletBootstrap));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId()))
                .thenReturn(List.of());
        when(reservations.findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
                account.getId(), List.of(SaasMemberBalanceReservation.ACTIVE,
                        SaasMemberBalanceReservation.PREPARED)))
                .thenReturn(Optional.empty());
        when(reservations.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceReservation value = invocation.getArgument(0);
            savedReservations.add(value);
            return value;
        });
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(any(UUID.class)))
                .thenReturn(List.of());

        LoyaltyApiModels.WalletReservationResponse response = service.reserveWallet(
                new LoyaltyApiModels.ReserveRequest(COMPANY_ID, STORE_ID, MEMBER_ID,
                        "T1", "sale-empty-wallet"), "token");

        assertThat(savedReservations).hasSize(1);
        assertThat(response.reservedLoyaltyAmount()).isEqualByComparingTo("0.00");
        assertThat(response.reservedReturnCreditAmount()).isEqualByComparingTo("0.00");
        assertThat(response.spendable()).isEqualByComparingTo("0.00");
        assertThat(savedReservations.getFirst().getReservedTotal()).isEqualByComparingTo("0.00");
    }

    private WalletReservationScenario reserveWalletWithLots(MemberBalanceType... balanceTypes) {
        boolean hasLoyalty = java.util.Arrays.asList(balanceTypes).contains(MemberBalanceType.LOYALTY);
        boolean hasReturnCredit = java.util.Arrays.asList(balanceTypes).contains(MemberBalanceType.RETURN_CREDIT);
        BigDecimal loyaltyAmount = hasLoyalty ? money("13.21") : money("0");
        BigDecimal returnCreditAmount = hasReturnCredit ? money("4.42") : money("0");
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, loyaltyAmount, returnCreditAmount,
                BigDecimal.ZERO.setScale(4), NOW);
        List<SaasMemberBalanceLot> accountLots = new ArrayList<>();
        for (MemberBalanceType balanceType : balanceTypes) {
            BigDecimal amount = balanceType == MemberBalanceType.LOYALTY
                    ? loyaltyAmount : returnCreditAmount;
            accountLots.add(new SaasMemberBalanceLot(
                    UUID.randomUUID(), account, balanceType, amount, NOW,
                    NOW.plus(Duration.ofDays(1)), UUID.randomUUID(), null));
        }
        List<SaasMemberBalanceReservation> savedReservations = new ArrayList<>();
        List<SaasMemberBalanceReservationLot> savedLinks = new ArrayList<>();
        SaasMemberWalletBootstrap walletBootstrap = completedWalletBootstrap();
        when(walletBootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(COMPANY_ID))
                .thenReturn(Optional.of(walletBootstrap));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(lots.findByAccount_IdOrderByCreatedAtAscIdAsc(account.getId())).thenReturn(accountLots);
        when(reservations.findFirstByAccount_IdAndStatusInOrderByCreatedAtDesc(
                account.getId(), List.of(SaasMemberBalanceReservation.ACTIVE,
                        SaasMemberBalanceReservation.PREPARED))).thenReturn(Optional.empty());
        when(reservations.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceReservation value = invocation.getArgument(0);
            savedReservations.add(value);
            return value;
        });
        when(reservationLots.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceReservationLot value = invocation.getArgument(0);
            savedLinks.add(value);
            return value;
        });
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(any(UUID.class)))
                .thenReturn(savedLinks);
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(any(UUID.class)))
                .thenReturn(List.of());

        LoyaltyApiModels.WalletReservationResponse response = service.reserveWallet(
                new LoyaltyApiModels.ReserveRequest(COMPANY_ID, STORE_ID, MEMBER_ID,
                        "T1", "sale-" + UUID.randomUUID()), "token");
        assertThat(savedReservations).hasSize(1);
        return new WalletReservationScenario(savedReservations.getFirst(), savedLinks, response);
    }

    private void assertWalletReservation(
            WalletReservationScenario scenario,
            String loyaltyAmount,
            String returnCreditAmount,
            String spendableAmount,
            MemberBalanceType... expectedTypes) {
        assertThat(scenario.response().reservedLoyaltyAmount()).isEqualByComparingTo(loyaltyAmount);
        assertThat(scenario.response().reservedReturnCreditAmount()).isEqualByComparingTo(returnCreditAmount);
        assertThat(scenario.response().spendable()).isEqualByComparingTo(spendableAmount);
        assertThat(scenario.reservation().getReservedTotal()).isEqualByComparingTo(loyaltyAmount);
        assertThat(scenario.reservation().getReservedLoyaltyAmount()).isEqualByComparingTo(loyaltyAmount);
        assertThat(scenario.reservation().getReservedReturnCreditAmount())
                .isEqualByComparingTo(returnCreditAmount);
        assertThat(scenario.links()).extracting(SaasMemberBalanceReservationLot::getBalanceType)
                .containsExactlyElementsOf(List.of(expectedTypes));
    }

    private SaasMemberWalletBootstrap completedWalletBootstrap() {
        SaasMemberWalletBootstrap walletBootstrap = mock(SaasMemberWalletBootstrap.class);
        when(walletBootstrap.isCompleted()).thenReturn(true);
        return walletBootstrap;
    }

    private record WalletReservationScenario(
            SaasMemberBalanceReservation reservation,
            List<SaasMemberBalanceReservationLot> links,
            LoyaltyApiModels.WalletReservationResponse response) {
    }

    private LoyaltyApiModels.WalletReservationResponse configureWithoutClaims(
            SaasMemberBalanceReservation reservation) {
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(reservation.getAccount()));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of());
        LoyaltyApiModels.RetentionConfigureRequest request = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", reservation.getSaleId(), money("0"), List.of(), null);
        return service.configureRetention(reservation.getId(), request, "token");
    }

    @Test
    void finalizeReplayDoesNotReuseStandaloneReceiptForAnotherMember() {
        UUID operationId = UUID.randomUUID();
        UUID memberA = UUID.randomUUID();
        SaasMemberBalanceAccount accountB = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("10"), money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), accountB, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10"), NOW, Duration.ofMinutes(2));
        reservation.prepareTyped(operationId, money("0"), money("0"), NOW);
        reservation.finalizePreparedTyped(operationId, NOW, money("0"));
        SaasMemberBalanceRetentionReceipt receiptA = new SaasMemberBalanceRetentionReceipt(
                operationId, COMPANY_ID, STORE_ID, memberA, UUID.randomUUID(), UUID.randomUUID(),
                money("2"), "012345678901234567890123456789012345678901234567890123456789abcd",
                money("2"), money("0"), money("0"), NOW);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(accountB));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(receiptA));
        var request = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId);

        var response = service.finalizePreparedWallet(reservation.getId(), request, "token");

        assertThat(response.memberId()).isEqualTo(MEMBER_ID);
        assertThat(response.heldKnown()).isEqualByComparingTo("0.00");
        assertThat(response.recoveredKnown()).isEqualByComparingTo("0.00");
        assertThat(response.pendingMissing()).isEqualByComparingTo("0.00");
        assertThat(response.spentShortfall()).isEqualByComparingTo("0.00");
        assertThat(response.spendable()).isEqualByComparingTo("10.00");
        assertThat(response.retentionClaims()).isEmpty();
    }

    @Test
    void finalizeRejectsPositiveRetentionWithoutDocumentSnapshotEvenForLegacyReceipt() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("10"), money("0"), money("0"), NOW);
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10"), NOW, null,
                movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("10"));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10"), money(".20"), SaasMemberBalanceRetentionClaimStatus.APPLIED, NOW);
        claim.setHeldAmount(money(".20"));
        String fingerprint = retentionFingerprint(money(".20"), claim);
        reservation.configureRetention(1, fingerprint, money(".20"));
        reservation.prepareTyped(operationId, money("1.00"), money("0.00"), NOW);
        SaasMemberBalanceRetentionReceipt legacy = new SaasMemberBalanceRetentionReceipt(
                operationId, COMPANY_ID, STORE_ID, MEMBER_ID, sourceDocumentId, null,
                money(".20"), fingerprint, money(".20"), money("0"), money("0"), NOW);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(claim));
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), new LoyaltyApiModels.PreparedOwnerRequest(
                        COMPANY_ID, STORE_ID, "T1", "S1", operationId), "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("snapshot");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.PREPARED);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
        assertThat(link.getRemainingAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void finalizeProjectorFirstAcceptsCommittedReceiptWhenClaimsNoLongerBelongToReservation() {
        SaasMemberBalanceAccount account = account("10.00");
        UUID operationId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("0"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("1.00"), money("0.20"), SaasMemberBalanceRetentionClaimStatus.APPLIED, NOW);
        claim.setHeldAmount(money("0.20"));
        String fingerprint = retentionFingerprint(money("0.20"), claim);
        reservation.configureRetention(1, fingerprint, money("0.20"));
        reservation.prepareTyped(operationId, money("0"), money("0"), NOW);
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                operationId, COMPANY_ID, STORE_ID, MEMBER_ID, sourceDocumentId, returnDocumentId,
                money("0.20"), fingerprint, money("0.20"), money("0"), money("0"), NOW);
        MemberReturnBalanceRecoveryProjector projector = mock(MemberReturnBalanceRecoveryProjector.class);
        service.setRetentionReconciler(projector);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(receipt));

        LoyaltyApiModels.PreparedOwnerRequest request = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, money("0.20"), fingerprint,
                        List.of(new LoyaltyApiModels.RetentionClaim(
                                lotId, movementId, sourceDocumentId,
                                money("1.00"), money("0.20")))));

        service.finalizePreparedWallet(reservation.getId(), request, "token");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.CONSUMED);
        verify(projector).validate(any());
        verify(projector).reconcile(any(), any());
    }

    @Test
    void finalizeWithoutWalletUseOrRetentionReleasesPreparedReservation() {
        SaasMemberBalanceAccount account = account("10.00");
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S-empty",
                money("0"), NOW, Duration.ofMinutes(2));
        reservation.prepareTyped(operationId, money("0"), money("0"), NOW);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of());

        service.finalizePreparedWallet(
                reservation.getId(),
                new LoyaltyApiModels.PreparedOwnerRequest(
                        COMPANY_ID, STORE_ID, "T1", "S-empty", operationId),
                "token");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.RELEASED);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void releasePreparedWalletRejectsWithoutChangingClaimsOrState() {
        SaasMemberBalanceAccount account = account("10.00");
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S-prepared",
                money("10.00"), NOW, Duration.ofMinutes(2));
        reservation.prepareTyped(UUID.randomUUID(), money("2.00"), money("0"), NOW);
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                money("1.00"), money("0.20"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        LoyaltyApiModels.ReservationOwnerRequest request = new LoyaltyApiModels.ReservationOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S-prepared");

        assertThatThrownBy(() -> service.releaseWallet(reservation.getId(), request, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("ACTIVE");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.PREPARED);
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
        verify(retentionClaims, never()).save(any());
    }

    @Test
    void finalizeLocksRetentionProjectionBeforeAccountRow() {
        SaasMemberBalanceAccount account = account("10.00");
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10.00"), NOW, null,
                movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10.00"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("10.00"));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10.00"), money(".20"),
                SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        claim.setHeldAmount(money(".20"));
        String fingerprint = retentionFingerprint(money(".20"), claim);
        reservation.configureRetention(1, fingerprint, money(".20"));
        reservation.prepareTyped(operationId, money("1.00"), money("0.00"), NOW);

        MemberReturnBalanceRecoveryProjector projector = mock(MemberReturnBalanceRecoveryProjector.class);
        service.setRetentionReconciler(projector);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(claim));
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.empty());

        LoyaltyApiModels.PreparedOwnerRequest request = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, money(".20"), fingerprint,
                        List.of(new LoyaltyApiModels.RetentionClaim(
                                lotId, movementId, sourceDocumentId, money("10.00"), money(".20")))));

        service.finalizePreparedWallet(reservation.getId(), request, "token");

        InOrder order = inOrder(accounts, projector);
        order.verify(accounts).ensureProjectionLock("OPERATION:" + operationId);
        order.verify(accounts).lockProjectionKey("OPERATION:" + operationId);
        order.verify(projector).lockForRecovery(any());
        order.verify(accounts).findForUpdate(COMPANY_ID, MEMBER_ID);
    }

    @Test
    void firstFinalizeRejectsSnapshotForAnotherMemberBeforeAnyWalletMutation() {
        SaasMemberBalanceAccount account = account("10.00");
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10.00"), NOW, null,
                movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10.00"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("10.00"));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10.00"), money(".20"),
                SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        claim.setHeldAmount(money(".20"));
        String fingerprint = retentionFingerprint(money(".20"), claim);
        reservation.configureRetention(1, fingerprint, money(".20"));
        reservation.prepareTyped(operationId, money("1.00"), money("0.00"), NOW);
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(claim));

        LoyaltyApiModels.PreparedOwnerRequest request = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        UUID.randomUUID(), sourceDocumentId, returnDocumentId, money(".20"), fingerprint,
                        List.of(new LoyaltyApiModels.RetentionClaim(
                                lotId, movementId, sourceDocumentId, money("10.00"), money(".20")))));

        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), request, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("miembro");
        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.PREPARED);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
        assertThat(link.getRemainingAmount()).isEqualByComparingTo("10.00");
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
        verify(accounts, never()).ensureProjectionLock(any());
        verify(retentionReceipts, never()).save(any());
    }

    @Test
    void configureRetentionRejectsClaimFromAnotherSourceDocumentBeforePersistence() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("1"), money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("1"), NOW, Duration.ofMinutes(2));
        UUID sourceDocumentId = UUID.randomUUID();
        UUID otherDocumentId = UUID.randomUUID();
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        var request = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", money(".20"),
                List.of(new LoyaltyApiModels.RetentionClaim(
                        UUID.randomUUID(), UUID.randomUUID(), otherDocumentId,
                        money("1"), money(".20"))), sourceDocumentId);

        assertThatThrownBy(() -> service.configureRetention(reservation.getId(), request, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(retentionClaims, never()).save(any());
        assertThat(reservation.getRetentionRevision()).isZero();
    }

    @Test
    void configureRetentionRejectsOmittedClaimsInsteadOfTreatingItAsClear() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, money("1"), money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("1"), NOW, Duration.ofMinutes(2));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));

        var request = new LoyaltyApiModels.RetentionConfigureRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", money("0"), null, UUID.randomUUID());

        assertThatThrownBy(() -> service.configureRetention(reservation.getId(), request, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(retentionClaims, never()).save(any());
        assertThat(reservation.getRetentionRevision()).isZero();
    }

    @Test
    void prepareRejectsRetentionRevisionRace() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, new BigDecimal("1.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                new BigDecimal("1.00"), NOW, Duration.ofMinutes(2));
        reservation.configureRetention(1, "fingerprint");
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        SaasMemberWalletBootstrap walletBootstrap = mock(SaasMemberWalletBootstrap.class);
        when(walletBootstrap.isCompleted()).thenReturn(true);
        when(walletBootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(COMPANY_ID))
                .thenReturn(Optional.of(walletBootstrap));

        assertThatThrownBy(() -> service.prepareWallet(
                reservation.getId(),
                new LoyaltyApiModels.WalletPrepareRequest(
                        COMPANY_ID, STORE_ID, "T1", "S1", UUID.randomUUID(),
                        new BigDecimal(".10"), BigDecimal.ZERO, 0L, ""),
                "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("revision de retencion");
    }

    @Test
    void prepareWalletRejectsPositiveAmountUntilRetentionIsConfirmedAndRejectsRetentionOnly() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                new BigDecimal("10.00"), NOW, Duration.ofMinutes(2));
        UUID missingLotId = UUID.randomUUID();
        SaasMemberBalanceRetentionClaim pendingClaim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, missingLotId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("2.00"), new BigDecimal("2.00"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        reservation.configureRetention(1, "fingerprint", new BigDecimal("2.00"));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(pendingClaim));

        var positive = new LoyaltyApiModels.WalletPrepareRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", UUID.randomUUID(),
                new BigDecimal(".10"), BigDecimal.ZERO, 1L, "fingerprint");
        assertThatThrownBy(() -> service.prepareWallet(reservation.getId(), positive, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("retencion de devolucion aun no esta confirmada");

        var retentionOnly = new LoyaltyApiModels.WalletPrepareRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", UUID.randomUUID(),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), 1L, "fingerprint");
        assertThatThrownBy(() -> service.prepareWallet(
                reservation.getId(), retentionOnly, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("importe positivo del monedero");
        assertThat(reservation.isPrepared()).isFalse();
    }

    @Test
    void finalizeWalletIsIdempotentAndKeepsRecoveredMetrics() {
        SaasMemberBalanceRetentionReceipt[] savedReceipt = new SaasMemberBalanceRetentionReceipt[1];
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), COMPANY_ID, MEMBER_ID, new BigDecimal("10.00"),
                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(4), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                new BigDecimal("10.00"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                UUID.randomUUID(), account, MemberBalanceType.LOYALTY, new BigDecimal("10.00"),
                NOW, null, UUID.randomUUID(), null);
        UUID sourceDocumentId = UUID.randomUUID();
        SaasMemberBalanceReservationLot reservationLot = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, new BigDecimal("10.00"));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lot.getId(), lot.getSourceMovementId(),
                sourceDocumentId, new BigDecimal("10.00"), new BigDecimal(".20"),
                SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        claim.setHeldAmount(new BigDecimal(".09"));
        String fingerprint = retentionFingerprint(new BigDecimal(".20"), claim);
        reservation.configureRetention(1, fingerprint, new BigDecimal(".20"));
        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(reservationLot));
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(claim));
        when(retentionClaims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(retentionReceipts.save(any())).thenAnswer(invocation -> {
            savedReceipt[0] = invocation.getArgument(0);
            return savedReceipt[0];
        });
        UUID operationId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        reservation.prepareTyped(operationId, BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), NOW);
        LoyaltyApiModels.PreparedOwnerRequest request = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, new BigDecimal(".20"), fingerprint,
                        List.of(new LoyaltyApiModels.RetentionClaim(
                                lot.getId(), lot.getSourceMovementId(), sourceDocumentId,
                                new BigDecimal("10.00"), new BigDecimal(".20")))));

        LoyaltyApiModels.WalletReservationResponse first = service.finalizePreparedWallet(
                reservation.getId(), request, "token");
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(savedReceipt[0]));
        MemberReturnBalanceRecoveryProjector projector = new MemberReturnBalanceRecoveryProjector(
                accounts, lots, retentionClaims, retentionReceipts, new ObjectMapper(),
                reservationLots, reservations);
        service.setRetentionReconciler(projector);
        LoyaltyApiModels.WalletReservationResponse replay = service.finalizePreparedWallet(
                reservation.getId(), request, "token");

        assertThat(first.heldKnown()).isEqualByComparingTo("0.00");
        assertThat(first.recoveredKnown()).isEqualByComparingTo(".09");
        assertThat(first.spentShortfall()).isEqualByComparingTo(".11");
        assertThat(replay.recoveredKnown()).isEqualByComparingTo(".09");
        assertThat(replay.spentShortfall()).isEqualByComparingTo(".11");
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
        SaasMemberBalanceRetentionReceipt receipt = savedReceipt[0];
        assertThat(receipt).isNotNull();
        assertThat(receipt.getOperationId()).isEqualTo(operationId);
        assertThat(receipt.getFingerprint()).isEqualTo(fingerprint);
        assertThat(receipt.getReturnDocumentId()).isEqualTo(returnDocumentId);
        assertThat(receipt.getRecoveredKnown()).isEqualByComparingTo(".09");
        assertThat(receipt.getSpentShortfall()).isEqualByComparingTo(".11");

        var missingReturnDocument = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, null, new BigDecimal(".20"), fingerprint,
                        List.of(new LoyaltyApiModels.RetentionClaim(
                                lot.getId(), lot.getSourceMovementId(), sourceDocumentId,
                                new BigDecimal("10.00"), new BigDecimal(".20")))));
        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), missingReturnDocument, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");

        Map<String, Object> recoveryClaim = Map.of(
                "lotId", lot.getId().toString(), "sourceMovementId", lot.getSourceMovementId().toString(),
                "sourceDocumentId", sourceDocumentId.toString(), "amountOriginal", "10.00", "amount", ".20");
        Map<String, Object> recoveryPayload = Map.of(
                "schemaVersion", 1, "companyId", COMPANY_ID.toString(), "storeId", STORE_ID.toString(),
                "memberId", MEMBER_ID.toString(), "sourceDocumentId", sourceDocumentId.toString(),
                "returnDocumentId", returnDocumentId.toString(), "attributedAmount", ".20",
                "claimsFingerprint", recoveryFingerprint(".20", recoveryClaim),
                "claims", List.of(recoveryClaim));
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(receipt));
        projector.project(recoveryEvent(COMPANY_ID, STORE_ID, operationId), recoveryPayload, NOW);
        assertThat(receipt.getReturnDocumentId()).isEqualTo(returnDocumentId);
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");

        Map<String, Object> conflictingReturn = new java.util.HashMap<>(recoveryPayload);
        conflictingReturn.put("returnDocumentId", UUID.randomUUID().toString());
        assertThatThrownBy(() -> projector.project(
                recoveryEvent(COMPANY_ID, STORE_ID, operationId), conflictingReturn, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThatThrownBy(() -> projector.project(
                recoveryEvent(UUID.randomUUID(), STORE_ID, operationId), recoveryPayload, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");
        assertThatThrownBy(() -> projector.project(
                recoveryEvent(COMPANY_ID, UUID.randomUUID(), operationId), recoveryPayload, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");

        var altered = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, new BigDecimal(".21"),
                        fingerprint, List.of(new LoyaltyApiModels.RetentionClaim(
                                lot.getId(), lot.getSourceMovementId(), sourceDocumentId,
                                new BigDecimal("10.00"), new BigDecimal(".21")))));
        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), altered, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        var duplicate = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, new BigDecimal(".20"),
                        fingerprint, List.of(
                                new LoyaltyApiModels.RetentionClaim(
                                        lot.getId(), lot.getSourceMovementId(), sourceDocumentId,
                                        new BigDecimal("10.00"), new BigDecimal(".10")),
                                new LoyaltyApiModels.RetentionClaim(
                                        lot.getId(), lot.getSourceMovementId(), sourceDocumentId,
                                        new BigDecimal("10.00"), new BigDecimal(".10")))));
        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), duplicate, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        var nullClaims = new LoyaltyApiModels.PreparedOwnerRequest(
                COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                new LoyaltyApiModels.RetentionSnapshot(
                        MEMBER_ID, sourceDocumentId, returnDocumentId, new BigDecimal(".20"),
                        fingerprint, null));
        assertThatThrownBy(() -> service.finalizePreparedWallet(
                reservation.getId(), nullClaims, "token"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("9.91");
    }

    @Test
    void recoveryBeforeFinalizeKeepsPreparedSpendAndDoesNotDebitRetentionTwice() {
        UUID operationId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account("10.00");
        UUID lotId = UUID.randomUUID();
        SaasMemberBalanceLot lot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10.00"), NOW,
                null, movementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, STORE_ID, INSTALLATION_ID, "T1", "S1",
                money("10.00"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("10.00"));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10.00"), money(".20"),
                SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        String fingerprint = retentionFingerprint(money(".20"), claim);
        reservation.configureRetention(1, fingerprint, money(".20"));
        reservation.prepareTyped(operationId, money("3.00"), money("0.00"), NOW);

        when(reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(accounts.findForUpdate(COMPANY_ID, MEMBER_ID)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.of(lot));
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));
        when(retentionClaims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(List.of(claim));
        when(retentionClaims.findByLotIdAndSourceMovementIdAndStatusIn(
                lotId, movementId, List.of(
                        SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN,
                        SaasMemberBalanceRetentionClaimStatus.HELD_MISSING)))
                .thenReturn(List.of(claim));
        when(retentionClaims.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SaasMemberBalanceRetentionReceipt[] savedReceipt = new SaasMemberBalanceRetentionReceipt[1];
        when(retentionReceipts.save(any())).thenAnswer(invocation -> {
            savedReceipt[0] = invocation.getArgument(0);
            return savedReceipt[0];
        });

        MemberReturnBalanceRecoveryProjector projector = new MemberReturnBalanceRecoveryProjector(
                accounts, lots, retentionClaims, retentionReceipts, new ObjectMapper(),
                reservationLots, reservations);
        Map<String, Object> claimPayload = Map.of(
                "lotId", lotId.toString(), "sourceMovementId", movementId.toString(),
                "sourceDocumentId", sourceDocumentId.toString(), "amountOriginal", "10.00",
                "amount", ".20");
        UUID returnDocumentId = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "schemaVersion", 1, "companyId", COMPANY_ID.toString(),
                "storeId", STORE_ID.toString(), "memberId", MEMBER_ID.toString(),
                "sourceDocumentId", sourceDocumentId.toString(), "returnDocumentId", returnDocumentId.toString(),
                "attributedAmount", ".20", "claimsFingerprint", recoveryFingerprint(".20", claimPayload),
                "claims", List.of(claimPayload));
        projector.project(recoveryEvent(COMPANY_ID, STORE_ID, operationId), payload, NOW);
        when(retentionReceipts.findById(operationId)).thenReturn(Optional.of(savedReceipt[0]));

        service.finalizePreparedWallet(reservation.getId(),
                new LoyaltyApiModels.PreparedOwnerRequest(COMPANY_ID, STORE_ID, "T1", "S1", operationId,
                        new LoyaltyApiModels.RetentionSnapshot(
                                MEMBER_ID, sourceDocumentId, returnDocumentId, money(".20"), fingerprint,
                                List.of(new LoyaltyApiModels.RetentionClaim(
                                        lotId, movementId, sourceDocumentId, money("10.00"), money(".20"))))),
                "token");

        assertThat(account.getBalance()).isEqualByComparingTo("6.80");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("6.80");
        assertThat(link.getRemainingAmount()).isEqualByComparingTo("6.80");
        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.CONSUMED);
        assertThat(savedReceipt[0]).isNotNull();
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
                .isInstanceOfSatisfying(MemberBalanceReservationConflictException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(409));
    }

    @Test
    void reserveWalletRechazaElUsoHastaCompletarElBootstrapAdministrativo() {
        assertThatThrownBy(() -> service.reserveWallet(
                        reserveRequest("CAJA-1", "VENTA-1"), "token"))
                .isInstanceOf(MemberWalletBootstrapRequiredException.class);
    }

    @Test
    void reserveWalletClasificaLaColisionRealDeOtroOwner() {
        var walletBootstrap = mock(SaasMemberWalletBootstrap.class);
        when(walletBootstrap.isCompleted()).thenReturn(true);
        when(walletBootstraps.findFirstByCompany_IdOrderByCreatedAtDesc(COMPANY_ID))
                .thenReturn(Optional.of(walletBootstrap));

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

        assertThatThrownBy(() -> service.reserveWallet(
                        reserveRequest("CAJA-1", "VENTA-1"), "token"))
                .isInstanceOf(MemberBalanceReservationConflictException.class);
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

    private static SaasSyncEvent recoveryEvent(UUID companyId, UUID storeId, UUID operationId) {
        SaasCompany company = mock(SaasCompany.class);
        when(company.getId()).thenReturn(companyId);
        SaasStore store = mock(SaasStore.class);
        when(store.getId()).thenReturn(storeId);
        SaasSyncEvent event = mock(SaasSyncEvent.class);
        when(event.getCompany()).thenReturn(company);
        when(event.getStore()).thenReturn(store);
        when(event.getEntityId()).thenReturn(operationId);
        return event;
    }

    private static String recoveryFingerprint(String attributed, Map<String, Object> claim) {
        String canonical = new BigDecimal(attributed).setScale(2).toPlainString() + "\n"
                + claim.get("lotId") + "|" + claim.get("sourceMovementId") + "|"
                + claim.get("sourceDocumentId") + "|10.00|0.20";
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String retentionFingerprint(BigDecimal attributed,
            SaasMemberBalanceRetentionClaim claim) {
        String canonical = attributed.setScale(2).toPlainString() + "\n"
                + claim.getLotId() + "|" + claim.getSourceMovementId() + "|"
                + claim.getSourceDocumentId() + "|" + claim.getAmountOriginal().toPlainString()
                + "|" + claim.getAmount().toPlainString();
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
