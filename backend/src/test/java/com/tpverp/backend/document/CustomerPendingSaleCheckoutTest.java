package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerPendingSaleCheckoutTest {

    @Test
    void expiredOwnerLeaseCanBeClaimedButLiveLeaseCannotBeStolen() {
        var now = Instant.parse("2026-07-16T10:15:30Z");
        var originalOwner = UUID.randomUUID();
        var nextOwner = UUID.randomUUID();
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), originalOwner, now.plusSeconds(30), now);

        assertThat(checkout.claim(nextOwner, now.plusSeconds(20), now.plusSeconds(5))).isFalse();
        assertThat(checkout.claim(nextOwner, now.plusSeconds(61), now.plusSeconds(31))).isTrue();
        assertThat(checkout.isOwnedBy(nextOwner)).isTrue();
    }

    @Test
    void reservationMatchesItsPayloadHashAndCompletesWithTheCreatedDocument() {
        var checkout = CustomerPendingSaleCheckout.reserve(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), Instant.parse("2026-07-16T10:15:30Z"));
        var documentId = UUID.randomUUID();
        var completedAt = Instant.parse("2026-07-16T10:16:30Z");

        assertThat(checkout.matchesHash("a".repeat(64))).isTrue();
        assertThat(checkout.matchesHash("b".repeat(64))).isFalse();
        assertThat(checkout.isCompleted()).isFalse();

        checkout.complete(documentId, completedAt);

        assertThat(checkout.isCompleted()).isTrue();
        assertThat(checkout.getDocumentId()).isEqualTo(documentId);
        assertThat(checkout.getCompletedAt()).isEqualTo(completedAt);
    }
}
