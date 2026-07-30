package com.tpverp.saas.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class SaasSessionTokenStore {

    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public SaasSessionTokenStore(Clock clock) {
        this.clock = clock;
    }

    public IssuedSession issue(String realm, String username) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        Instant expiresAt = clock.instant().plus(SESSION_LIFETIME);
        sessions.put(hash(token), new Session(realm, username, expiresAt));
        return new IssuedSession(token, expiresAt);
    }

    public Optional<String> username(String token, String realm) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = hash(token);
        Session session = sessions.get(key);
        if (session == null || !session.realm().equals(realm)) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(key, session);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.remove(hash(token));
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo proteger el token de sesion", exception);
        }
    }

    private record Session(String realm, String username, Instant expiresAt) {
    }

    public record IssuedSession(String token, Instant expiresAt) {
    }
}
