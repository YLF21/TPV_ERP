package com.tpverp.backend.compatibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;

class SystemCompatibilityControllerTest {
    @ParameterizedTest
    @ValueSource(strings = {"0.1.0", "1.0.0"})
    void publishesBuildVersionCapabilitiesAndStablePaymentStates(String version) {
        var properties = new Properties();
        properties.setProperty("version", version);
        var view = new SystemCompatibilityController(new BuildProperties(properties), "1", "0.0.1").compatibility();

        assertThat(view.backendVersion()).isEqualTo(version);
        assertThat(view.capabilities()).contains("PAYMENT_RECOVERY", "PAYMENT_REFUND", "CORRELATION_ID");
        assertThat(view.paymentStates())
                .containsEntry("PENDING", "INICIADO")
                .containsEntry("SENT", "PROCESANDO")
                .containsEntry("TIMEOUT", "INCIERTO")
                .containsEntry("CANCELLED", "CANCELADO");
    }
}
