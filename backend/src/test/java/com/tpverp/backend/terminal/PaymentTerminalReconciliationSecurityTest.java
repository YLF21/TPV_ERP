package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalReconciliationSecurityTest {
    @Test
    void masksProviderReferenceAuthorizationAndDiagnosticBeforePersistenceOrExposure() {
        var configuration = new CardTerminalConfiguration(UUID.randomUUID(), UUID.randomUUID(),
                PaymentCardMode.INTEGRATED, PaymentTerminalProvider.REDSYS_TPV_PC, true, true, "TPV",
                "ref", 1, "a".repeat(64), Map.of());
        var result = new PaymentTerminalResult(PaymentTerminalOperationStatus.APPROVED, "OK",
                "4548-8120-4940-0004", "5555 5555 5555 4444",
                "reference 4111 1111 1111 1111 authorization 123456789012");

        var batch = PaymentTerminalReconciliationBatch.reserve(UUID.randomUUID(),UUID.randomUUID(),configuration,
                LocalDate.of(2026,7,13),BigDecimal.TEN,Instant.EPOCH);
        batch.complete(BigDecimal.TEN,result,Instant.EPOCH.plusSeconds(1));
        var event = PaymentTerminalReconciliationEvent.from(UUID.randomUUID(), result, Instant.EPOCH);

        assertThat(batch.getExternalReference()).isEqualTo("****0004");
        assertThat(event.getDiagnostic()).contains("****1111", "****9012")
                .doesNotContain("4111 1111 1111 1111", "123456789012");
    }
}
