package com.tpverp.saas.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.license.SaasCompany;
import com.tpverp.saas.loyalty.MemberBalanceType;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccount;
import com.tpverp.saas.loyalty.SaasMemberBalanceAccountRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceLotRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservation;
import com.tpverp.saas.loyalty.SaasMemberBalanceReservationLot;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaim;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimRepository;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionClaimStatus;
import com.tpverp.saas.loyalty.SaasMemberBalanceRetentionReceipt;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class MemberWalletRetentionProjectorTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
    private static final UUID SOURCE_DOCUMENT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void acceptsEpochSecondsWithNanosecondsAndExpiresAt() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        var account = account(companyId, memberId, "0");
        var accounts = mock(SaasMemberBalanceAccountRepository.class);
        var lots = mock(SaasMemberBalanceLotRepository.class);
        var claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId))
                .thenReturn(Optional.empty());
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> payload = new LinkedHashMap<>(payload(memberId, movementId, "1"));
        payload.put("createdAt", new BigDecimal("1788000000.123456789"));
        payload.put("expiresAt", new BigDecimal("1788003600.000000001"));

        lots(projector(accounts, lots, claims), event(companyId, lotId), payload);

        var saved = org.mockito.ArgumentCaptor.forClass(SaasMemberBalanceLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt())
                .isEqualTo(Instant.ofEpochSecond(1788000000L, 123456789));
        assertThat(saved.getValue().getExpiresAt())
                .isEqualTo(Instant.ofEpochSecond(1788003600L, 1));
    }

    @Test
    void acceptsLegacyEpochDoubleFromJsonPayload() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        var account = account(companyId, memberId, "0");
        var accounts = mock(SaasMemberBalanceAccountRepository.class);
        var lots = mock(SaasMemberBalanceLotRepository.class);
        var claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId))
                .thenReturn(Optional.empty());
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        Map<String, Object> payload = new LinkedHashMap<>(payload(memberId, movementId, "1"));
        payload.put("createdAt", Double.valueOf("1788018240.3205457"));

        lots(projector(accounts, lots, claims), event(companyId, lotId), payload);

        var saved = org.mockito.ArgumentCaptor.forClass(SaasMemberBalanceLot.class);
        verify(lots).save(saved.capture());
        assertThat(saved.getValue().getCreatedAt())
                .isEqualTo(Instant.ofEpochSecond(1788018240L, 320545700));
    }

    @Test
    void rejectsInvalidEpochScaleMillisAndCivilRange() {
        assertInvalidInstant(new BigDecimal("1788000000.1234567891"),
                "maximo 9 decimales");
        assertInvalidInstant(1788000000320L, "fuera del rango civil");
        assertInvalidInstant(new BigDecimal("253402300800"), "fuera del rango civil");
        assertInvalidInstant(Double.NaN, "instante");
        assertInvalidInstant(Double.POSITIVE_INFINITY, "instante");
    }

    @Test
    void replayWithSubMicrosecondCreatedAtIsIdempotent() {
        assertTemporalReplay(
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2026-08-29T00:00:00.015586100Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                false);
    }

    @Test
    void replayWithSubMicrosecondExpiresAtIsIdempotent() {
        assertTemporalReplay(
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015586100Z"),
                false);
    }

    @Test
    void replayWithOneMicrosecondCreatedAtIsConflict() {
        assertTemporalReplay(
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2026-08-29T00:00:00.015587000Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                true);
    }

    @Test
    void replayWithOneMicrosecondExpiresAtIsConflict() {
        assertTemporalReplay(
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2026-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015586000Z"),
                Instant.parse("2027-08-29T00:00:00.015587000Z"),
                true);
    }

    private static void assertTemporalReplay(
            Instant storedCreatedAt, Instant replayCreatedAt,
            Instant storedExpiresAt, Instant replayExpiresAt, boolean conflict) {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        var account = account(companyId, memberId, "0");
        var existingLot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10"), money("10"),
                storedCreatedAt, storedExpiresAt, movementId, SOURCE_DOCUMENT_ID);
        var accounts = mock(SaasMemberBalanceAccountRepository.class);
        var lots = mock(SaasMemberBalanceLotRepository.class);
        var claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(lots.findById(lotId)).thenReturn(Optional.of(existingLot));
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of());
        Map<String, Object> replay = new LinkedHashMap<>(payload(memberId, movementId, "10"));
        replay.put("createdAt", replayCreatedAt.toString());
        replay.put("expiresAt", replayExpiresAt.toString());
        var projector = projector(accounts, lots, claims);

        if (conflict) {
            assertThatThrownBy(() -> lots(projector, event(companyId, lotId), replay))
                    .hasMessageContaining("lotId ya existe con datos diferentes");
        } else {
            lots(projector, event(companyId, lotId), replay);
            lots(projector, event(companyId, lotId), replay);
            verify(lots, never()).save(any());
        }
    }

    private static void lots(MemberWalletSyncProjector projector, SaasSyncEvent event,
            Map<String, Object> payload) {
        projector.project(event, payload, NOW);
    }

    private static void assertInvalidInstant(Object createdAt, String reason) {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>(payload(memberId, movementId, "1"));
        payload.put("createdAt", createdAt);
        var projector = projector(
                mock(SaasMemberBalanceAccountRepository.class),
                mock(SaasMemberBalanceLotRepository.class),
                mock(SaasMemberBalanceRetentionClaimRepository.class));

        assertThatThrownBy(() -> lots(projector, event(companyId, lotId), payload))
                .hasMessageContaining(reason);
    }

    @Test
    void projectsAllPendingClaimsAndReplayDoesNotDoubleApply() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        SaasMemberBalanceRetentionClaim first = standaloneClaim(
                firstId, receipt(firstId, companyId, memberId), lotId, movementId, "3");
        SaasMemberBalanceRetentionClaim second = standaloneClaim(
                secondId, receipt(secondId, companyId, memberId), lotId, movementId, "3");
        first.commitPending(NOW);
        second.commitPending(NOW);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(first, second));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SaasCompany company = mock(SaasCompany.class);
        when(company.getId()).thenReturn(companyId);
        SaasSyncEvent event = mock(SaasSyncEvent.class);
        when(event.getCompany()).thenReturn(company);
        when(event.getEntityId()).thenReturn(lotId);
        MemberWalletSyncProjector projector = new MemberWalletSyncProjector(
                accounts, lots, new ObjectMapper(), claims, mock(
                        com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository.class));

        projector.project(event, payload(memberId, movementId, "10"), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("4.00");
        assertThat(first.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
        assertThat(second.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
    }

    @Test
    void allocatesFiniteLateLotAcrossPendingClaimsAndRecordsShortfall() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        SaasMemberBalanceRetentionClaim first = standaloneClaim(
                firstId, receipt(firstId, companyId, memberId), lotId, movementId, "6");
        SaasMemberBalanceRetentionClaim second = standaloneClaim(
                secondId, receipt(secondId, companyId, memberId), lotId, movementId, "6");
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(first, second));
        when(claims.findByReceipt_OperationIdOrderByLotIdAsc(any()))
                .thenAnswer(invocation -> invocation.getArgument(0).equals(firstId)
                        ? List.of(first) : List.of(second));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        projector(accounts, lots, claims).project(
                event(companyId, lotId), payload(memberId, movementId, "10"), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        assertThat(first.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
        assertThat(second.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.APPLIED);
        assertThat(first.getHeldAmount()).isEqualByComparingTo("6.00");
        assertThat(second.getHeldAmount()).isEqualByComparingTo("4.00");
        assertThat(first.getReceipt().getRecoveredKnown()).isEqualByComparingTo("6.00");
        assertThat(second.getReceipt().getRecoveredKnown()).isEqualByComparingTo("4.00");
        assertThat(second.getReceipt().getSpentShortfall()).isEqualByComparingTo("2.00");
    }

    @Test
    void activeMissingClaimUsesNetCreditForReservationSnapshot() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "12.86");
        SaasMemberBalanceReservation reservation = reservation(account, NOW);
        SaasMemberBalanceRetentionClaim pending = standaloneClaim(
                UUID.randomUUID(), receipt(UUID.randomUUID(), companyId, memberId, "9"),
                lotId, movementId, "9");
        pending.commitPending(NOW);
        SaasMemberBalanceRetentionClaim missing = claim(reservation, lotId, movementId, "2");
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(pending, missing));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var reservationLots = mock(com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository.class);
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenReturn(List.of());
        SaasCompany company = mock(SaasCompany.class);
        when(company.getId()).thenReturn(companyId);
        SaasSyncEvent event = mock(SaasSyncEvent.class);
        when(event.getCompany()).thenReturn(company);
        when(event.getEntityId()).thenReturn(lotId);
        MemberWalletSyncProjector projector = new MemberWalletSyncProjector(
                accounts, lots, new ObjectMapper(), claims, reservationLots);

        projector.project(event, payload(memberId, movementId, "10"), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("13.86");
        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("13.86");
        assertThat(reservation.getReservedTotal().subtract(missing.getHeldAmount()))
                .isEqualByComparingTo("12.86");
        assertThat(missing.getHeldAmount()).isEqualByComparingTo("1.00");
        assertThat(missing.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
    }

    @Test
    void expiredMissingClaimIsCancelledAndCreditsNormally() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(), "T", "S",
                BigDecimal.ZERO.setScale(2), NOW.minus(Duration.ofMinutes(5)), Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim missing = claim(reservation, lotId, movementId, "10");
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(missing));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        MemberWalletSyncProjector projector = projector(accounts, lots, claims);
        projector.project(event(companyId, lotId), payload(memberId, movementId, "10"), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(missing.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
    }

    @Test
    void expiredMissingClaimDoesNotCompeteWithActiveClaimForTheSameLot() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceReservation expiredReservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(), "T1", "S1",
                BigDecimal.ZERO.setScale(2), NOW.minus(Duration.ofMinutes(5)), Duration.ofMinutes(2));
        SaasMemberBalanceReservation activeReservation = reservation(account, NOW);
        SaasMemberBalanceRetentionClaim expired = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), expiredReservation, lotId, movementId, SOURCE_DOCUMENT_ID,
                money("10"), money("5"), SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaim active = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), activeReservation, lotId, movementId, SOURCE_DOCUMENT_ID,
                money("10"), money("5"), SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(expired, active));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var reservationLots = mock(com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository.class);
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(activeReservation.getId()))
                .thenReturn(List.of());

        projector(accounts, lots, claims, reservationLots).project(
                event(companyId, lotId), payload(memberId, movementId, "10"), NOW);

        assertThat(expired.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
        assertThat(active.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
        assertThat(active.getHeldAmount()).isEqualByComparingTo("5.00");
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void rejectsClaimWithDifferentOriginalAmountBeforeCredit() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceReservation reservation = reservation(account, NOW);
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId,
                SOURCE_DOCUMENT_ID,
                money("9"), money("3"), SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING, NOW);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(claim));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> projector(accounts, lots, claims)
                .project(event(companyId, lotId), payload(memberId, movementId, "10"), NOW))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING);
    }

    @Test
    void validatesEveryClaimBeforeMutatingAnyClaimOrCreatingAccount() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceReservation reservation = reservation(account, NOW);
        SaasMemberBalanceRetentionClaim valid = new SaasMemberBalanceRetentionClaim(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), reservation,
                lotId, movementId, SOURCE_DOCUMENT_ID, money("10"), money("3"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaim invalid = new SaasMemberBalanceRetentionClaim(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), reservation, lotId, movementId,
                SOURCE_DOCUMENT_ID, money("9"), money("3"), SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(valid, invalid));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        MemberWalletSyncProjector projector = projector(accounts, lots, claims);

        assertThatThrownBy(() -> projector.project(
                event(companyId, lotId), payload(memberId, movementId, "10"), NOW))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(valid.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_MISSING);
        assertThat(valid.getHeldAmount()).isEqualByComparingTo("3.00");
        assertThat(invalid.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_MISSING);
        assertThat(invalid.getHeldAmount()).isEqualByComparingTo("3.00");
        verify(accounts, never()).save(any());
        verify(lots, never()).save(any());
        assertThat(account.getBalance()).isEqualByComparingTo("0.00");
    }

    @Test
    void expiredClaimAndLaterInvalidClaimLeaveAllClaimsUnchanged() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceReservation expiredReservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(), "T1", "S1",
                BigDecimal.ZERO.setScale(2), NOW.minus(Duration.ofMinutes(5)), Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim expired = new SaasMemberBalanceRetentionClaim(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), expiredReservation,
                lotId, movementId, SOURCE_DOCUMENT_ID, money("10"), money("3"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaim invalid = new SaasMemberBalanceRetentionClaim(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), expiredReservation,
                lotId, movementId, SOURCE_DOCUMENT_ID, money("9"), money("3"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(expired, invalid));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);

        assertThatThrownBy(() -> projector(accounts, lots, claims).project(
                event(companyId, lotId), payload(memberId, movementId, "10"), NOW))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);

        assertThat(expired.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_MISSING);
        assertThat(expired.getHeldAmount()).isEqualByComparingTo("3.00");
        assertThat(invalid.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_MISSING);
        verify(accounts, never()).save(any());
        verify(lots, never()).save(any());
    }

    @Test
    void replayingExistingLotIncorporatesActiveMissingClaimExactlyOnce() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "10");
        SaasMemberBalanceReservation reservation = reservation(account, NOW);
        SaasMemberBalanceRetentionClaim missing = claim(reservation, lotId, movementId, "5");
        SaasMemberBalanceLot existingLot = new SaasMemberBalanceLot(
                lotId, account, MemberBalanceType.LOYALTY, money("10"), money("10"), NOW,
                null, movementId, SOURCE_DOCUMENT_ID);
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(missing));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        when(lots.findById(lotId)).thenReturn(Optional.of(existingLot));
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        var reservationLots = mock(com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository.class);
        var links = new java.util.ArrayList<SaasMemberBalanceReservationLot>();
        when(reservationLots.findByReservation_IdOrderByLot_CreatedAtAscLot_IdAsc(reservation.getId()))
                .thenAnswer(invocation -> List.copyOf(links));
        when(reservationLots.save(any())).thenAnswer(invocation -> {
            links.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        MemberWalletSyncProjector projector = projector(accounts, lots, claims, reservationLots);

        projector.project(event(companyId, lotId), payload(memberId, movementId, "10"), NOW);
        projector.project(event(companyId, lotId), payload(memberId, movementId, "10"), NOW);

        assertThat(missing.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN);
        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("15.00");
        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).getRemainingAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    void ignoresSameKeysFromAnotherTenant() {
        UUID companyId = UUID.randomUUID();
        UUID otherCompanyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID movementId = UUID.randomUUID();
        SaasMemberBalanceAccount account = account(companyId, memberId, "0");
        SaasMemberBalanceAccount otherAccount = account(otherCompanyId, memberId, "0");
        SaasMemberBalanceReservation otherReservation = reservation(otherAccount, NOW);
        SaasMemberBalanceRetentionClaim otherClaim = claim(otherReservation, lotId, movementId, "10");
        SaasMemberBalanceRetentionClaimRepository claims = mock(SaasMemberBalanceRetentionClaimRepository.class);
        when(claims.findByLotIdAndSourceMovementIdAndStatusIn(any(), any(), any()))
                .thenReturn(List.of(otherClaim));
        SaasMemberBalanceLotRepository lots = mock(SaasMemberBalanceLotRepository.class);
        SaasMemberBalanceAccountRepository accounts = mock(SaasMemberBalanceAccountRepository.class);
        when(accounts.findForUpdate(companyId, memberId)).thenReturn(Optional.of(account));
        when(lots.findById(lotId)).thenReturn(Optional.empty());
        when(lots.findByCompanyIdAndSourceMovementId(companyId, movementId)).thenReturn(Optional.empty());
        when(lots.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        projector(accounts, lots, claims).project(
                event(companyId, lotId), payload(memberId, movementId, "10"), NOW);

        assertThat(account.getBalance()).isEqualByComparingTo("10.00");
        assertThat(otherClaim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.HELD_MISSING);
    }

    private static MemberWalletSyncProjector projector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims) {
        return projector(accounts, lots, claims,
                mock(com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository.class));
    }

    private static MemberWalletSyncProjector projector(
            SaasMemberBalanceAccountRepository accounts,
            SaasMemberBalanceLotRepository lots,
            SaasMemberBalanceRetentionClaimRepository claims,
            com.tpverp.saas.loyalty.SaasMemberBalanceReservationLotRepository reservationLots) {
        return new MemberWalletSyncProjector(accounts, lots, new ObjectMapper(), claims, reservationLots);
    }

    private static SaasSyncEvent event(UUID companyId, UUID lotId) {
        SaasCompany company = mock(SaasCompany.class);
        when(company.getId()).thenReturn(companyId);
        SaasSyncEvent event = mock(SaasSyncEvent.class);
        when(event.getCompany()).thenReturn(company);
        when(event.getEntityId()).thenReturn(lotId);
        return event;
    }

    private static SaasMemberBalanceRetentionClaim claim(
            SaasMemberBalanceReservation reservation, UUID lotId, UUID movementId, String amount) {
        return new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, lotId, movementId,
                SOURCE_DOCUMENT_ID, money("10"), money(amount), SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);
    }

    private static SaasMemberBalanceRetentionClaim standaloneClaim(
            UUID id, SaasMemberBalanceRetentionReceipt receipt, UUID lotId, UUID movementId,
            String amount) {
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                id, null, lotId, movementId, SOURCE_DOCUMENT_ID, money("10"), money(amount),
                SaasMemberBalanceRetentionClaimStatus.COMMITTED_PENDING, NOW);
        claim.attachReceipt(receipt);
        return claim;
    }

    private static SaasMemberBalanceRetentionReceipt receipt(
            UUID operationId, UUID companyId, UUID memberId) {
        return receipt(operationId, companyId, memberId, "6");
    }

    private static SaasMemberBalanceRetentionReceipt receipt(
            UUID operationId, UUID companyId, UUID memberId, String amount) {
        return new SaasMemberBalanceRetentionReceipt(
                operationId, companyId, UUID.randomUUID(), memberId, UUID.randomUUID(),
                UUID.randomUUID(), money(amount),
                "0000000000000000000000000000000000000000000000000000000000000000",
                money("0"), money(amount), money("0"), NOW);
    }

    private static SaasMemberBalanceReservation reservation(
            SaasMemberBalanceAccount account, Instant now) {
        return new SaasMemberBalanceReservation(UUID.randomUUID(), account, UUID.randomUUID(),
                UUID.randomUUID(), "T", "S", account.getBalance(), now, Duration.ofMinutes(2));
    }

    private static SaasMemberBalanceAccount account(UUID companyId, UUID memberId, String amount) {
        return new SaasMemberBalanceAccount(UUID.randomUUID(), companyId, memberId,
                money(amount), money("0"), money("0"), NOW);
    }

    private static Map<String, Object> payload(UUID memberId, UUID movementId, String amount) {
        return Map.of("schemaVersion", 2, "memberId", memberId.toString(),
                "balanceType", MemberBalanceType.LOYALTY.name(), "amount", amount,
                "createdAt", NOW.toString(), "sourceMovementId", movementId.toString(),
                "documentId", SOURCE_DOCUMENT_ID.toString());
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
