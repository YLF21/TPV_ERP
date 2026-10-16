package com.tpverp.saas.marketing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Per-process submission guard. Raw IP addresses/emails are neither retained nor logged.
 * Use an edge limiter as well in multi-instance deployments. Forwarded headers are not trusted.
 */
final class DemoRequestRateLimiter {
    static final int MAX_BUCKETS = 10_000;
    static final int DEFAULT_IP_LIMIT = 120;
    private static final int EMAIL_LIMIT = 5;
    private static final Duration IP_WINDOW = Duration.ofMinutes(10);
    private static final Duration EMAIL_WINDOW = Duration.ofHours(1);
    private final Clock clock;
    private final int ipLimit;
    private final String salt = UUID.randomUUID().toString();
    private final Map<String, Bucket> buckets = new HashMap<>();
    private Instant nextCleanup = Instant.MIN;

    DemoRequestRateLimiter(Clock clock) {
        this(clock, DEFAULT_IP_LIMIT);
    }

    DemoRequestRateLimiter(Clock clock, int ipLimit) {
        if (ipLimit < 1 || ipLimit > MAX_BUCKETS) throw new IllegalArgumentException("Invalid marketing IP request limit");
        this.clock = clock;
        this.ipLimit = ipLimit;
    }

    synchronized void accept(String remoteAddress, String normalizedEmail) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanup)) {
            buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt));
            nextCleanup = now.plusSeconds(60);
        }
        String ip = key("ip", remoteAddress == null ? "unknown" : remoteAddress);
        String email = key("email", normalizedEmail);
        Bucket ipBucket = current(ip, now);
        Bucket emailBucket = current(email, now);
        if (ipBucket != null && ipBucket.count >= ipLimit) reject(ipBucket.expiresAt, now);
        if (emailBucket != null && emailBucket.count >= EMAIL_LIMIT) reject(emailBucket.expiresAt, now);
        int needed = (ipBucket == null ? 1 : 0) + (emailBucket == null ? 1 : 0);
        if (buckets.size() + needed > MAX_BUCKETS) reject(now.plusSeconds(60), now);
        consume(ip, ipBucket, now.plus(IP_WINDOW));
        consume(email, emailBucket, now.plus(EMAIL_WINDOW));
    }

    private Bucket current(String key, Instant now) {
        Bucket bucket = buckets.get(key);
        if (bucket != null && !now.isBefore(bucket.expiresAt)) {
            buckets.remove(key);
            return null;
        }
        return bucket;
    }

    private void consume(String key, Bucket existing, Instant expiresAt) {
        buckets.put(key, existing == null ? new Bucket(1, expiresAt) : new Bucket(existing.count + 1, existing.expiresAt));
    }

    private static void reject(Instant until, Instant now) {
        throw new DemoRequestFailure(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Demasiadas solicitudes. Intentalo de nuevo mas tarde", Map.of(),
                Math.max(1, Duration.between(now, until).toSeconds()));
    }

    private String key(String category, String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + category + ":" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    int retainedBucketCount() { return buckets.size(); }

    private record Bucket(int count, Instant expiresAt) { }
}
