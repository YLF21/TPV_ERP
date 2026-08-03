package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemberDocumentLoyaltySettlementTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    @Test
    void accumulatesExactGeneratedGrantedAndDebtAmounts() {
        var settlement = settlement("40.00");

        settlement.recordAccrual(
                new BigDecimal("10.00"),
                10,
                4,
                6,
                new BigDecimal("1.00"),
                new BigDecimal("0.40"),
                new BigDecimal("0.60"),
                NOW);

        assertThat(settlement.getEligiblePaidAmount()).isEqualByComparingTo("10.00");
        assertThat(settlement.getGeneratedPoints()).isEqualTo(10);
        assertThat(settlement.getGrantedPoints()).isEqualTo(4);
        assertThat(settlement.getPointsAppliedToDebt()).isEqualTo(6);
        assertThat(settlement.getGeneratedBalance()).isEqualByComparingTo("1.00");
        assertThat(settlement.getGrantedBalance()).isEqualByComparingTo("0.40");
        assertThat(settlement.getBalanceAppliedToDebt()).isEqualByComparingTo("0.60");
    }

    @Test
    void rejectsAnInconsistentBenefitBreakdown() {
        var settlement = settlement("40.00");

        assertThatThrownBy(() -> settlement.recordAccrual(
                new BigDecimal("10.00"),
                10,
                4,
                5,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("desglose generado");
    }

    @Test
    void plansCumulativePartialReversalsAndClosesWithExactTotals() {
        var settlement = settlement("20.00");
        settlement.recordAccrual(
                new BigDecimal("10.00"),
                7,
                5,
                2,
                new BigDecimal("1.01"),
                new BigDecimal("0.71"),
                new BigDecimal("0.30"),
                NOW);
        settlement.updateMemberBalanceUsed(new BigDecimal("8.00"), NOW);

        var partial = settlement.planReversal(
                new BigDecimal("10.00"), new BigDecimal("10.00"));
        settlement.recordReversal(partial, 0, BigDecimal.ZERO, NOW);

        assertThat(partial.points()).isEqualTo(3);
        assertThat(partial.grantedPointsDelta()).isEqualTo(2);
        assertThat(partial.debtPointsDelta()).isEqualTo(1);
        assertThat(partial.balance()).isEqualByComparingTo("0.50");
        assertThat(partial.memberBalanceRestoreDelta()).isEqualByComparingTo("2.00");

        var complete = settlement.planReversal(
                new BigDecimal("40.00"), new BigDecimal("20.00"));

        assertThat(complete.points()).isEqualTo(7);
        assertThat(complete.balance()).isEqualByComparingTo("1.01");
        assertThat(complete.restoredMemberBalance()).isEqualByComparingTo("8.00");
    }

    private MemberDocumentLoyaltySettlement settlement(String eligibleAmount) {
        var company = PartyTestData.company();
        var customer = new Customer(
                company,
                "Cliente",
                DocumentType.NIF,
                "1",
                null,
                null,
                null,
                null,
                CustomerRate.VENTA,
                BigDecimal.ZERO);
        var member = new Member(
                customer, "M-001-000001", LocalDate.of(2026, 8, 2));
        return new MemberDocumentLoyaltySettlement(
                UUID.randomUUID(), member, new BigDecimal("40.00"),
                new BigDecimal(eligibleAmount), NOW);
    }
}
