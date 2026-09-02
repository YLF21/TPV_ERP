package com.tpverp.saas.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcSecurityStateStore implements SecurityStateStore {

    private final JdbcTemplate jdbc;

    JdbcSecurityStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveSession(String tokenHash, String realm, String username, Instant issuedAt, Instant expiresAt) {
        jdbc.update("""
                insert into saas_session(token_hash, realm, username, issued_at, expires_at)
                values (?, ?, ?, ?, ?)
                """, tokenHash, realm, username, timestamp(issuedAt), timestamp(expiresAt));
    }

    @Override
    public Optional<SessionState> findSession(String tokenHash) {
        return jdbc.query("""
                select realm, username, expires_at
                from saas_session
                where token_hash = ?
                """, (rs, row) -> new SessionState(
                rs.getString("realm"),
                rs.getString("username"),
                rs.getTimestamp("expires_at").toInstant()), tokenHash).stream().findFirst();
    }

    @Override
    public Optional<SessionState> consumeSessionForRefresh(String tokenHash, Instant now) {
        return jdbc.query("""
                delete from saas_session
                where token_hash = ? and expires_at > ?
                returning realm, username, expires_at
                """, (rs, row) -> new SessionState(
                rs.getString("realm"),
                rs.getString("username"),
                rs.getTimestamp("expires_at").toInstant()), tokenHash, timestamp(now)).stream().findFirst();
    }

    @Override
    public void deleteSession(String tokenHash) {
        jdbc.update("delete from saas_session where token_hash = ?", tokenHash);
    }

    @Override
    public void deleteSessionsByUser(String realm, String username) {
        jdbc.update("delete from saas_session where realm = ? and lower(username) = lower(?)", realm, username);
    }

    @Override
    public void deleteExpiredSessions(Instant now) {
        jdbc.update("delete from saas_session where expires_at <= ?", timestamp(now));
    }

    @Override
    public Optional<AttemptState> findAttempt(String scope, String usernameKey, String remoteAddress) {
        return jdbc.query("""
                select failures, last_failure_at, blocked_until
                from saas_login_attempt
                where scope = ? and username_key = ? and remote_address = ?
                """, (rs, row) -> new AttemptState(
                rs.getInt("failures"),
                rs.getTimestamp("last_failure_at").toInstant(),
                rs.getTimestamp("blocked_until") == null
                        ? null : rs.getTimestamp("blocked_until").toInstant()),
                scope, usernameKey, remoteAddress).stream().findFirst();
    }

    @Override
    public AttemptState recordFailure(
            String scope,
            String usernameKey,
            String remoteAddress,
            Instant now,
            Instant expiredCutoff,
            int maxFailures,
            Instant blockedUntil) {
        return jdbc.queryForObject("""
                insert into saas_login_attempt(
                    scope, username_key, remote_address, failures, last_failure_at, blocked_until)
                values (?, ?, ?, 1, ?, null)
                on conflict (scope, username_key, remote_address) do update set
                    failures = case
                        when saas_login_attempt.last_failure_at <= ? then 1
                        else saas_login_attempt.failures + 1
                    end,
                    last_failure_at = excluded.last_failure_at,
                    blocked_until = case
                        when (case
                            when saas_login_attempt.last_failure_at <= ? then 1
                            else saas_login_attempt.failures + 1
                        end) >= ? then ?
                        else null
                    end
                returning failures, last_failure_at, blocked_until
                """, (rs, row) -> new AttemptState(
                rs.getInt("failures"),
                rs.getTimestamp("last_failure_at").toInstant(),
                rs.getTimestamp("blocked_until") == null
                        ? null : rs.getTimestamp("blocked_until").toInstant()),
                scope, usernameKey, remoteAddress, timestamp(now),
                timestamp(expiredCutoff), timestamp(expiredCutoff), maxFailures, timestamp(blockedUntil));
    }

    @Override
    public void deleteAttempt(String scope, String usernameKey, String remoteAddress) {
        jdbc.update("""
                delete from saas_login_attempt
                where scope = ? and username_key = ? and remote_address = ?
                """, scope, usernameKey, remoteAddress);
    }

    @Override
    public void deleteExpiredAttempts(Instant cutoff) {
        jdbc.update("""
                delete from saas_login_attempt
                where last_failure_at <= ?
                  and (blocked_until is null or blocked_until <= ?)
                """, timestamp(cutoff), timestamp(cutoff));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
