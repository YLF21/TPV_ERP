package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

class OperationalPermissionAuthorizationPolicyTest {

    private static final String PERMISSION = CorePermissionBootstrap.GESTION_VENTAS;

    private UserAccountRepository users;
    private PasswordEncoder passwordEncoder;
    private CurrentOrganization organization;
    private UserAccount operator;
    private OperationalPermissionAuthorizationService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        organization = mock(CurrentOrganization.class);
        operator = mock(UserAccount.class);
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(organization.currentUser(org.mockito.ArgumentMatchers.any()))
                .thenReturn(operator);
        service = new OperationalPermissionAuthorizationService(
                users,
                passwordEncoder,
                organization);
    }

    @Test
    void permissionOffPasswordOffRunsAsCurrentOperator() {
        var result = service.authorize(
                Set.of(PERMISSION),
                false,
                false,
                null,
                null,
                authentication());

        assertCurrentOperator(result);
        verifyNoInteractions(users, passwordEncoder);
    }

    @Test
    void permissionOffPasswordOnOnlyAcceptsCurrentOperatorPassword() {
        when(operator.getPasswordHash()).thenReturn("operator-hash");
        when(passwordEncoder.matches("1234", "operator-hash")).thenReturn(true);

        var result = service.authorize(
                Set.of(PERMISSION),
                false,
                true,
                "OTHER_USER",
                "1234",
                authentication());

        assertCurrentOperator(result);
        verifyNoInteractions(users);
    }

    @Test
    void permissionOffPasswordOnRejectsMissingCurrentPassword() {
        assertThatThrownBy(() -> service.authorize(
                Set.of(PERMISSION),
                false,
                true,
                "OTHER_USER",
                null,
                authentication()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("La contrasena de autorizacion es obligatoria");
    }

    @Test
    void permissionOnPasswordOffRunsDirectlyWhenOperatorHasPermission() {
        var result = service.authorize(
                Set.of(PERMISSION),
                true,
                false,
                null,
                null,
                authentication(PERMISSION));

        assertCurrentOperator(result);
        verifyNoInteractions(users, passwordEncoder);
    }

    @Test
    void permissionOnPasswordOffDelegatesWhenOperatorLacksPermission() {
        var authorizer = delegatedAuthorizer("SUPERVISOR", "1234");

        var result = service.authorize(
                Set.of(PERMISSION),
                true,
                false,
                "supervisor",
                "1234",
                authentication());

        assertThat(result.operator()).isSameAs(operator);
        assertThat(result.authorizer()).isSameAs(authorizer);
        assertThat(result.delegated()).isTrue();
    }

    @Test
    void permissionOnPasswordOnReauthenticatesPermittedOperator() {
        when(operator.getPasswordHash()).thenReturn("operator-hash");
        when(passwordEncoder.matches("1234", "operator-hash")).thenReturn(true);

        var result = service.authorize(
                Set.of(PERMISSION),
                true,
                true,
                null,
                "1234",
                authentication(PERMISSION));

        assertCurrentOperator(result);
        verifyNoInteractions(users);
    }

    @Test
    void permissionOnPasswordOnDelegatesWhenOperatorLacksPermission() {
        var authorizer = delegatedAuthorizer("SUPERVISOR", "1234");

        var result = service.authorize(
                Set.of(PERMISSION),
                true,
                true,
                "supervisor",
                "1234",
                authentication());

        assertThat(result.authorizer()).isSameAs(authorizer);
        assertThat(result.delegated()).isTrue();
    }

    private UserAccount delegatedAuthorizer(String username, String password) {
        var company = mock(Company.class);
        var store = mock(Store.class);
        var authorizer = mock(UserAccount.class);
        when(company.getId()).thenReturn(UUID.randomUUID());
        when(store.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.isActivo()).thenReturn(true);
        when(authorizer.isProtegido()).thenReturn(true);
        when(authorizer.getPasswordHash()).thenReturn("authorizer-hash");
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(users.findByEmpresaIdAndNombre(company.getId(), username))
                .thenReturn(Optional.of(authorizer));
        when(passwordEncoder.matches(password, "authorizer-hash")).thenReturn(true);
        return authorizer;
    }

    private UsernamePasswordAuthenticationToken authentication(String... permissions) {
        var authorities = java.util.Arrays.stream(permissions)
                .map(SimpleGrantedAuthority::new)
                .toList();
        return new UsernamePasswordAuthenticationToken(operator, "token", authorities);
    }

    private void assertCurrentOperator(
            OperationalPermissionAuthorizationService.Authorization result) {
        assertThat(result.operator()).isSameAs(operator);
        assertThat(result.authorizer()).isSameAs(operator);
        assertThat(result.delegated()).isFalse();
    }
}
