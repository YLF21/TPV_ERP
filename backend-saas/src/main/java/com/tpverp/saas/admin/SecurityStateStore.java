package com.tpverp.saas.admin;

import java.time.Instant;
import java.util.Optional;

interface SecurityStateStore {

    void saveSession(String tokenHash, String realm, String username, Instant issuedAt, Instant expiresAt);

    Optional<SessionState> findSession(String tokenHash);

    Optional<SessionState> consumeSessionForRefresh(String tokenHash, Instant now);

    void deleteSession(String tokenHash);

    void deleteSessionsByUser(String realm, String username);

    void deleteExpiredSessions(Instant now);

    Optional<AttemptState> findAttempt(String scope, String usernameKey, String remoteAddress);

    AttemptState recordFailure(
            String scope,
            String usernameKey,
            String remoteAddress,
            Instant now,
            Instant expiredCutoff,
            int maxFailures,
            Instant blockedUntil);

    void deleteAttempt(String scope, String usernameKey, String remoteAddress);

    void deleteExpiredAttempts(Instant cutoff);

    record SessionState(String realm, String username, Instant expiresAt) {
    }

    record AttemptState(int failures, Instant lastFailureAt, Instant blockedUntil) {
    }
}
