package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerPendingSaleCheckoutTest {

    @Test
    void reservationMatchesItsPayloadHashAndCompletesWithTheCreatedDocument() {
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), Instant.parse("2026-07-16T10:15:30Z"));
        var documentId = UUID.randomUUID();

        assertThat(checkout.matchesHash("a".repeat(64))).isTrue();
        assertThat(checkout.matchesHash("b".repeat(64))).isFalse();
        assertThat(checkout.isCompleted()).isFalse();

        checkout.complete(documentId);

        assertThat(checkout.isCompleted()).isTrue();
        assertThat(checkout.getDocumentId()).isEqualTo(documentId);
        assertThat(checkout.getCompletedAt()).isNotNull();
    }
}
