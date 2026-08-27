package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    @Test
    void blocksAfterFiveFailuresAndSuccessClearsFailures() {
        var limiter = new LoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC));

        for (int index = 0; index < LoginAttemptLimiter.MAX_FAILURES - 1; index++) {
            limiter.failure("admin", "User", "127.0.0.1");
            assertThat(limiter.blocked("admin", "user", "127.0.0.1")).isFalse();
        }
        limiter.success("admin", "user", "127.0.0.1");
        assertThat(limiter.blocked("admin", "user", "127.0.0.1")).isFalse();

        for (int index = 0; index < LoginAttemptLimiter.MAX_FAILURES; index++) {
            limiter.failure("admin", "user", "127.0.0.1");
        }
        assertThat(limiter.blocked("admin", "USER", "127.0.0.1")).isTrue();
        assertThat(limiter.blocked("tenant", "user", "127.0.0.1")).isFalse();
    }

    @Test
    void doesNotEvictActivePartialFailuresWhenTheMapReachesCleanupThreshold() {
        var limiter = new LoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC));

        for (int index = 0; index < LoginAttemptLimiter.MAX_FAILURES - 1; index++) {
            limiter.failure("login-account", "target", "");
        }
        for (int index = 0; index < 9_999; index++) {
            limiter.failure("login-account", "random-" + index, "");
        }

        limiter.failure("login-account", "target", "");
        assertThat(limiter.blocked("login-account", "target", "")).isTrue();
    }
}
