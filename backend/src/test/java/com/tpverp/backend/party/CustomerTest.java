package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.organization.Company;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CustomerTest {

    @Test
    void normalizesFiscalDocumentAndValidatesCompleteFiscalData() {
        var customer = new Customer(
                company(), " Cliente Uno ", DocumentType.NIF, " 12ab ",
                new FiscalAddress("Calle 1", "35001", "Las Palmas", "Las Palmas", "es"),
                null, null, null, CustomerRate.VENTA, new BigDecimal("5.50"));

        assertThat(customer.getFiscalName()).isEqualTo("Cliente Uno");
        assertThat(customer.getDocumentNumber()).isEqualTo("12AB");
        assertThat(customer.hasCompleteFiscalData()).isTrue();
    }

    @Test
    void preservesButBlocksBalanceWhenMemberBecomesRegularCustomer() {
        var customer = new Customer(
                company(), "Member", DocumentType.NIE, "x1",
                null, null, null, null, CustomerRate.MEMBER, BigDecimal.ZERO);
        customer.applyBalance(new BigDecimal("10.00"));

        customer.update(
                "Member", DocumentType.NIE, "x1", null, null, null, null,
                CustomerRate.VENTA, BigDecimal.ZERO);

        assertThat(customer.getMemberBalance()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> customer.applyBalance(BigDecimal.ONE.negate()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBalanceBelowZero() {
        var customer = new Customer(
                company(), "Member", DocumentType.NIF, "1",
                null, null, null, null, CustomerRate.MEMBER, BigDecimal.ZERO);

        assertThatThrownBy(() -> customer.applyBalance(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negativo");
    }

    @Test
    void conservaCodigoYFechaAlReactivarMember() {
        var customer = new Customer(
                company(), "Member", DocumentType.NIF, "2",
                null, null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);
        customer.activateMember("M-001-000001", LocalDate.of(2026, 6, 24));

        customer.deactivateMember();
        customer.activateMember("M-001-000002", LocalDate.of(2026, 7, 1));

        assertThat(customer.isMember()).isTrue();
        assertThat(customer.getMemberId()).isEqualTo("M-001-000001");
        assertThat(customer.getMemberSince()).isEqualTo(LocalDate.of(2026, 6, 24));
        assertThat(customer.getRate()).isEqualTo(CustomerRate.MEMBER);
    }

    private Company company() {
        return new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
    }
}
