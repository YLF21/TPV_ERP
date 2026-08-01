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
import com.tpverp.backend.security.domain.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;

class OperationalPermissionAuthorizationServiceTest {

    private UserAccountRepository users;
    private PasswordEncoder passwordEncoder;
    private CurrentOrganization organization;
    private OperationalPermissionAuthorizationService service;

    @BeforeEach
    void setUp() {
        users = mock(UserAccountRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        organization = mock(CurrentOrganization.class);
        service = new OperationalPermissionAuthorizationService(users, passwordEncoder, organization);
    }

    @Test
    void usesCurrentUserWithoutRequestingCredentialsWhenPermissionIsPresent() {
        var operator = mock(UserAccount.class);
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(operator);
        var authentication = new UsernamePasswordAuthenticationToken(
                operator,
                "token",
                List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.ABRIR_CAJON)));

        var result = service.authorize(
                CorePermissionBootstrap.ABRIR_CAJON, null, null, authentication);

        assertThat(result.operator()).isSameAs(operator);
        assertThat(result.authorizer()).isSameAs(operator);
        assertThat(result.delegated()).isFalse();
        verifyNoInteractions(users, passwordEncoder);
    }

    @Test
    void acceptsAnActiveProtectedAdministratorAsDelegatedAuthorizer() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B00000000", "Company", address);
        var store = new Store(company, "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var operator = mock(UserAccount.class);
        var admin = mock(UserAccount.class);
        var operatorId = UUID.randomUUID();
        when(operator.getId()).thenReturn(operatorId);
        when(admin.getId()).thenReturn(UUID.randomUUID());
        when(admin.isProtegido()).thenReturn(true);
        when(admin.isActivo()).thenReturn(true);
        when(admin.getPasswordHash()).thenReturn("encoded");
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(operator);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(users.findByEmpresaIdAndNombre(company.getId(), "ADMIN")).thenReturn(Optional.empty());
        when(users.findByNombreAndTiendaIsNull("ADMIN")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("1234", "encoded")).thenReturn(true);
        var authentication = new UsernamePasswordAuthenticationToken(
                operator,
                "token",
                List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.VENTA)));

        var result = service.authorize(
                CorePermissionBootstrap.ABRIR_CAJON, "admin", "1234", authentication);

        assertThat(result.authorizer()).isSameAs(admin);
        assertThat(result.delegated()).isTrue();
    }

    @Test
    void rejectsValidCredentialsWhenAuthorizerDoesNotHaveRequestedPermission() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas", "codigoPostal", "35001",
                "provincia", "Las Palmas", "pais", "ES");
        var company = new Company("B00000000", "Company", address);
        var store = new Store(company, "Store", address, "hash", "Atlantic/Canary", "EUR", "es-ES");
        var operator = mock(UserAccount.class);
        var authorizer = mock(UserAccount.class);
        var role = mock(Role.class);
        when(operator.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.getId()).thenReturn(UUID.randomUUID());
        when(authorizer.isActivo()).thenReturn(true);
        when(authorizer.getPasswordHash()).thenReturn("encoded");
        when(authorizer.getRol()).thenReturn(role);
        when(role.getPermisos()).thenReturn(Set.of());
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(operator);
        when(organization.currentCompany()).thenReturn(company);
        when(organization.currentStore()).thenReturn(store);
        when(users.findByEmpresaIdAndNombre(company.getId(), "SUPERVISOR"))
                .thenReturn(Optional.of(authorizer));
        when(users.hasStoreAccess(authorizer.getId(), store.getId())).thenReturn(true);
        when(passwordEncoder.matches("1234", "encoded")).thenReturn(true);
        var authentication = new UsernamePasswordAuthenticationToken(
                operator,
                "token",
                List.of(new SimpleGrantedAuthority(CorePermissionBootstrap.VENTA)));

        assertThatThrownBy(() -> service.authorize(
                CorePermissionBootstrap.ABRIR_CAJON, "supervisor", "1234", authentication))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining(CorePermissionBootstrap.ABRIR_CAJON);
    }

    @Test
    void sensitiveAuthorizationReauthenticatesCurrentPrivilegedUser() {
        var operator = mock(UserAccount.class);
        when(operator.getPasswordHash()).thenReturn("encoded");
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(operator);
        when(passwordEncoder.matches("1234", "encoded")).thenReturn(true);
        var authentication = new UsernamePasswordAuthenticationToken(
                operator,
                "token",
                List.of(new SimpleGrantedAuthority(
                        CorePermissionBootstrap.GESTION_VENTAS)));

        var result = service.authorizeWithPassword(
                Set.of(
                        CorePermissionBootstrap.GESTION_VENTAS,
                        CorePermissionBootstrap.GESTION_CUENTAS),
                null,
                "1234",
                authentication);

        assertThat(result.authorizer()).isSameAs(operator);
        assertThat(result.delegated()).isFalse();
    }

    @Test
    void sensitiveAuthorizationRejectsWrongCurrentUserPassword() {
        var operator = mock(UserAccount.class);
        when(operator.getPasswordHash()).thenReturn("encoded");
        when(organization.currentUser(org.mockito.ArgumentMatchers.any())).thenReturn(operator);
        when(passwordEncoder.matches("bad", "encoded")).thenReturn(false);
        var authentication = new UsernamePasswordAuthenticationToken(
                operator,
                "token",
                List.of(new SimpleGrantedAuthority(
                        CorePermissionBootstrap.GESTION_CUENTAS)));

        assertThatThrownBy(() -> service.authorizeWithPassword(
                Set.of(
                        CorePermissionBootstrap.GESTION_VENTAS,
                        CorePermissionBootstrap.GESTION_CUENTAS),
                null,
                "bad",
                authentication))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Contraseña incorrecta");
    }
}
