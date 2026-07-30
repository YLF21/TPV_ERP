package com.tpverp.saas.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    public static final Duration BLOCK_DURATION = Duration.ofMinutes(10);

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean blocked(String realm, String username, String remoteAddress) {
        String key = key(realm, username, remoteAddress);
        Attempt attempt = attempts.get(key);
        if (attempt == null) {
            return false;
        }
        if (attempt.blockedUntil() != null && attempt.blockedUntil().isAfter(clock.instant())) {
            return true;
        }
        if (attempt.blockedUntil() != null) {
            attempts.remove(key, attempt);
        }
        return false;
    }

    public void failure(String realm, String username, String remoteAddress) {
        String key = key(realm, username, remoteAddress);
        Instant now = clock.instant();
        attempts.compute(key, (ignored, current) -> {
            int failures = current == null ? 1 : current.failures() + 1;
            Instant blockedUntil = failures >= MAX_FAILURES ? now.plus(BLOCK_DURATION) : null;
            return new Attempt(failures, blockedUntil);
        });
        evictExpired(now);
    }

    public void success(String realm, String username, String remoteAddress) {
        attempts.remove(key(realm, username, remoteAddress));
    }

    private void evictExpired(Instant now) {
        if (attempts.size() < 10_000) {
            return;
        }
        attempts.entrySet().removeIf(entry ->
                entry.getValue().blockedUntil() == null || !entry.getValue().blockedUntil().isAfter(now));
    }

    private static String key(String realm, String username, String remoteAddress) {
        return realm + '\n'
                + (username == null ? "" : username.toLowerCase(java.util.Locale.ROOT))
                + '\n' + (remoteAddress == null ? "" : remoteAddress);
    }

    private record Attempt(int failures, Instant blockedUntil) {
    }
}
