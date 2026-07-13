package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentTerminalReceiptRecordTest {
    @Test
    void removesCardholderSecretsAndMasksLongDigitSequencesBeforePersistence() {
        var record = PaymentTerminalReceiptRecord.create(UUID.randomUUID(), UUID.randomUUID(),
                new PaymentTerminalReceipt(PaymentTerminalOperationStatus.APPROVED, "OK",
                        "COMERCIO DEMO\nTARJETA: 4548812049400004\nCVV: 123\nAUT: 987654"),
                Instant.parse("2026-07-13T08:00:00Z"));

        assertThat(record.getReceiptText()).contains("COMERCIO DEMO", "****0004", "AUT: 987654")
                .doesNotContain("4548812049400004", "CVV: 123");
    }

    @Test
    void masksPanWithSpacesOrHyphensAndCardNumberLabels() {
        var spaced = PaymentTerminalReceiptRecord.create(UUID.randomUUID(), UUID.randomUUID(),
                new PaymentTerminalReceipt(PaymentTerminalOperationStatus.APPROVED, "OK",
                        "Numero tarjeta: 4548 8120 4940 0004\nCARD NUMBER: 4111-1111-1111-1111"),
                Instant.parse("2026-07-13T08:00:00Z"));

        assertThat(spaced.getReceiptText()).contains("****0004", "****1111")
                .doesNotContain("4548 8120 4940 0004", "4111-1111-1111-1111");
    }
}
