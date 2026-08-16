package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoreVoucherConfigurationTest {

    @Test
    void defaultsTo365DaysAndCanSwitchToNoExpiryWithoutLosingThePeriod() {
        var configuration = new StoreVoucherConfiguration(UUID.randomUUID());
        var issuedOn = LocalDate.of(2026, 8, 16);

        assertThat(configuration.expirationMode()).isEqualTo(VoucherExpirationMode.DAYS);
        assertThat(configuration.expirationFor(issuedOn))
                .isEqualTo(LocalDate.of(2027, 8, 16));

        configuration.update(VoucherExpirationMode.NEVER, 365);
        assertThat(configuration.expirationFor(issuedOn)).isNull();
        assertThat(configuration.validityDays()).isEqualTo(365);
    }
}
