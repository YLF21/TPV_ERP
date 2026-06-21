package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.ConfiguracionBackupRepository;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.PermisoRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
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
        var currentRole = new Rol(currentStore, "CAJA");
        var foreignRole = new Rol(foreignStore, "CAJA");
        var currentUser = new Usuario(currentStore, "CURRENT", "hash", currentRole);
        var foreignUser = new Usuario(foreignStore, "FOREIGN", "hash", foreignRole);
        var users = org.mockito.Mockito.mock(UsuarioRepository.class);
        when(users.findAllByTiendaIdOrderByNombre(currentStore.getId()))
                .thenReturn(List.of(currentUser));
        authenticate(currentUser);

        var result = service(users, org.mockito.Mockito.mock(RolRepository.class)).users();

        assertThat(result).extracting(SecurityAdministrationService.UserItem::name)
                .containsExactly("CURRENT");
    }

    @Test
    void cannotDeactivateUserFromAnotherStore() {
        var currentStore = store();
        var foreignStore = store();
        var currentUser = new Usuario(
                currentStore, "CURRENT", "hash", new Rol(currentStore, "CAJA"));
        var foreignUser = new Usuario(
                foreignStore, "FOREIGN", "hash", new Rol(foreignStore, "CAJA"));
        var users = org.mockito.Mockito.mock(UsuarioRepository.class);
        when(users.findByIdAndTiendaId(foreignUser.getId(), currentStore.getId()))
                .thenReturn(Optional.empty());
        authenticate(currentUser);

        assertThatThrownBy(() -> service(users, org.mockito.Mockito.mock(RolRepository.class))
                .setUserActive(foreignUser.getId(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Usuario no encontrado");
        assertThat(foreignUser.isActivo()).isTrue();
    }

    @Test
    void protectedAdminRoleCannotBeAssignedToAnotherUser() {
        var users = org.mockito.Mockito.mock(UsuarioRepository.class);
        var roles = org.mockito.Mockito.mock(RolRepository.class);
        var store = store();
        var normalRole = new Rol(store, "CAJA");
        var adminRole = new Rol(store, "ADMIN");
        var user = new Usuario(store, "USER", "hash", normalRole);
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
            UsuarioRepository users, RolRepository roles) {
        var organization = org.mockito.Mockito.mock(CurrentOrganization.class);
        when(organization.currentStore()).thenAnswer(invocation -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            return ((Usuario) authentication.getPrincipal()).getTienda();
        });
        return new SecurityAdministrationService(
                organization, users, roles,
                org.mockito.Mockito.mock(PermisoRepository.class),
                org.mockito.Mockito.mock(SesionRepository.class),
                org.mockito.Mockito.mock(PasswordEncoder.class),
                Clock.systemUTC(),
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(BackupKeyStore.class),
                org.mockito.Mockito.mock(ConfiguracionBackupRepository.class),
                org.mockito.Mockito.mock(InstalacionRepository.class));
    }

    private static void authenticate(Usuario user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "token"));
    }

    private static Tienda store() {
        var address = Map.of(
                "linea1", "Calle 1", "ciudad", "Las Palmas",
                "codigoPostal", "35001", "provincia", "Las Palmas", "pais", "ES");
        return new Tienda(
                new Empresa("B00000000", "Empresa", address),
                "Tienda", address, UUID.randomUUID().toString(),
                "Atlantic/Canary", "EUR", "es-ES");
    }
}
