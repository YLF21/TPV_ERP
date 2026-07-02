package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class MemberTest {

    @Test
    void preservesButBlocksBalanceWhenMemberBecomesInactive() {
        var member = member();
        member.applyBalance(new BigDecimal("10.00"));

        member.deactivate();

        assertThat(member.getMemberBalance()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> member.applyBalance(BigDecimal.ONE.negate()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBalanceBelowZero() {
        var member = member();

        assertThatThrownBy(() -> member.applyBalance(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    void conservaCodigoYFechaAlReactivarMember() {
        var member = member();

        member.deactivate();
        member.activate();

        assertThat(member.isActive()).isTrue();
        assertThat(member.getMemberId()).isEqualTo("M-001-000001");
        assertThat(member.getMemberSince()).isEqualTo(LocalDate.of(2026, 6, 24));
    }

    @Test
    void accumulatesPointsWithoutAllowingNegativeTotal() {
        var member = member();

        member.applyPoints(10);

        assertThat(member.getMemberPoints()).isEqualTo(10);
        assertThatThrownBy(() -> member.applyPoints(-11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("puntos");
    }

    private Member member() {
        var customer = new Customer(
                PartyTestData.company(), "Member", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        return new Member(customer, "M-001-000001", LocalDate.of(2026, 6, 24));
    }
}
