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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SaasSessionTokenStore {

    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final SecurityStateStore state;
    private final Duration sessionLifetime;

    public SaasSessionTokenStore(
            Clock clock,
            SecurityStateStore state,
            @Value("${tpv.saas.sessions.lifetime:PT8H}") Duration sessionLifetime) {
        this.clock = clock;
        this.state = state;
        if (sessionLifetime == null || sessionLifetime.isZero() || sessionLifetime.isNegative()) {
            throw new IllegalArgumentException("La duracion de sesion debe ser positiva");
        }
        this.sessionLifetime = sessionLifetime;
    }

    public IssuedSession issue(String realm, String username) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        Instant issuedAt = clock.instant();
        Instant expiresAt = issuedAt.plus(sessionLifetime);
        state.deleteExpiredSessions(issuedAt);
        state.saveSession(hash(token), realm, username, issuedAt, expiresAt);
        return new IssuedSession(token, expiresAt);
    }

    public Optional<String> username(String token, String realm) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = hash(token);
        SecurityStateStore.SessionState session = state.findSession(key).orElse(null);
        if (session == null || !session.realm().equals(realm)) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            state.deleteSession(key);
            return Optional.empty();
        }
        return Optional.of(session.username());
    }

    public Optional<SessionIdentity> session(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String key = hash(token);
        SecurityStateStore.SessionState session = state.findSession(key).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            state.deleteSession(key);
            return Optional.empty();
        }
        return Optional.of(new SessionIdentity(session.realm(), session.username(), session.expiresAt()));
    }

    public Optional<RefreshedSession> refresh(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return state.consumeSessionForRefresh(hash(token), now)
                .map(session -> new RefreshedSession(
                        session.realm(), session.username(), issue(session.realm(), session.username())));
    }

    public boolean revokeAllForToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SecurityStateStore.SessionState session = state.findSession(hash(token)).orElse(null);
        if (session == null || !session.expiresAt().isAfter(clock.instant())) {
            revoke(token);
            return false;
        }
        state.deleteSessionsByUser(session.realm(), session.username());
        return true;
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            state.deleteSession(hash(token));
        }
    }

    public void revokeByUser(String realm, String username) {
        if (realm == null || realm.isBlank() || username == null || username.isBlank()) {
            return;
        }
        state.deleteSessionsByUser(realm, username);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo proteger el token de sesion", exception);
        }
    }

    public record IssuedSession(String token, Instant expiresAt) {
    }

    public record RefreshedSession(String realm, String username, IssuedSession issued) {
    }

    public record SessionIdentity(String realm, String username, Instant expiresAt) {
    }
}
