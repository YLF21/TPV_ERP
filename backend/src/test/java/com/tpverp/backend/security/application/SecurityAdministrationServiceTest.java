package com.tpverp.backend.security.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.ConfiguracionBackupRepository;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.installation.InstalacionRepository;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.PermisoRepository;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SecurityAdministrationServiceTest {

    @Test
    void protectedAdminRoleCannotBeAssignedToAnotherUser() {
        var stores = org.mockito.Mockito.mock(TiendaRepository.class);
        var users = org.mockito.Mockito.mock(UsuarioRepository.class);
        var roles = org.mockito.Mockito.mock(RolRepository.class);
        var store = store();
        var normalRole = new Rol(store, "CAJA");
        var adminRole = new Rol(store, "ADMIN");
        var user = new Usuario(store, "USER", "hash", normalRole);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(roles.findById(adminRole.getId())).thenReturn(Optional.of(adminRole));
        var service = new SecurityAdministrationService(
                stores,
                users,
                roles,
                org.mockito.Mockito.mock(PermisoRepository.class),
                org.mockito.Mockito.mock(SesionRepository.class),
                org.mockito.Mockito.mock(PasswordEncoder.class),
                Clock.systemUTC(),
                org.mockito.Mockito.mock(AuditService.class),
                org.mockito.Mockito.mock(BackupKeyStore.class),
                org.mockito.Mockito.mock(ConfiguracionBackupRepository.class),
                org.mockito.Mockito.mock(InstalacionRepository.class));

        assertThatThrownBy(() -> service.changeUserRole(
                user.getId(), adminRole.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
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
