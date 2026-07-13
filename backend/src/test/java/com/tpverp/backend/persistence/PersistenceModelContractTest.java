package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.installation.Installation;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.domain.Permission;
import com.tpverp.backend.security.domain.Role;
import com.tpverp.backend.security.domain.RolePermission;
import com.tpverp.backend.security.domain.RolePermissionRepository;
import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TerminalType;
import jakarta.persistence.Version;
import jakarta.persistence.Entity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PersistenceModelContractTest {

    private static final Map<String, String> DIRECCION = Map.of(
        "linea1", "Calle Mayor 1",
        "ciudad", "Las Palmas",
        "codigoPostal", "35001",
        "provincia", "Las Palmas",
        "pais", "ES");

    @Test
    void instalacionCreaDemoDeTreintaDias() {
        Instant creadaEn = Instant.parse("2026-06-08T00:00:00Z");

        Installation instalacion = new Installation("INST-001", "public-key", creadaEn);

        assertThat(Duration.between(instalacion.getCreadaEn(), instalacion.getDemoHasta()))
            .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void normalizaRolesPermisosYUsuariosPeroNoTerminales() {
        Store tienda = tienda();
        Role rol = new Role(tienda, "ventas");
        Permission permiso = new Permission("facturas.leer", "permission.invoices.read", "ventas");
        UserAccount usuario = new UserAccount(tienda, "operador", "hash", rol);
        Terminal terminal = new Terminal(tienda, "Caja Principal", TerminalType.TERMINAL_VENTA, "hash");

        assertThat(rol.getNombre()).isEqualTo("VENTAS");
        assertThat(permiso.getCodigo()).isEqualTo("FACTURAS.LEER");
        assertThat(usuario.getNombre()).isEqualTo("OPERADOR");
        assertThat(terminal.getNombre()).isEqualTo("Caja Principal");
    }

    @Test
    void protegeRolYUsuarioAdminEnElModelo() {
        Store tienda = tienda();
        Role admin = new Role(tienda, "admin");
        UserAccount usuarioAdmin = new UserAccount(tienda, "admin", "hash", admin);

        assertThat(admin.isProtegido()).isTrue();
        assertThat(usuarioAdmin.isProtegido()).isTrue();
        assertThatThrownBy(() -> admin.renombrar("OTRO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(admin::validateDeletion).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> usuarioAdmin.renombrar("OTRO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(usuarioAdmin::validateDeletion).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void agregadosMutablesIncluyenVersionOptimista() {
        assertThat(hasVersionField(Installation.class)).isTrue();
        assertThat(hasVersionField(Company.class)).isTrue();
        assertThat(hasVersionField(Store.class)).isTrue();
        assertThat(hasVersionField(Role.class)).isTrue();
        assertThat(hasVersionField(UserAccount.class)).isTrue();
        assertThat(hasVersionField(Terminal.class)).isTrue();
    }

    @Test
    void rolPermisoEsEntidadConRepositorioPropio() {
        assertThat(RolePermission.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(RolePermissionRepository.class.isInterface()).isTrue();
    }

    @Test
    void rolPersisteYEliminaSusPermisosComoParteDelAgregado() throws Exception {
        OneToMany relation = Role.class.getDeclaredField("permisos").getAnnotation(OneToMany.class);

        assertThat(relation).isNotNull();
        assertThat(relation.orphanRemoval()).isTrue();
        assertThat(relation.cascade()).contains(CascadeType.ALL);
    }

    private Store tienda() {
        Company empresa = new Company("B12345678", "Company Demo", DIRECCION);
        return new Store(empresa, null, DIRECCION, "address-hash", "Atlantic/Canary", "EUR", "es-ES");
    }

    private boolean hasVersionField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Version.class)) {
                return true;
            }
        }
        return false;
    }
}
