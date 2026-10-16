package com.tpverp.saas.marketing;

import static org.assertj.core.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DemoRequestRateLimiterTest {
    @Test
    void limitsEmailAcrossAddressesAndAllowsItAgainAfterExpiry() {
        var clock = new MutableClock();
        var limiter = new DemoRequestRateLimiter(clock);
        for (int i = 0; i < 5; i++) limiter.accept("test-ip-" + i, "test@example.com");
        assertThatThrownBy(() -> limiter.accept("another-ip", "test@example.com"))
                .isInstanceOfSatisfying(DemoRequestFailure.class, failure -> {
                    assertThat(failure.code).isEqualTo("RATE_LIMITED");
                    assertThat(failure.retryAfter).isEqualTo(3600);
                });
        clock.now = clock.now.plusSeconds(3600);
        assertThatCode(() -> limiter.accept("another-ip", "test@example.com")).doesNotThrowAnyException();
    }

    @Test
    void limitsAddressAcrossEmailsAndBoundsMemoryForUnknownClients() {
        var limiter = new DemoRequestRateLimiter(new MutableClock(), 20);
        for (int i = 0; i < 20; i++) limiter.accept("shared-ip", "test" + i + "@example.com");
        assertThatThrownBy(() -> limiter.accept("shared-ip", "another@example.com"))
                .isInstanceOf(DemoRequestFailure.class);
        var bounded = new DemoRequestRateLimiter(new MutableClock());
        for (int i = 0; i < DemoRequestRateLimiter.MAX_BUCKETS / 2; i++) {
            bounded.accept("ip-" + i, "test" + i + "@example.com");
        }
        assertThatThrownBy(() -> bounded.accept("overflow", "overflow@example.com"))
                .isInstanceOf(DemoRequestFailure.class);
        assertThat(bounded.retainedBucketCount()).isEqualTo(DemoRequestRateLimiter.MAX_BUCKETS);
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-15T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
