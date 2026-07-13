package com.tpverp.backend.terminal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.AuthenticationFailedException;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class PaymentTerminalReauthenticationServiceTest {

    private final CurrentOrganization organization = mock(CurrentOrganization.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final UsernamePasswordAuthenticationToken authentication =
            UsernamePasswordAuthenticationToken.authenticated("cashier", "token", java.util.List.of());

    @Test
    void requiresTheAuthenticatedUsersRealPasswordByDefault() {
        var user = new UserAccount(null, "ADMIN", encoder.encode("1234"), new Role(null, "ADMIN"));
        when(organization.currentUser(authentication)).thenReturn(user);
        var service = new PaymentTerminalReauthenticationService(organization, encoder, true);

        assertThatCode(() -> service.require(authentication, "1234")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.require(authentication, "9999"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    void explicitOptOutIsTheOnlyWayToDisableReauthentication() {
        var service = new PaymentTerminalReauthenticationService(organization, encoder, false);

        assertThatCode(() -> service.require(authentication, null)).doesNotThrowAnyException();
    }
}
