package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.license.SaasStore;
import com.tpverp.saas.license.InstallationAuthenticator;
import com.tpverp.saas.license.SaasInstallation;
import com.tpverp.saas.license.SaasInstallationRepository;
import com.tpverp.saas.loyalty.LoyaltyApiModels;
import com.tpverp.saas.loyalty.MemberBalanceReservationService;
import com.tpverp.saas.loyalty.SaasMemberLoyaltyBootstrapRepository;
import com.tpverp.saas.loyalty.SaasMemberWalletBootstrapRepository;
import jakarta.persistence.EntityManager;
import com.tpverp.saas.loyalty.MemberBalanceType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservation;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaim;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimStatus;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceipt;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptAlias;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceiptAliasRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationRepository;
import com.tpverp.saas.sync.MemberReturnBalanceRecoveryCommand;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberReturnBalanceRecoveryProjectorTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    void newStandaloneRecoveryRequiresReturnDocumentId() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        var claim = new MemberReturnBalanceRecoveryCommand.Claim(
                lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, null, money("0.22"),
                fingerprint("0.22", List.of(line(lotId, movementId, sourceDocumentId, "1.00", "0.22"))),
                List.of(claim));
        Fixtures fixtures = fixtures(companyId, storeId, memberId,
                account(companyId, memberId, "1.00"));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class)
                .hasMessageContaining("returnDocumentId");
        verify(fixtures.receipts, never()).save(any());
    }

    @Test
    void historicalOperationWithoutReturnDocumentRemainsIdempotentWhenAnotherReceiptOwnsDocument() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID operationA = UUID.randomUUID();
        UUID operationB = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        String fingerprint = fingerprint("0.22", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.22")));
        SaasMemberBalanceRetentionReceipt receiptA = new SaasMemberBalanceRetentionReceipt(
                operationA, companyId, storeId, memberId, sourceDocumentId, null,
                money("0.22"), fingerprint, money("0.22"), money("0"), money("0"), NOW);
        SaasMemberBalanceRetentionReceipt receiptB = new SaasMemberBalanceRetentionReceipt(
                operationB, companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                money("0.22"), fingerprint, money("0.22"), money("0"), money("0"), NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId,
                account(companyId, memberId, "0.78"));
        when(fixtures.receipts.findById(operationA)).thenReturn(Optional.of(receiptA));
        when(fixtures.receipts.findByCompanyIdAndReturnDocumentId(companyId, returnDocumentId))
                .thenReturn(Optional.of(receiptB));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationA, companyId, storeId, memberId, null, null,
                sourceDocumentId, null, money("0.22"), fingerprint,
                List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                        lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"))));

        fixtures.projector.apply(command, NOW.plusSeconds(1));

        assertThat(fixtures.account.getBalance()).isEqualByComparingTo("0.78");
        verify(fixtures.receipts, never()).save(any());
        verify(fixtures.aliases, never()).save(any(SaasMemberBalanceRetentionReceiptAlias.class));
    }

    @Test
    void operationIdPresentAsReceiptAndAliasIsRejectedBeforeReplay() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID canonicalOperationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        String fingerprint = fingerprint("0.22", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.22")));
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                canonicalOperationId, companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                money("0.22"), fingerprint, money("0.22"), money("0"), money("0"), NOW);
        SaasMemberBalanceRetentionReceipt directReceipt = new SaasMemberBalanceRetentionReceipt(
                operationId, companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                money("0.22"), fingerprint, money("0.22"), money("0"), money("0"), NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId,
                account(companyId, memberId, "0.78"));
        when(fixtures.receipts.findById(operationId)).thenReturn(Optional.of(directReceipt));
        when(fixtures.receipts.findByCompanyIdAndReturnDocumentId(companyId, returnDocumentId))
                .thenReturn(Optional.of(receipt));
        when(fixtures.aliases.findById(operationId)).thenReturn(Optional.of(
                new SaasMemberBalanceRetentionReceiptAlias(operationId, receipt, NOW)));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, returnDocumentId, money("0.22"), fingerprint,
                List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                        lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"))));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class)
                .hasMessageContaining("receipt y alias");
        verify(fixtures.receipts, never()).save(any());
        verify(fixtures.aliases, never()).save(any());
    }

    @Test
    void incompleteHistoricalReceiptCannotBeAcknowledgedAsRecoveryComplete() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID canonicalOperationId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        String fingerprint = fingerprint("0.22", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.22")));
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                canonicalOperationId, companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                money("0.22"), fingerprint, money("0.12"), money("0.10"), money("0"), NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId,
                account(companyId, memberId, "0.88"));
        when(fixtures.receipts.findById(operationId)).thenReturn(Optional.empty());
        when(fixtures.receipts.findByCompanyIdAndReturnDocumentId(companyId, returnDocumentId))
                .thenReturn(Optional.of(receipt));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, returnDocumentId, money("0.22"), fingerprint,
                List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                        lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"))));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class)
                .hasMessageContaining("recuperacion completa");
        verify(fixtures.receipts, never()).save(any());
    }

    @Test
    void standaloneKnownClaimConsumesOnlyKnownBalanceAndPersistsReceipt() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        Map<String, Object> payload = payload(companyId, storeId, memberId, sourceDocumentId,
                operationId, lotId, movementId, "10", "3");

        fixtures.projector.project(fixtures.event(operationId), payload, NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("7.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("7.00");
        verify(fixtures.receipts).save(any());
        verify(fixtures.claims).save(any());
    }

    @Test
    void standalonePointTwentyTwoDebitsOnceReplaysIdempotentlyAndConflictsOnDifferentPayload() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "1.00");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "1.00", "1.00");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        SaasMemberBalanceRetentionReceipt[] saved = new SaasMemberBalanceRetentionReceipt[1];
        when(fixtures.receipts.save(any())).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        Map<String, Object> payload = payload(companyId, storeId, memberId, sourceDocumentId,
                operationId, lotId, movementId, "1.00", "0.22");

        fixtures.projector.project(fixtures.event(operationId), payload, NOW);
        when(fixtures.receipts.findById(operationId)).thenReturn(Optional.of(saved[0]));
        fixtures.projector.project(fixtures.event(operationId), payload, NOW.plusSeconds(1));

        assertThat(account.getBalance()).isEqualByComparingTo("0.78");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.78");
        verify(fixtures.receipts, org.mockito.Mockito.times(1)).save(any());
        verify(fixtures.claims, org.mockito.Mockito.times(1)).save(any());

        Map<String, Object> conflictingPayload = payload(companyId, storeId, memberId,
                sourceDocumentId, operationId, lotId, movementId, "1.00", "0.23");
        assertThatThrownBy(() -> fixtures.projector.project(
                fixtures.event(operationId), conflictingPayload, NOW.plusSeconds(2)))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("0.78");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.78");
    }

    @Test
    void differentOperationForSameReturnDocumentIsIdempotentAndConflictsOnDifferentData() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID firstOperationId = UUID.randomUUID();
        UUID replayOperationId = UUID.randomUUID();
        UUID conflictOperationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "1.00");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "1.00", "1.00");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        SaasMemberBalanceRetentionReceipt[] saved = new SaasMemberBalanceRetentionReceipt[1];
        when(fixtures.receipts.save(any())).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        var firstClaim = new MemberReturnBalanceRecoveryCommand.Claim(
                lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"));
        String firstFingerprint = fingerprint("0.22", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.22")));
        var first = new MemberReturnBalanceRecoveryCommand(
                firstOperationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, returnDocumentId, money("0.22"), firstFingerprint,
                List.of(firstClaim));

        fixtures.projector.apply(first, NOW);
        when(fixtures.receipts.findById(replayOperationId)).thenReturn(Optional.empty());
        when(fixtures.receipts.findByCompanyIdAndReturnDocumentId(companyId, returnDocumentId))
                .thenReturn(Optional.of(saved[0]));
        var replay = new MemberReturnBalanceRecoveryCommand(
                replayOperationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, returnDocumentId, money("0.22"), firstFingerprint,
                List.of(firstClaim));

        fixtures.projector.apply(replay, NOW.plusSeconds(1));

        assertThat(account.getBalance()).isEqualByComparingTo("0.78");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.78");
        verify(fixtures.receipts, org.mockito.Mockito.times(1)).save(any());
        verify(fixtures.claims, org.mockito.Mockito.times(1)).save(any());
        verify(fixtures.aliases).save(any(SaasMemberBalanceRetentionReceiptAlias.class));
        verify(fixtures.accounts, org.mockito.Mockito.times(2)).lockProjectionKey(
                "RETURN_DOCUMENT:" + companyId + ":" + returnDocumentId);
        verify(fixtures.accounts).lockProjectionKey("OPERATION:" + firstOperationId);
        verify(fixtures.accounts).lockProjectionKey("OPERATION:" + replayOperationId);

        var conflictClaim = new MemberReturnBalanceRecoveryCommand.Claim(
                lotId, movementId, sourceDocumentId, money("1.00"), money("0.23"));
        String conflictFingerprint = fingerprint("0.23", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.23")));
        var conflict = new MemberReturnBalanceRecoveryCommand(
                conflictOperationId, companyId, storeId, memberId, null, null,
                sourceDocumentId, returnDocumentId, money("0.23"), conflictFingerprint,
                List.of(conflictClaim));

        assertThatThrownBy(() -> fixtures.projector.apply(conflict, NOW.plusSeconds(2)))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("0.78");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.78");
        verify(fixtures.receipts, org.mockito.Mockito.times(1)).save(any());
        verify(fixtures.claims, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void crossOperationRecoveryLinkedToReservationCannotReuseCanonicalReceipt() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID canonicalOperationId = UUID.randomUUID();
        UUID incomingOperationId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "1.00");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "1.00", "1.00");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                reservationId, account, storeId, UUID.randomUUID(), "T1",
                incomingOperationId.toString(), money("1.00"), NOW, Duration.ofMinutes(2));
        reservation.prepare(incomingOperationId, money(".50"), NOW);
        SaasMemberBalanceRetentionClaim held = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("1.00"), money(".22"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        String fingerprint = fingerprint("0.22", List.of(
                line(lotId, movementId, sourceDocumentId, "1.00", "0.22")));
        SaasMemberBalanceRetentionReceipt canonical = new SaasMemberBalanceRetentionReceipt(
                canonicalOperationId, companyId, storeId, memberId, sourceDocumentId,
                returnDocumentId, money("0.22"), fingerprint, money("0.22"), money("0"), money("0"), NOW);
        when(fixtures.receipts.findByCompanyIdAndReturnDocumentId(companyId, returnDocumentId))
                .thenReturn(Optional.of(canonical));
        when(fixtures.reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(fixtures.claims.findByReservation_IdOrderByLotIdAsc(reservationId))
                .thenReturn(List.of(held));
        var command = new MemberReturnBalanceRecoveryCommand(
                incomingOperationId, companyId, storeId, memberId, reservationId,
                incomingOperationId.toString(), sourceDocumentId, returnDocumentId,
                money("0.22"), fingerprint, List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                        lotId, movementId, sourceDocumentId, money("1.00"), money("0.22"))));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW.plusSeconds(1)))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class)
                .hasMessageContaining("reserva");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.PREPARED);
        assertThat(held.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
        assertThat(account.getBalance()).isEqualByComparingTo("1.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("1.00");
        verify(fixtures.receipts, never()).save(any());
        verify(fixtures.claims, never()).save(any());
    }

    @Test
    void validatesEveryClaimBeforeConsumingAnyLot() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID firstLotId = UUID.randomUUID();
        UUID secondLotId = UUID.randomUUID();
        UUID firstMovement = UUID.randomUUID();
        UUID secondMovement = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot first = lot(account, firstLotId, firstMovement, sourceDocumentId, "5", "5");
        SaasMemberBalanceLot second = lot(account, secondLotId, secondMovement, sourceDocumentId, "7", "7");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, first, second);
        Map<String, Object> payload = Map.of(
                "schemaVersion", 1,
                "companyId", companyId.toString(), "storeId", storeId.toString(),
                "memberId", memberId.toString(), "sourceDocumentId", sourceDocumentId.toString(),
                "attributedAmount", "4.00", "claimsFingerprint", fingerprint("4.00",
                        List.of(line(firstLotId, firstMovement, sourceDocumentId, "5", "2"),
                                line(secondLotId, secondMovement, sourceDocumentId, "6", "2"))),
                "claims", List.of(
                        line(firstLotId, firstMovement, sourceDocumentId, "5", "2"),
                        line(secondLotId, secondMovement, sourceDocumentId, "6", "2")));

        assertThatThrownBy(() -> fixtures.projector.project(fixtures.event(operationId), payload, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(first.getRemainingAmount()).isEqualByComparingTo("5.00");
        verify(fixtures.receipts, never()).save(any());
    }

    @Test
    void sameOperationHeldClaimSynchronizesPreparedReservationLot() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, storeId, UUID.randomUUID(), "T", "sale",
                money("6"), NOW, Duration.ofMinutes(2));
        reservation.prepare(operationId, money("3"), NOW);
        SaasMemberBalanceReservationLot link = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, lot, money("3"));
        SaasMemberBalanceRetentionClaim held = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10"), money("3"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        when(fixtures.claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(held));
        when(fixtures.reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of(link));

        fixtures.projector.project(fixtures.event(operationId), payload(companyId, storeId, memberId,
                sourceDocumentId, operationId, lotId, movementId, "10", "3"), NOW);

        assertThat(link.getRemainingAmount()).isEqualByComparingTo("0.00");
        assertThat(held.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("7.00");
    }

    @Test
    void shiftedFinalSnapshotReusesPreparedReservationAndReleasesOldClaim() throws Exception {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID oldLotId = UUID.randomUUID();
        UUID newLotId = UUID.randomUUID();
        UUID oldMovementId = UUID.randomUUID();
        UUID newMovementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot oldLot = new SaasMemberBalanceLot(
                oldLotId, account, MemberBalanceType.LOYALTY, money("5"), money("5"),
                NOW.minusSeconds(1), null, oldMovementId, sourceDocumentId);
        SaasMemberBalanceLot newLot = new SaasMemberBalanceLot(
                newLotId, account, MemberBalanceType.LOYALTY, money("5"), money("5"),
                NOW, null, newMovementId, sourceDocumentId);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, storeId, UUID.randomUUID(), "T", operationId.toString(),
                money("10"), NOW, Duration.ofMinutes(2));
        reservation.configureRetention(1, "old", money("2"));
        reservation.prepare(operationId, money("3"), NOW);
        SaasMemberBalanceReservationLot oldLink = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, oldLot, money("5"));
        SaasMemberBalanceReservationLot newLink = new SaasMemberBalanceReservationLot(
                UUID.randomUUID(), reservation, newLot, money("5"));
        SaasMemberBalanceRetentionClaim oldClaim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, oldLotId, oldMovementId, sourceDocumentId,
                money("5"), money("2"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, oldLot, newLot);
        when(fixtures.reservations.findByAccount_IdAndStatusIn(any(), any()))
                .thenReturn(List.of(reservation));
        when(fixtures.reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixtures.lots.findById(newLotId)).thenReturn(Optional.of(newLot));
        List<SaasMemberBalanceRetentionClaim> claimStore = new ArrayList<>(List.of(oldClaim));
        when(fixtures.claims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(claimStore);
        when(fixtures.claims.findByLotIdAndSourceMovementIdAndStatusIn(
                any(UUID.class), any(UUID.class), any())).thenReturn(List.of());
        when(fixtures.reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(
                reservation.getId())).thenReturn(List.of(oldLink, newLink));
        when(fixtures.claims.save(any())).thenAnswer(invocation -> {
            SaasMemberBalanceRetentionClaim value = invocation.getArgument(0);
            if (!claimStore.contains(value)) claimStore.add(value);
            return value;
        });
        SaasMemberBalanceRetentionReceipt[] savedReceipt = new SaasMemberBalanceRetentionReceipt[1];
        when(fixtures.receipts.save(any())).thenAnswer(invocation -> {
            savedReceipt[0] = invocation.getArgument(0);
            return savedReceipt[0];
        });
        Map<String, Object> claim = line(newLotId, newMovementId, sourceDocumentId, "5", "2");
        UUID returnDocumentId = UUID.randomUUID();
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("schemaVersion", 1), Map.entry("companyId", companyId.toString()),
                Map.entry("storeId", storeId.toString()), Map.entry("memberId", memberId.toString()),
                Map.entry("sourceDocumentId", sourceDocumentId.toString()),
                Map.entry("reservationId", reservation.getId().toString()),
                Map.entry("reservationSaleId", operationId.toString()),
                Map.entry("returnDocumentId", returnDocumentId.toString()),
                Map.entry("attributedAmount", "2.00"),
                Map.entry("claimsFingerprint", fingerprint("2.00", List.of(claim))),
                Map.entry("claims", List.of(claim)));

        fixtures.projector.project(fixtures.event(operationId), payload, NOW);

        assertThat(oldClaim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
        assertThat(reservation.getRetentionFingerprint())
                .isEqualTo(fingerprint("2.00", List.of(claim)));
        assertThat(newLot.getRemainingAmount()).isEqualByComparingTo("3.00");
        assertThat(newLink.getRemainingAmount()).isEqualByComparingTo("3.00");
        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.PREPARED);

        SaasInstallationRepository installations = mock(SaasInstallationRepository.class);
        InstallationAuthenticator authenticator = mock(InstallationAuthenticator.class);
        SaasInstallation installation = mock(SaasInstallation.class);
        SaasStore store = mock(SaasStore.class);
        when(installation.getId()).thenReturn(reservation.getInstallationId());
        when(installation.getStore()).thenReturn(store);
        when(store.getId()).thenReturn(storeId);
        when(installations.findByCompany_Id(companyId)).thenReturn(List.of(installation));
        when(authenticator.requireLinkedInstallation(any(), any(), any(), any())).thenReturn(installation);
        MemberBalanceReservationService service = new MemberBalanceReservationService(
                installations, authenticator, mock(SaasMemberLoyaltyBootstrapRepository.class),
                mock(SaasMemberWalletBootstrapRepository.class), fixtures.accounts, fixtures.lots,
                fixtures.reservations, fixtures.reservationLots, fixtures.claims,
                mock(EntityManager.class), Clock.fixed(NOW, ZoneOffset.UTC));
        var retentionReceiptsSetter = MemberBalanceReservationService.class
                .getDeclaredMethod("setRetentionReceipts", SaasMemberBalanceRetentionReceiptRepository.class);
        retentionReceiptsSetter.setAccessible(true);
        retentionReceiptsSetter.invoke(service, fixtures.receipts);
        when(fixtures.receipts.findById(operationId)).thenReturn(Optional.of(savedReceipt[0]));
        service.finalizePreparedWallet(reservation.getId(),
                new LoyaltyApiModels.PreparedOwnerRequest(
                        companyId, storeId, "T", operationId.toString(), operationId,
                        new LoyaltyApiModels.RetentionSnapshot(
                                memberId, sourceDocumentId, returnDocumentId, money("2.00"),
                                fingerprint("2.00", List.of(claim)),
                                List.of(new LoyaltyApiModels.RetentionClaim(
                                        newLotId, newMovementId, sourceDocumentId,
                                        money("5"), money("2"))))), "token");

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.CONSUMED);
        assertThat(account.getBalance()).isEqualByComparingTo("5.00");
        assertThat(oldLot.getRemainingAmount()).isEqualByComparingTo("2.00");
        assertThat(oldLink.getRemainingAmount()).isEqualByComparingTo("2.00");
        assertThat(newLot.getRemainingAmount()).isEqualByComparingTo("3.00");
        assertThat(newLink.getRemainingAmount()).isEqualByComparingTo("3.00");
    }

    @Test
    void missingStandaloneClaimIsTombstonedWhenWalletLotArrives() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account);
        SaasMemberBalanceRetentionClaim[] saved = new SaasMemberBalanceRetentionClaim[1];
        when(fixtures.claims.save(any())).thenAnswer(invocation -> {
            saved[0] = invocation.getArgument(0);
            return saved[0];
        });
        when(fixtures.lots.findById(lotId)).thenReturn(Optional.empty());
        fixtures.projector.project(fixtures.event(operationId), payload(companyId, storeId, memberId,
                sourceDocumentId, operationId, lotId, movementId, "10", "3"), NOW);

        SaasMemberBalanceRetentionClaim claim = saved[0];
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING);
        SaasMemberBalanceLotRepository lateLots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository lateAccounts = mock(SaasMemberBalanceAccountRepository.class);
        when(lateAccounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lateLots.findById(lotId)).thenReturn(Optional.empty());
        when(lateLots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lateLots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixtures.claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(claim));
        MemberWalletSyncProjector wallet = new MemberWalletSyncProjector(
                lateAccounts, lateLots, new ObjectMapper(), fixtures.claims, fixtures.reservationLots);

        wallet.project(fixtures.event(lotId), Map.of("schemaVersion", 2,
                "memberId", memberId.toString(), "balanceType", "LOYALTY", "amount", "10.00",
                "createdAt", NOW.toString(), "sourceMovementId", movementId.toString(),
                "documentId", sourceDocumentId.toString()), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("7.00");
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
    }

    @Test
    void successiveReceiptsConsumeSameLotAndReplayIsNoOp() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID firstOperation = UUID.randomUUID();
        UUID secondOperation = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        SaasMemberBalanceRetentionReceipt[] savedReceipt = new SaasMemberBalanceRetentionReceipt[1];
        when(fixtures.receipts.save(any())).thenAnswer(invocation -> {
            savedReceipt[0] = invocation.getArgument(0);
            return savedReceipt[0];
        });
        Map<String, Object> firstPayload = payload(companyId, storeId, memberId, sourceDocumentId,
                firstOperation, lotId, movementId, "10", "6");
        fixtures.projector.project(fixtures.event(firstOperation), firstPayload, NOW);
        Map<String, Object> secondPayload = payload(companyId, storeId, memberId, sourceDocumentId,
                secondOperation, lotId, movementId, "10", "4");
        fixtures.projector.project(fixtures.event(secondOperation), secondPayload, NOW);
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.00");

        when(fixtures.receipts.findById(secondOperation)).thenReturn(Optional.of(savedReceipt[0]));
        fixtures.projector.project(fixtures.event(secondOperation), secondPayload, NOW.plusSeconds(1));
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void unrelatedLiveReservationBlocksRecoveryWithoutMutation() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        SaasMemberBalanceReservation other = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, storeId, UUID.randomUUID(), "T", "other-sale",
                money("1"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        when(lots.findById(lotId)).thenReturn(Optional.of(lot));
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        SaasMemberBalanceRetentionReceiptRepository receipts = mock(SaasMemberBalanceRetentionReceiptRepository.class);
        SaasMemberBalanceReservationLotRepository reservationLots = mock(SaasMemberBalanceReservationLotRepository.class);
        SaasMemberBalanceReservationRepository reservations = mock(SaasMemberBalanceReservationRepository.class);
        when(reservations.findByAccount_IdAndStatusIn(any(), any())).thenReturn(List.of(other));
        MemberReturnBalanceRecoveryProjector projector = new MemberReturnBalanceRecoveryProjector(
                accounts, lots, claims, receipts, new ObjectMapper(), reservationLots, reservations);

        assertThatThrownBy(() -> projector.project(event(companyId, storeId, operationId),
                payload(companyId, storeId, memberId, sourceDocumentId, operationId,
                        lotId, movementId, "10", "3"), NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
    }

    @Test
    void emptyZeroRecoveryClearsActiveRetentionAndIsReplaySafe() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, storeId, UUID.randomUUID(), "T", operationId.toString(),
                money("2"), NOW, Duration.ofMinutes(2));
        reservation.configureRetention(1, "old", money("2"));
        SaasMemberBalanceRetentionClaim held = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId, sourceDocumentId,
                money("10"), money("2"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        when(fixtures.reservations.findByAccount_IdAndStatusIn(any(), any())).thenReturn(List.of(reservation));
        when(fixtures.reservations.findById(reservation.getId())).thenReturn(Optional.of(reservation));
        when(fixtures.claims.findByReservation_IdOrderByLotIdAsc(reservation.getId()))
                .thenReturn(new ArrayList<>(List.of(held)));
        Map<String, Object> payload = Map.of(
                "schemaVersion", 1, "companyId", companyId.toString(), "storeId", storeId.toString(),
                "memberId", memberId.toString(), "sourceDocumentId", sourceDocumentId.toString(),
                "reservationId", reservation.getId().toString(),
                "reservationSaleId", operationId.toString(), "attributedAmount", "0.00",
                "claimsFingerprint", fingerprint("0.00", List.of()), "claims", List.of());

        fixtures.projector.project(fixtures.event(operationId), payload, NOW);

        assertThat(reservation.getStatus()).isEqualTo(SaasMemberBalanceReservation.RELEASED);
        assertThat(held.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        when(fixtures.receipts.findById(operationId)).thenReturn(
                Optional.of(new SaasMemberBalanceRetentionReceipt(
                        operationId, companyId, storeId, memberId, sourceDocumentId, null,
                        money("0"), fingerprint("0.00", List.of()), money("0"), money("0"),
                        money("0"), NOW)));
        fixtures.projector.project(fixtures.event(operationId), payload, NOW.plusSeconds(1));
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void typedDuplicateLotIsRejectedBeforeAnyMutation() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        var claim = new MemberReturnBalanceRecoveryCommand.Claim(
                lotId, movementId, sourceDocumentId, money("10"), money("1"));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, null, null, sourceDocumentId, UUID.randomUUID(),
                money("2"), "bad", List.of(claim, claim));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
        verify(fixtures.receipts, never()).save(any());
    }

    @Test
    void durableReservationIdentityCannotDegradeToStandaloneRecovery() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        var claim = new MemberReturnBalanceRecoveryCommand.Claim(
                lotId, movementId, sourceDocumentId, money("10"), money(".20"));
        Map<String, Object> claimLine = line(lotId, movementId, sourceDocumentId, "10", ".20");
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, UUID.randomUUID(), operationId.toString(),
                sourceDocumentId, UUID.randomUUID(), money(".20"),
                fingerprint(".20", List.of(claimLine)),
                List.of(claim));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
        verify(fixtures.receipts, never()).save(any());
    }

    @Test
    void existingReceiptStillRejectsAReservationOwnerMismatchBeforeReplayAck() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceLot lot = lot(account, lotId, movementId, sourceDocumentId, "10", "10");
        Fixtures fixtures = fixtures(companyId, storeId, memberId, account, lot);
        Map<String, Object> claim = line(lotId, movementId, sourceDocumentId, "10", ".20");
        String fingerprint = fingerprint(".20", List.of(claim));
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                operationId, companyId, storeId, memberId, sourceDocumentId, returnDocumentId,
                money(".20"), fingerprint, money(".20"), money("0"), money("0"), NOW);
        when(fixtures.receipts.findById(operationId)).thenReturn(Optional.of(receipt));
        var command = new MemberReturnBalanceRecoveryCommand(
                operationId, companyId, storeId, memberId, UUID.randomUUID(), operationId.toString(),
                sourceDocumentId, returnDocumentId, money(".20"), fingerprint,
                List.of(new MemberReturnBalanceRecoveryCommand.Claim(
                        lotId, movementId, sourceDocumentId, money("10"), money(".20"))));

        assertThatThrownBy(() -> fixtures.projector.apply(command, NOW))
                .isInstanceOf(MemberReturnBalanceRecoveryProjector.ProjectionException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(lot.getRemainingAmount()).isEqualByComparingTo("10.00");
        verify(fixtures.receipts, never()).save(any());
    }

    private static Fixtures fixtures(UUID companyId, UUID storeId, UUID memberId,
            SaasMemberBalanceAccount account, SaasMemberBalanceLot... lots) {
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(accounts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SaasMemberBalanceLotRepository lotRepository = mock(SaasMemberBalanceLotRepository.class);
        for (SaasMemberBalanceLot lot : lots) when(lotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        SaasMemberBalanceRetentionReceiptRepository receipts = mock(SaasMemberBalanceRetentionReceiptRepository.class);
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SaasMemberBalanceRetentionReceiptAliasRepository aliases =
                mock(SaasMemberBalanceRetentionReceiptAliasRepository.class);
        when(aliases.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SaasMemberBalanceReservationLotRepository reservationLots = mock(SaasMemberBalanceReservationLotRepository.class);
        SaasMemberBalanceReservationRepository reservations = mock(SaasMemberBalanceReservationRepository.class);
        MemberReturnBalanceRecoveryProjector projector = new MemberReturnBalanceRecoveryProjector(
                accounts, lotRepository, claims, receipts, new ObjectMapper(), reservationLots, reservations,
                aliases);
        return new Fixtures(companyId, storeId, memberId, account, accounts, lotRepository,
                claims, receipts, aliases, reservationLots, reservations, projector);
    }

    private record Fixtures(UUID companyId, UUID storeId, UUID memberId,
            SaasMemberBalanceAccount account, SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            SaasMemberBalanceRetentionReceiptRepository receipts,
            SaasMemberBalanceRetentionReceiptAliasRepository aliases,
            SaasMemberBalanceReservationLotRepository reservationLots,
            SaasMemberBalanceReservationRepository reservations,
            MemberReturnBalanceRecoveryProjector projector) {
        SaasSyncEvent event(UUID operationId) {
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
    }

    private static SaasSyncEvent event(UUID companyId, UUID storeId, UUID operationId) {
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

    private static Map<String, Object> payload(UUID companyId, UUID storeId, UUID memberId,
            UUID sourceDocumentId, UUID operationId, UUID lotId, UUID movementId,
            String amountOriginal, String amount) {
        Map<String, Object> claim = line(lotId, movementId, sourceDocumentId, amountOriginal, amount);
        return Map.of("schemaVersion", 1, "companyId", companyId.toString(), "storeId", storeId.toString(),
                "memberId", memberId.toString(), "sourceDocumentId", sourceDocumentId.toString(),
                "returnDocumentId", UUID.randomUUID().toString(),
                "attributedAmount", amount, "claimsFingerprint", fingerprint(amount, List.of(claim)),
                "claims", List.of(claim));
    }

    private static Map<String, Object> line(UUID lotId, UUID movementId, UUID documentId,
            String original, String amount) {
        return Map.of("lotId", lotId.toString(), "sourceMovementId", movementId.toString(),
                "sourceDocumentId", documentId.toString(), "amountOriginal", original, "amount", amount);
    }

    private static String fingerprint(String attributed, List<Map<String, Object>> lines) {
        String canonical = new BigDecimal(attributed).setScale(2) + "\n" + lines.stream().sorted((a, b) -> a.get("lotId").toString()
                .compareTo(b.get("lotId").toString())).map(line -> line.get("lotId") + "|"
                        + line.get("sourceMovementId") + "|" + line.get("sourceDocumentId") + "|"
                        + new BigDecimal(line.get("amountOriginal").toString()).setScale(2) + "|"
                        + new BigDecimal(line.get("amount").toString()).setScale(2)).reduce((a, b) -> a + "\n" + b).orElse("");
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static SaasMemberBalanceLot lot(SaasMemberBalanceAccount account, UUID id, UUID movement,
            UUID document, String original, String remaining) {
        return new SaasMemberBalanceLot(id, account, MemberBalanceType.LOYALTY,
                money(original), money(remaining), NOW, null, movement, document);
    }

    private static SaasMemberBalanceAccount account(UUID companyId, UUID memberId, String balance) {
        return new SaasMemberBalanceAccount(UUID.randomUUID(), companyId, memberId,
                money(balance), money("0"), money("0"), NOW);
    }

    private static BigDecimal money(String value) { return new BigDecimal(value).setScale(2); }
}
