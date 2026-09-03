package com.tpverp.backend.security.gestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserSession;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GestionGroupUnlockStateTest {

    @Test
    void appliesTheApprovedProgressiveCooldownSequence() {
        var state = state();
        var now = Instant.parse("2026-09-02T10:00:00Z");
        long[] expected = {0, 0, 5, 15, 30, 60, 120, 300, 600, 900, 900};

        for (int index = 0; index < expected.length; index++) {
            var blockedUntil = state.registerFailure(now);
            assertThat(blockedUntil).isEqualTo(expected[index] == 0
                    ? null : now.plusSeconds(expected[index]));
        }
    }

    @Test
    void resetsFailuresAfterThirtyMinutes() {
        var state = state();
        var start = Instant.parse("2026-09-02T10:00:00Z");
        state.registerFailure(start);
        state.registerFailure(start.plusSeconds(1));
        state.registerFailure(start.plusSeconds(2));

        assertThat(state.registerFailure(start.plusSeconds(1800))).isNull();
        assertThat(state.getFailedAttempts()).isEqualTo(1);
    }

    @Test
    void unlockIsBoundToCredentialVersionAndRole() {
        var state = state();
        var roleId = UUID.randomUUID();
        state.unlock(Instant.parse("2026-09-02T10:00:00Z"), 4, roleId);

        assertThat(state.isUnlocked(4, roleId)).isTrue();
        assertThat(state.isUnlocked(5, roleId)).isFalse();
        assertThat(state.isUnlocked(4, UUID.randomUUID())).isFalse();
    }

    private static GestionGroupUnlockState state() {
        var role = new Role(null, "ADMIN");
        var user = new UserAccount(null, "ADMIN", "hash", role);
        var session = new UserSession(user, null, "token-hash", Instant.EPOCH);
        return new GestionGroupUnlockState(UUID.randomUUID(), session, GestionGroup.FISCAL);
    }
}
