package com.tpverp.backend.pdawork;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PdaOperationalEventRetentionJobTest {
    @Test
    void deletesOnlyEventsOlderThanConfiguredRetentionInBoundedBatch() {
        var events = mock(PdaOperationalEventRepository.class);
        var now = Instant.parse("2026-10-29T10:00:00Z");
        var cutoff = Instant.parse("2026-07-31T10:00:00Z");
        when(events.deleteRetentionBatch(cutoff, 10000)).thenReturn(42);
        var job = new PdaOperationalEventRetentionJob(events, Clock.fixed(now, ZoneOffset.UTC), 90, 10000);

        assertThat(job.purgeExpired()).isEqualTo(42);
        verify(events).deleteRetentionBatch(cutoff, 10000);
    }
}
