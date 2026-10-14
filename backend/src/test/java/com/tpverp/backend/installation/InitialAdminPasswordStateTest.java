package com.tpverp.backend.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

class InitialAdminPasswordStateTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final InitialAdminPasswordState state = new InitialAdminPasswordState(users, passwords);

    @Test
    void recognizesOnlyTheUntouchedInitialAdmin() {
        var admin = new UserAccount(null, "ADMIN", "initial-hash", new Role(null, "ADMIN"));
        admin.requirePasswordChange();
        when(users.findGlobalByUserName("ADMIN")).thenReturn(Optional.of(admin));
        when(passwords.matches("0000", "initial-hash")).thenReturn(true);
        assertThat(state.isRequired()).isTrue();
        admin.cambiarPassword("changed-hash");
        assertThat(state.isRequired()).isFalse();
        verify(passwords, never()).matches("0000", "changed-hash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "inactive", "unprotected", "not-temporary", "changed-password", "unprotected-role"})
    void rejectsAccountsOutsideInitialState(String reason) {
        var admin = mock(UserAccount.class);
        var role = mock(Role.class);
        when(users.findGlobalByUserName("ADMIN")).thenReturn("absent".equals(reason) ? Optional.empty() : Optional.of(admin));
        when(admin.isProtegido()).thenReturn(!"unprotected".equals(reason));
        when(admin.isActivo()).thenReturn(!"inactive".equals(reason));
        when(admin.mustChangePassword()).thenReturn(!"not-temporary".equals(reason));
        when(admin.getRol()).thenReturn(role);
        when(role.isProtegido()).thenReturn(!"unprotected-role".equals(reason));
        when(admin.getPasswordHash()).thenReturn("hash");
        when(passwords.matches("0000", "hash")).thenReturn(!"changed-password".equals(reason));
        assertThat(state.isRequired()).isFalse();
    }
}
