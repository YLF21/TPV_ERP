package com.tpverp.backend.security.gestion;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.domain.UserAccount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GestionGroupAccessService {

    private final GestionGroupUnlockStateRepository states;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public GestionGroupAccessService(
            GestionGroupUnlockStateRepository states,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            Clock clock) {
        this.states = states;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = {
            GestionGroupInvalidPasswordException.class,
            GestionGroupUnlockThrottledException.class
    })
    public UnlockResult unlock(GestionGroup group, String password, Authentication authentication) {
        var context = context(authentication, group);
        var user = user(authentication, group);
        ensureState(context.sessionId(), group);
        var state = states.findLocked(context.sessionId(), group)
                .orElseThrow(() -> new IllegalStateException("No se pudo crear el estado de desbloqueo"));
        var now = clock.instant();
        if (state.getBlockedUntil() != null && now.isBefore(state.getBlockedUntil())) {
            var retryAfter = Math.max(1, Duration.between(now, state.getBlockedUntil()).toSeconds());
            audit.record("GESTION_GROUP_UNLOCK_THROTTLED", AuditResult.FALLO,
                    details(context.sessionId(), group, state.getFailedAttempts(), state.getBlockedUntil()));
            throw new GestionGroupUnlockThrottledException(state.getBlockedUntil(), retryAfter);
        }
        if (!passwordEncoder.matches(password == null ? "" : password, user.getPasswordHash())) {
            var blockedUntil = state.registerFailure(now);
            states.save(state);
            audit.record("GESTION_GROUP_UNLOCK_REJECTED", AuditResult.FALLO,
                    details(context.sessionId(), group, state.getFailedAttempts(), blockedUntil));
            throw new GestionGroupInvalidPasswordException();
        }
        state.unlock(now, user.getAuthVersion(), user.getRol().getId());
        states.save(state);
        audit.record("GESTION_GROUP_UNLOCKED", AuditResult.EXITO,
                details(context.sessionId(), group, 0, null));
        return new UnlockResult(group, now);
    }

    @Transactional(readOnly = true)
    public void requireUnlocked(GestionGroup group, Authentication authentication) {
        var context = context(authentication, group);
        var user = user(authentication, group);
        boolean unlocked = states.findState(context.sessionId(), group)
                .filter(state -> state.isUnlocked(user.getAuthVersion(), user.getRol().getId()))
                .isPresent();
        if (!unlocked) {
            throw new GestionGroupLockedException(group);
        }
    }

    private void ensureState(UUID sessionId, GestionGroup group) {
        jdbc.update("""
                insert into gestion_group_unlock_state (id, session_id, group_code, failed_attempts, version)
                values (?, ?, ?, 0, 0)
                on conflict (session_id, group_code) do nothing
                """, UUID.randomUUID(), sessionId, group.name());
    }

    private static OperationalSessionContext context(
            Authentication authentication, GestionGroup group) {
        if (authentication == null
                || !(authentication.getDetails() instanceof OperationalSessionContext context)
                || context.sessionId() == null) {
            throw new GestionGroupLockedException(group);
        }
        return context;
    }

    private static UserAccount user(Authentication authentication, GestionGroup group) {
        if (authentication != null && authentication.getPrincipal() instanceof UserAccount user) {
            return user;
        }
        throw new GestionGroupLockedException(group);
    }

    private static Map<String, Object> details(
            UUID sessionId,
            GestionGroup group,
            int failedAttempts,
            Instant blockedUntil) {
        var values = new LinkedHashMap<String, Object>();
        values.put("sessionId", sessionId.toString());
        values.put("group", group.name());
        values.put("failedAttempts", failedAttempts);
        if (blockedUntil != null) {
            values.put("blockedUntil", blockedUntil.toString());
        }
        return Map.copyOf(values);
    }

    public record UnlockResult(GestionGroup group, Instant unlockedAt) {
    }
}
