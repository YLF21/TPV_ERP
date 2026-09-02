package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ManualPaymentReconciliationAdapterTest {

    private final ManualPaymentReconciliationAdapter adapter = new ManualPaymentReconciliationAdapter();

    @Test
    void onlyAcceptsExplicitManualProvidersAndPositiveAmounts() {
        assertThatCode(() -> adapter.validate(request("MANUAL_BANK", "20.00")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> adapter.validate(request("STRIPE", "20.00")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> adapter.validate(request("MANUAL_GATEWAY", "-1")))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static CreatePaymentReconciliationRequest request(String provider, String amount) {
        return new CreatePaymentReconciliationRequest(null, provider, "EXT-1", amount, "EUR",
                Instant.parse("2026-09-01T10:00:00Z"), null);
    }
}
