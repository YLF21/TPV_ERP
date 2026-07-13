package com.tpverp.backend.terminal.bridge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PaymentTerminalBridgeConfigurationTest {

    @Test
    void createsUnavailableClientWithoutObjectMapperWhenBridgeIsNotConfigured() {
        try (var context = new AnnotationConfigApplicationContext(
                PaymentTerminalBridgeConfiguration.class)) {
            assertThat(context.getBean(PaymentTerminalBridgeClient.class))
                    .isInstanceOf(UnavailablePaymentTerminalBridgeClient.class);
        }
    }
}
