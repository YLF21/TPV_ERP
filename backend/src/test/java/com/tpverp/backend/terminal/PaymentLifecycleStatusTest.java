package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentLifecycleStatusTest {
    @Test
    void mapsProviderStatesToStableBusinessLifecycle() {
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.PENDING)).isEqualTo(PaymentLifecycleStatus.INICIADO);
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.SENT)).isEqualTo(PaymentLifecycleStatus.PROCESANDO);
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.APPROVED)).isEqualTo(PaymentLifecycleStatus.APROBADO);
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.DECLINED)).isEqualTo(PaymentLifecycleStatus.RECHAZADO);
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.TIMEOUT)).isEqualTo(PaymentLifecycleStatus.INCIERTO);
        assertThat(PaymentLifecycleStatus.from(PaymentTerminalOperationStatus.CANCELLED)).isEqualTo(PaymentLifecycleStatus.CANCELADO);
    }

    @Test
    void distinguishesFinalAndRecoverableErrors() {
        var recoverable = operation();
        recoverable.fail("NETWORK", "timeout", false, Instant.now());
        assertThat(PaymentLifecycleStatus.from(recoverable)).isEqualTo(PaymentLifecycleStatus.INCIERTO);

        var rejected = operation();
        rejected.fail("PROVIDER_REJECTED", "rejected", true, Instant.now());
        assertThat(PaymentLifecycleStatus.from(rejected)).isEqualTo(PaymentLifecycleStatus.RECHAZADO);
    }

    private static PaymentTerminalOperation operation() {
        return PaymentTerminalOperation.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PaymentTerminalProvider.REDSYS_TPV_PC, PaymentTerminalMode.SIMULATED,
                PaymentTerminalOperationType.CHARGE, null, UUID.randomUUID().toString(), "a".repeat(64),
                BigDecimal.ONE, null, -1, Instant.now());
    }
}
