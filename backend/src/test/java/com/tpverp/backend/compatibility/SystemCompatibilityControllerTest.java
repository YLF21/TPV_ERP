package com.tpverp.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemCompatibilityControllerTest {
    @Test
    void publishesVersionsCapabilitiesAndStablePaymentStates() {
        var view = new SystemCompatibilityController("2.4.0", "1", "0.0.1").compatibility();

        assertThat(view.backendVersion()).isEqualTo("2.4.0");
        assertThat(view.capabilities()).contains("PAYMENT_RECOVERY", "PAYMENT_REFUND", "CORRELATION_ID");
        assertThat(view.paymentStates())
                .containsEntry("PENDING", "INICIADO")
                .containsEntry("SENT", "PROCESANDO")
                .containsEntry("TIMEOUT", "INCIERTO")
                .containsEntry("CANCELLED", "CANCELADO");
    }
}
