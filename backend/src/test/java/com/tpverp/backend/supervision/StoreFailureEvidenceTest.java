package com.tpverp.backend.supervision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.tpverp.backend.sync.SyncOperation;
import com.tpverp.backend.sync.SyncOutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StoreFailureEvidenceTest {
    @Test
    void successfulDeliveryRetainsRealFailureCountAndStaleClaimsCannotIncreaseIt() {
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        var event = new SyncOutboxEvent(UUID.randomUUID(), UUID.randomUUID(), null, "DOCUMENTO", UUID.randomUUID(),
                SyncOperation.CREAR, Map.of("privateCustomer", "never reported"), first);
        UUID claim = UUID.randomUUID(); event.claim(claim, first);
        assertThat(event.markRetry(UUID.randomUUID(), "sensitive error", first.plusSeconds(5), first.plusSeconds(1))).isFalse();
        assertThat(event.getFailureCount()).isZero();
        assertThat(event.markRetry(claim, "sensitive error", first.plusSeconds(5), first.plusSeconds(1))).isTrue();
        UUID retry = UUID.randomUUID(); event.claim(retry, first.plusSeconds(5));
        event.markSent(retry, first.plusSeconds(6));
        assertThat(event.getFailureCount()).isEqualTo(1);
        assertThat(event.getFirstFailureAt()).isEqualTo(first.plusSeconds(1));
        assertThat(event.getLastFailureAt()).isEqualTo(first.plusSeconds(1));
        assertThat(event.getLastError()).isNull();
    }

    @Test
    void transportPayloadContainsOnlySourceEvidenceAndNoBusinessData() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var signal = new StoreFailurePublisher.Signal("LOCAL_CONTROL", UUID.randomUUID(), 2, "REVIEWED", "WARNING",
                "CASH_SESSION_DISCREPANCY", now, now, 1);
        Map<String, Object> payload = StoreFailurePublisher.payload(UUID.randomUUID(), signal);
        assertThat(payload).containsOnlyKeys("schemaVersion", "installationId", "source", "sourceId", "sourceRevision",
                "status", "severity", "code", "firstSeenAt", "lastSeenAt", "occurrences");
        assertThat(payload).containsEntry("status", "REVIEWED").containsEntry("occurrences", 1L);
    }

    @Test
    void disabledWorkerDoesNotCollectAndOneStoreFailureDoesNotStopOtherStores() {
        StoreFailurePublisher publisher = mock(StoreFailurePublisher.class);
        MockEnvironment environment = new MockEnvironment();
        StoreFailureScheduler scheduler = new StoreFailureScheduler(publisher, environment);
        scheduler.tick(); verifyNoInteractions(publisher);
        environment.withProperty("tpv.sync.worker-enabled", "true");
        var first = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var next = new StoreFailurePublisher.Site(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(publisher.sites()).thenReturn(List.of(first, next));
        when(publisher.publish(first)).thenThrow(new IllegalStateException("do not log token"));
        scheduler.tick(); verify(publisher).publish(next);
    }
}
