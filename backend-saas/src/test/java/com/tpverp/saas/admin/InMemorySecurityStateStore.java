package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class InMemorySecurityStateStore implements SecurityStateStore {

    private final Map<String, SessionState> sessions = new HashMap<>();
    private final Map<String, AttemptState> attempts = new HashMap<>();

    @Override
    public synchronized void saveSession(
            String tokenHash, String realm, String username, Instant issuedAt, Instant expiresAt) {
        sessions.put(tokenHash, new SessionState(realm, username, expiresAt));
    }

    @Override
    public synchronized Optional<SessionState> findSession(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public synchronized Optional<SessionState> consumeSessionForRefresh(String tokenHash, Instant now) {
        SessionState session = sessions.get(tokenHash);
        if (session == null || !session.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        sessions.remove(tokenHash);
        return Optional.of(session);
    }

    @Override
    public synchronized void deleteSession(String tokenHash) {
        sessions.remove(tokenHash);
    }

    @Override
    public synchronized void deleteSessionsByUser(String realm, String username) {
        sessions.entrySet().removeIf(entry -> entry.getValue().realm().equals(realm)
                && entry.getValue().username().equalsIgnoreCase(username));
    }

    @Override
    public synchronized void deleteExpiredSessions(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    @Override
    public synchronized Optional<AttemptState> findAttempt(
            String scope, String usernameKey, String remoteAddress) {
        return Optional.ofNullable(attempts.get(key(scope, usernameKey, remoteAddress)));
    }

    @Override
    public synchronized AttemptState recordFailure(
            String scope,
            String usernameKey,
            String remoteAddress,
            Instant now,
            Instant expiredCutoff,
            int maxFailures,
            Instant blockedUntil) {
        String key = key(scope, usernameKey, remoteAddress);
        AttemptState current = attempts.get(key);
        int failures = current == null || !current.lastFailureAt().isAfter(expiredCutoff)
                ? 1 : current.failures() + 1;
        AttemptState updated = new AttemptState(
                failures, now, failures >= maxFailures ? blockedUntil : null);
        attempts.put(key, updated);
        return updated;
    }

    @Override
    public synchronized void deleteAttempt(String scope, String usernameKey, String remoteAddress) {
        attempts.remove(key(scope, usernameKey, remoteAddress));
    }

    @Override
    public synchronized void deleteExpiredAttempts(Instant cutoff) {
        attempts.entrySet().removeIf(entry -> !entry.getValue().lastFailureAt().isAfter(cutoff)
                && (entry.getValue().blockedUntil() == null
                    || !entry.getValue().blockedUntil().isAfter(cutoff)));
    }

    private static String key(String scope, String username, String remoteAddress) {
        return scope + '\n' + username + '\n' + remoteAddress;
    }
}
