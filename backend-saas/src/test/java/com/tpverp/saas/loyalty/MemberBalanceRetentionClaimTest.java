package com.tpverp.saas.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberBalanceRetentionClaimTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    void replacementKeepsClaimIdentityAndUpdatesHeldAmount() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), companyId, memberId, money("12.86"),
                money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("12.86"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                money(".09"), money(".09"),
                SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        UUID claimId = claim.getId();

        claim.replace(claim.getSourceMovementId(), claim.getSourceDocumentId(), money(".09"), money(".09"),
                money(".05"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW.plusSeconds(1));

        assertThat(claim.getId()).isEqualTo(claimId);
        assertThat(claim.getAmount()).isEqualByComparingTo(".09");
        assertThat(claim.getHeldAmount()).isEqualByComparingTo(".05");
    }

    @Test
    void lateKnownLotExpandsSnapshotWithoutIncreasingSpendable() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("12.86"),
                money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("12.86"), NOW, Duration.ofMinutes(2));
        reservation.incorporateWalletLot(MemberBalanceType.LOYALTY, money(".09"));

        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("12.95");
        assertThat(reservation.getReservedLoyaltyAmount()).isEqualByComparingTo("12.95");
    }

    @Test
    void typedReservationKeepsLegacyTotalAsLoyaltyAndExposesBothTypedBuckets() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("13"),
                money("10"), money("3"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("10"), money("3"), NOW, Duration.ofMinutes(2));

        assertThat(reservation.getReservedTotal()).isEqualByComparingTo("10.00");
        assertThat(reservation.getReservedLoyaltyAmount()).isEqualByComparingTo("10.00");
        assertThat(reservation.getReservedReturnCreditAmount()).isEqualByComparingTo("3.00");
        assertThat(reservation.getReservedLoyaltyAmount().add(reservation.getReservedReturnCreditAmount()))
                .isEqualByComparingTo("13.00");
    }

    @Test
    void typedReservationSupportsLoyaltyOnlyAndReturnCreditOnlyBuckets() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("17.63"),
                money("13.21"), money("4.42"), NOW);
        SaasMemberBalanceReservation loyaltyOnly = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("13.21"), money("0"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceReservation returnCreditOnly = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S2", money("0"), money("4.42"), NOW, Duration.ofMinutes(2));

        assertThat(loyaltyOnly.getReservedTotal()).isEqualByComparingTo("13.21");
        assertThat(returnCreditOnly.getReservedTotal()).isEqualByComparingTo("0.00");
        assertThat(returnCreditOnly.getReservedReturnCreditAmount()).isEqualByComparingTo("4.42");
    }

    @Test
    void prepareAndReprepareKeepLegacyPreparedAmountAtLoyaltyBucket() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("13"),
                money("10"), money("3"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("10"), money("3"), NOW, Duration.ofMinutes(2));
        UUID operationId = UUID.randomUUID();

        reservation.prepareTyped(operationId, money("4"), money("2"), NOW);
        assertThat(reservation.getPreparedAmount()).isEqualByComparingTo("4.00");
        assertThat(reservation.getPreparedLoyaltyAmount()).isEqualByComparingTo("4.00");
        assertThat(reservation.getPreparedReturnCreditAmount()).isEqualByComparingTo("2.00");

        reservation.reprepareTyped(operationId, money("5"), money("1"), NOW.plusSeconds(1));
        assertThat(reservation.getPreparedAmount()).isEqualByComparingTo("5.00");
        assertThat(reservation.getPreparedLoyaltyAmount()).isEqualByComparingTo("5.00");
        assertThat(reservation.getPreparedReturnCreditAmount()).isEqualByComparingTo("1.00");
    }

    @Test
    void finalizeMixedReservationKeepsReturnCreditTypedAndLegacyConsumedTotalLoyaltyOnly() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("13"),
                money("10"), money("3"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("10"), money("3"), NOW, Duration.ofMinutes(2));
        UUID operationId = UUID.randomUUID();
        reservation.prepareTyped(operationId, money("4"), money("2"), NOW);

        reservation.finalizePreparedTyped(operationId, NOW.plusSeconds(1), money("1.50"));

        assertThat(reservation.getConsumedLoyaltyAmount()).isEqualByComparingTo("5.50");
        assertThat(reservation.getConsumedTotal()).isEqualByComparingTo("5.50");
        assertThat(reservation.getConsumedReturnCreditAmount()).isEqualByComparingTo("2.00");
    }

    @Test
    void typedReservationRejectsNegativeWalletAmount() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("1"),
                money("1"), money("0"), NOW);

        assertThatThrownBy(() -> new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("1"), money("-0.01"), NOW, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimLifecycleDoesNotApplyCancelledClaim() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("1"),
                money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("1"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(),
                money(".09"), money(".09"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW);

        claim.release(NOW.plusSeconds(1));

        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
        claim.apply(NOW.plusSeconds(2));
        assertThat(claim.getStatus()).isEqualTo(SaasMemberBalanceRetentionClaimStatus.CANCELLED);
    }

    @Test
    void attachingReceiptTransfersClaimOwnershipFromReservation() {
        UUID companyId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), companyId, memberId, money("1"), money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("1"), NOW, Duration.ofMinutes(2));
        SaasMemberBalanceRetentionClaim claim = new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(), sourceDocumentId,
                money("1"), money("1"), SaasMemberBalanceRetentionClaimStatus.HELD_KNOWN, NOW);
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                UUID.randomUUID(), companyId, UUID.randomUUID(), memberId, sourceDocumentId, null,
                money("1"), "012345678901234567890123456789012345678901234567890123456789abcd",
                money("1"), money("0"), money("0"), NOW);

        claim.attachReceipt(receipt);

        assertThat(claim.getReservation()).isNull();
        assertThat(claim.getReceipt()).isSameAs(receipt);
    }

    @Test
    void receiptReturnDocumentCanBeAttachedOnceAndDifferentValueDoesNotMatch() {
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();
        UUID sourceDocumentId = UUID.randomUUID();
        UUID returnDocumentId = UUID.randomUUID();
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                UUID.randomUUID(), companyId, storeId, memberId, sourceDocumentId, null,
                money("3"), "012345678901234567890123456789012345678901234567890123456789abcd",
                money("3"), money("0"), money("0"), NOW);

        assertThat(receipt.matchesImmutable(companyId, storeId, memberId, sourceDocumentId,
                returnDocumentId, money("3"), receipt.getFingerprint())).isTrue();
        receipt.attachReturnDocument(returnDocumentId, NOW.plusSeconds(1));
        assertThat(receipt.getReturnDocumentId()).isEqualTo(returnDocumentId);
        assertThat(receipt.matchesImmutable(companyId, storeId, memberId, sourceDocumentId,
                UUID.randomUUID(), money("3"), receipt.getFingerprint())).isFalse();
        assertThat(receipt.matchesImmutable(companyId, storeId, memberId, sourceDocumentId,
                money("3"), receipt.getFingerprint())).isFalse();
    }

    @Test
    void claimRequiresSourceDocument() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("1"),
                money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("1"), NOW, Duration.ofMinutes(2));

        assertThatThrownBy(() -> new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(), null,
                money("1"), money("1"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void claimRejectsZeroAmount() {
        SaasMemberBalanceAccount account = new SaasMemberBalanceAccount(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), money("1"),
                money("0"), money("0"), NOW);
        SaasMemberBalanceReservation reservation = new SaasMemberBalanceReservation(
                UUID.randomUUID(), account, UUID.randomUUID(), UUID.randomUUID(),
                "T1", "S1", money("1"), NOW, Duration.ofMinutes(2));

        assertThatThrownBy(() -> new SaasMemberBalanceRetentionClaim(
                UUID.randomUUID(), reservation, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), money("1"), money("0"),
                SaasMemberBalanceRetentionClaimStatus.HELD_MISSING, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiptMetricsMustReconcileToAttributedAmount() {
        SaasMemberBalanceRetentionReceipt receipt = new SaasMemberBalanceRetentionReceipt(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), money("3"),
                "012345678901234567890123456789012345678901234567890123456789abcd",
                money("3"), money("0"), money("0"), NOW);

        assertThatThrownBy(() -> receipt.replaceMetrics(
                money("2"), money("0"), money("0"), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(receipt.getRecoveredKnown()).isEqualByComparingTo("3.00");
    }

    @Test
    void receiptConstructorRejectsMetricsThatDoNotConserveAmount() {
        assertThatThrownBy(() -> new SaasMemberBalanceRetentionReceipt(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), money("3"),
                "012345678901234567890123456789012345678901234567890123456789abcd",
                money("2"), money("0"), money("0"), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
