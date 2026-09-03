package com.tpverp.backend.security.gestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.security.domain.OperationalSessionContext;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSession;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class GestionGroupAccessServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");

    @Mock GestionGroupUnlockStateRepository states;
    @Mock JdbcTemplate jdbc;
    @Mock PasswordEncoder passwords;
    @Mock AuditService audit;

    private UserAccount user;
    private UserSession session;
    private GestionGroupUnlockState state;
    private UsernamePasswordAuthenticationToken authentication;

    @BeforeEach
    void setUp() {
        var role = new Role(null, "ADMIN");
        user = new UserAccount(null, "ADMIN", "password-hash", role);
        session = new UserSession(user, null, "token-hash", NOW.minusSeconds(60));
        state = new GestionGroupUnlockState(UUID.randomUUID(), session, GestionGroup.FISCAL);
        authentication = new UsernamePasswordAuthenticationToken(user, "token");
        authentication.setDetails(new OperationalSessionContext(session.getId(), null, null));
    }

    @Test
    void unlocksForTheCurrentUsersOwnPasswordWithoutAdminBypass() {
        when(states.findLocked(session.getId(), GestionGroup.FISCAL)).thenReturn(Optional.of(state));
        when(passwords.matches("1234", "password-hash")).thenReturn(true);

        var result = service().unlock(GestionGroup.FISCAL, "1234", authentication);

        assertThat(result.group()).isEqualTo(GestionGroup.FISCAL);
        assertThat(result.unlockedAt()).isEqualTo(NOW);
        assertThat(state.isUnlocked(user.getAuthVersion(), user.getRol().getId())).isTrue();
        verify(states).save(state);
    }

    @Test
    void persistsARejectedAttemptWithoutIncludingTheCredentialInAuditData() {
        when(states.findLocked(session.getId(), GestionGroup.FISCAL)).thenReturn(Optional.of(state));
        when(passwords.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service().unlock(
                GestionGroup.FISCAL, "wrong-secret", authentication))
                .isInstanceOf(GestionGroupInvalidPasswordException.class);

        assertThat(state.getFailedAttempts()).isEqualTo(1);
        verify(states).save(state);
        @SuppressWarnings("unchecked")
        var details = ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(audit).record(
                org.mockito.ArgumentMatchers.eq("GESTION_GROUP_UNLOCK_REJECTED"),
                org.mockito.ArgumentMatchers.any(), details.capture());
        assertThat(details.getValue().toString()).doesNotContain("wrong-secret");
    }

    @Test
    void rejectsAProtectedCallFromAnotherStillLockedSession() {
        when(states.findState(session.getId(), GestionGroup.SEGURIDAD)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().requireUnlocked(
                GestionGroup.SEGURIDAD, authentication))
                .isInstanceOf(GestionGroupLockedException.class);
    }

    private GestionGroupAccessService service() {
        return new GestionGroupAccessService(
                states, jdbc, passwords, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
