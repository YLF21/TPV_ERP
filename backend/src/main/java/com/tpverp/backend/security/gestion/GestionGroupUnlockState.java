package com.tpverp.backend.security.gestion;

import com.tpverp.backend.security.domain.UserSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "gestion_group_unlock_state", uniqueConstraints =
        @UniqueConstraint(name = "uq_gestion_group_unlock_session_group", columnNames = {"session_id", "group_code"}))
public class GestionGroupUnlockState {

    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(30);
    private static final long[] COOLDOWNS_SECONDS = {0, 0, 5, 15, 30, 60, 120, 300, 600, 900};

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private UserSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "group_code", nullable = false, length = 32)
    private GestionGroup group;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "attempt_window_started_at")
    private Instant attemptWindowStartedAt;

    @Column(name = "blocked_until")
    private Instant blockedUntil;

    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    @Column(name = "user_auth_version")
    private Long userAuthVersion;

    @Column(name = "role_id")
    private UUID roleId;

    @Version
    private long version;

    protected GestionGroupUnlockState() {
    }

    public GestionGroupUnlockState(UUID id, UserSession session, GestionGroup group) {
        this.id = Objects.requireNonNull(id, "id");
        this.session = Objects.requireNonNull(session, "session");
        this.group = Objects.requireNonNull(group, "group");
    }

    public Instant registerFailure(Instant now) {
        Objects.requireNonNull(now, "now");
        if (attemptWindowStartedAt == null
                || !now.isBefore(attemptWindowStartedAt.plus(ATTEMPT_WINDOW))) {
            failedAttempts = 0;
            attemptWindowStartedAt = now;
        }
        failedAttempts++;
        long cooldown = COOLDOWNS_SECONDS[Math.min(failedAttempts, COOLDOWNS_SECONDS.length) - 1];
        blockedUntil = cooldown == 0 ? null : now.plusSeconds(cooldown);
        unlockedAt = null;
        userAuthVersion = null;
        roleId = null;
        return blockedUntil;
    }

    public void unlock(Instant now, long authVersion, UUID currentRoleId) {
        unlockedAt = Objects.requireNonNull(now, "now");
        userAuthVersion = authVersion;
        roleId = Objects.requireNonNull(currentRoleId, "currentRoleId");
        failedAttempts = 0;
        attemptWindowStartedAt = null;
        blockedUntil = null;
    }

    public boolean isUnlocked(long authVersion, UUID currentRoleId) {
        return unlockedAt != null
                && userAuthVersion != null
                && userAuthVersion == authVersion
                && Objects.equals(roleId, currentRoleId);
    }

    public GestionGroup getGroup() {
        return group;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public Instant getUnlockedAt() {
        return unlockedAt;
    }
}
