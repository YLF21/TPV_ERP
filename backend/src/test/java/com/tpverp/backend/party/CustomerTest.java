package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.organization.Company;
import java.math.BigDecimal;
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
    void activationAndDeactivationAreIdempotent() {
        var customer = new Customer(
                company(), "Cliente", DocumentType.NIF, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);

        customer.deactivate();
        customer.deactivate();
        assertThat(customer.isActive()).isFalse();

        customer.activate();
        customer.activate();
        assertThat(customer.isActive()).isTrue();
    }

    @Test
    void creditPolicyDefaultsAreCompatibleAndConfigurationIsValidated() {
        var customer = new Customer(
                company(), "Cliente", DocumentType.NIF, "1", null,
                null, null, null, CustomerRate.VENTA, BigDecimal.ZERO);

        assertThat(customer.isCreditEnabled()).isTrue();
        assertThat(customer.getCreditLimit()).isNull();
        assertThat(customer.getPaymentTermDays()).isEqualTo(30);
        assertThat(customer.isCreditBlocked()).isFalse();
        assertThat(customer.isBlockOnOverdue()).isFalse();

        customer.configureCredit(false, new BigDecimal("250.00"), 45, true, true);

        assertThat(customer.isCreditEnabled()).isFalse();
        assertThat(customer.getCreditLimit()).isEqualByComparingTo("250.00");
        assertThat(customer.getPaymentTermDays()).isEqualTo(45);
        assertThat(customer.isCreditBlocked()).isTrue();
        assertThat(customer.isBlockOnOverdue()).isTrue();
    }

    private Company company() {
        return new Company("B00000000", "Company", Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES"));
    }
}
