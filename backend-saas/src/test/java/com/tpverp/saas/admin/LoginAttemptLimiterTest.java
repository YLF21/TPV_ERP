package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    @Test
    void blocksAfterFiveFailuresAndSuccessClearsFailures() {
        var limiter = limiter(new InMemorySecurityStateStore());

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
    void isolatesFailuresByRemoteAddressAndSharesThemAcrossNodes() {
        var state = new InMemorySecurityStateStore();
        var firstNode = limiter(state);
        var secondNode = limiter(state);

        for (int index = 0; index < LoginAttemptLimiter.MAX_FAILURES; index++) {
            firstNode.failure("login-account", "target", "203.0.113.10");
        }

        assertThat(secondNode.blocked("login-account", "target", "203.0.113.10")).isTrue();
        assertThat(secondNode.blocked("login-account", "target", "203.0.113.11")).isFalse();
        assertThat(secondNode.blocked("login-account", "other", "203.0.113.10")).isFalse();
    }

    private static LoginAttemptLimiter limiter(SecurityStateStore state) {
        return new LoginAttemptLimiter(
                Clock.fixed(Instant.parse("2026-07-30T12:00:00Z"), ZoneOffset.UTC), state);
    }
}
