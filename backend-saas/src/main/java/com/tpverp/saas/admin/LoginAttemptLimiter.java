package com.tpverp.saas.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    public static final Duration BLOCK_DURATION = Duration.ofMinutes(10);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    private final Clock clock;
    private final SecurityStateStore state;

    public LoginAttemptLimiter(Clock clock, SecurityStateStore state) {
        this.clock = clock;
        this.state = state;
    }

    public boolean blocked(String scope, String username, String remoteAddress) {
        SecurityStateStore.AttemptState attempt = state.findAttempt(
                normalizeScope(scope), normalizeUsername(username), normalizeRemoteAddress(remoteAddress))
                .orElse(null);
        if (attempt == null) {
            return false;
        }
        Instant now = clock.instant();
        if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(now)) {
            return true;
        }
        if (expired(attempt, now)) {
            success(scope, username, remoteAddress);
        }
        return false;
    }

    public void failure(String scope, String username, String remoteAddress) {
        Instant now = clock.instant();
        state.recordFailure(
                normalizeScope(scope),
                normalizeUsername(username),
                normalizeRemoteAddress(remoteAddress),
                now,
                now.minus(FAILURE_WINDOW),
                MAX_FAILURES,
                now.plus(BLOCK_DURATION));
        state.deleteExpiredAttempts(now.minus(FAILURE_WINDOW));
    }

    public void success(String scope, String username, String remoteAddress) {
        state.deleteAttempt(
                normalizeScope(scope), normalizeUsername(username), normalizeRemoteAddress(remoteAddress));
    }

    private static boolean expired(SecurityStateStore.AttemptState attempt, Instant now) {
        if (attempt.blockedUntil() != null) {
            return !attempt.blockedUntil().isAfter(now);
        }
        return !attempt.lastFailureAt().plus(FAILURE_WINDOW).isAfter(now);
    }

    private static String normalizeScope(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeUsername(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeRemoteAddress(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String normalized = value.trim();
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}
