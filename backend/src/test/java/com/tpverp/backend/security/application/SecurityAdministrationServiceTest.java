package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.BackupSettingsRepository;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.installation.InstallationRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.PermissionRepository;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.time.Clock;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityAdministrationServiceTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void usersOnlyReturnsUsersFromAuthenticatedStore() {
        var currentStore = store();
        var foreignStore = store();
        var currentRole = new Role(currentStore, "CAJA");
        var foreignRole = new Role(foreignStore, "CAJA");
        var currentUser = new UserAccount(currentStore, "CURRENT", "hash", currentRole);
        var foreignUser = new UserAccount(foreignStore, "FOREIGN", "hash", foreignRole);
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        when(users.findAllByTiendaIdOrderByNombre(currentStore.getId()))
                .thenReturn(List.of(currentUser));
        authenticate(currentUser);

        var result = service(users, org.mockito.Mockito.mock(RoleRepository.class)).users();

        assertThat(result).extracting(SecurityAdministrationService.UserItem::name)
                .containsExactly("CURRENT");
    }

    @Test
    void cannotDeactivateUserFromAnotherStore() {
        var currentStore = store();
        var foreignStore = store();
        var currentUser = new UserAccount(
                currentStore, "CURRENT", "hash", new Role(currentStore, "CAJA"));
        var foreignUser = new UserAccount(
                foreignStore, "FOREIGN", "hash", new Role(foreignStore, "CAJA"));
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        when(users.findByIdAndTiendaId(foreignUser.getId(), currentStore.getId()))
                .thenReturn(Optional.empty());
        authenticate(currentUser);

        assertThatThrownBy(() -> service(users, org.mockito.Mockito.mock(RoleRepository.class))
                .setUserActive(foreignUser.getId(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserAccount no encontrado");
        assertThat(foreignUser.isActivo()).isTrue();
    }

    @Test
    void protectedAdminRoleCannotBeAssignedToAnotherUser() {
        var users = org.mockito.Mockito.mock(UserAccountRepository.class);
        var roles = org.mockito.Mockito.mock(RoleRepository.class);
        var store = store();
        var normalRole = new Role(store, "CAJA");
        var adminRole = new Role(store, "ADMIN");
        var user = new UserAccount(store, "USER", "hash", normalRole);
        when(users.findByIdAndTiendaId(user.getId(), store.getId()))
                .thenReturn(Optional.of(user));
        when(roles.findByIdAndTiendaId(adminRole.getId(), store.getId()))
                .thenReturn(Optional.of(adminRole));
        var service = service(users, roles);

        authenticate(user);

        assertThatThrownBy(() -> service.changeUserRole(
                user.getId(), adminRole.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
    }

    private static SecurityAdministrationService service(
            UserAccountRepository users, RoleRepository roles) {
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        when(organization.currentStore()).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return ((UserAccount) authentication.getPrincipal()).getTienda();
        });
        return new SecurityAdministrationService(
                organization, users, roles,
                org.mockito.Mockito.mock(PermissionRepository.class),
                org.mockito.Mockito.mock(UserSessionRepository.class),
                org.mockito.Mockito.mock(PasswordEncoder.class),
                Clock.systemUTC(),
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(BackupKeyStore.class),
                org.mockito.Mockito.mock(BackupSettingsRepository.class),
                org.mockito.Mockito.mock(InstallationRepository.class));
    }

    private static void authenticate(UserAccount user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
    }

    private static Store store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Store(
                new Company("B00000000", "Company", address),
                "Store", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
