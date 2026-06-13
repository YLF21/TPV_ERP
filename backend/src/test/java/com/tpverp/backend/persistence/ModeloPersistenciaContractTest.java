package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.installation.Instalacion;
import com.tpverp.backend.organization.Empresa;
import com.tpverp.backend.organization.Tienda;
import com.tpverp.backend.security.domain.Permiso;
import com.tpverp.backend.security.domain.Rol;
import com.tpverp.backend.security.domain.RolPermiso;
import com.tpverp.backend.security.domain.RolPermisoRepository;
import com.tpverp.backend.security.domain.Usuario;
import com.tpverp.backend.terminal.Terminal;
import com.tpverp.backend.terminal.TipoTerminal;
import jakarta.persistence.Version;
import jakarta.persistence.Entity;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModeloPersistenciaContractTest {

    private static final Map<String, String> DIRECCION = Map.of(
        "linea1", "Calle Mayor 1",
        "ciudad", "Las Palmas",
        "codigoPostal", "35001",
        "provincia", "Las Palmas",
        "pais", "ES");

    @Test
    void instalacionCreaDemoDeTreintaDias() {
        Instant creadaEn = Instant.parse("2026-06-08T00:00:00Z");

        Instalacion instalacion = new Instalacion("INST-001", "public-key", creadaEn);

        assertThat(Duration.between(instalacion.getCreadaEn(), instalacion.getDemoHasta()))
            .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void normalizaRolesPermisosYUsuariosPeroNoTerminales() {
        Tienda tienda = tienda();
        Rol rol = new Rol(tienda, "ventas");
        Permiso permiso = new Permiso("facturas.leer", "permission.invoices.read", "ventas");
        Usuario usuario = new Usuario(tienda, "operador", "hash", rol);
        Terminal terminal = new Terminal(tienda, "Caja Principal", TipoTerminal.TERMINAL_VENTA, "hash");

        assertThat(rol.getNombre()).isEqualTo("VENTAS");
        assertThat(permiso.getCodigo()).isEqualTo("FACTURAS.LEER");
        assertThat(usuario.getNombre()).isEqualTo("OPERADOR");
        assertThat(terminal.getNombre()).isEqualTo("Caja Principal");
    }

    @Test
    void protegeRolYUsuarioAdminEnElModelo() {
        Tienda tienda = tienda();
        Rol admin = new Rol(tienda, "admin");
        Usuario usuarioAdmin = new Usuario(tienda, "admin", "hash", admin);

        assertThat(admin.isProtegido()).isTrue();
        assertThat(usuarioAdmin.isProtegido()).isTrue();
        assertThatThrownBy(() -> admin.renombrar("OTRO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(admin::validarEliminacion).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> usuarioAdmin.renombrar("OTRO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(usuarioAdmin::validarEliminacion).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void agregadosMutablesIncluyenVersionOptimista() {
        assertThat(hasVersionField(Instalacion.class)).isTrue();
        assertThat(hasVersionField(Empresa.class)).isTrue();
        assertThat(hasVersionField(Tienda.class)).isTrue();
        assertThat(hasVersionField(Rol.class)).isTrue();
        assertThat(hasVersionField(Usuario.class)).isTrue();
        assertThat(hasVersionField(Terminal.class)).isTrue();
    }

    @Test
    void rolPermisoEsEntidadConRepositorioPropio() {
        assertThat(RolPermiso.class.isAnnotationPresent(Entity.class)).isTrue();
        assertThat(RolPermisoRepository.class.isInterface()).isTrue();
    }

    private Tienda tienda() {
        Empresa empresa = new Empresa("B12345678", "Empresa Demo", DIRECCION);
        return new Tienda(empresa, null, DIRECCION, "address-hash", "Atlantic/Canary", "EUR", "es-ES");
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
