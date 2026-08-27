package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminProductionGuardTest {

    private static final String DEFAULT_ADMIN_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

    private final SaasAdminUserRepository users = mock(SaasAdminUserRepository.class);

    @Test
    void bloqueaArranqueProductivoConAdminPorDefectoActivo() {
        when(users.findAll()).thenReturn(List.of(user("admin", DEFAULT_ADMIN_HASH, true)));

        assertThatThrownBy(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SAAS_ADMIN_DEFAULT_ALLOWED");
    }

    @Test
    void bloqueaArranqueProductivoConViewerPorDefectoActivo() {
        when(users.findAll()).thenReturn(List.of(user("viewer", DEFAULT_ADMIN_HASH, true)));

        assertThatThrownBy(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Credenciales iniciales")
                .hasMessageNotContaining(DEFAULT_ADMIN_HASH);
    }

    @Test
    void bloqueaTambienElPasswordAdminPorDefectoDespuesDeMigrarloABcrypt() {
        String bcrypt = new AdminPasswordHasher().hash("admin");
        when(users.findAll()).thenReturn(List.of(user("admin", bcrypt, true)));

        assertThatThrownBy(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Credenciales iniciales")
                .hasMessageNotContaining(bcrypt);
    }

    @Test
    void permiteCredencialesPorDefectoEnTest() {
        when(users.findAll()).thenReturn(List.of(
                user("admin", DEFAULT_ADMIN_HASH, true),
                user("viewer", DEFAULT_ADMIN_HASH, true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("test"), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void permiteCredencialesPorDefectoSinPerfilParaDesarrolloLocal() {
        when(users.findAll()).thenReturn(List.of(
                user("admin", DEFAULT_ADMIN_HASH, true),
                user("viewer", DEFAULT_ADMIN_HASH, true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of(), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void permiteProduccionCuandoLasCredencialesInicialesSeHanCambiado() {
        when(users.findAll()).thenReturn(List.of(
                user("admin", "changed-admin", true),
                user("viewer", "changed-viewer", true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void permiteProduccionConUsuariosSeedInactivos() {
        when(users.findAll()).thenReturn(List.of(
                user("admin", DEFAULT_ADMIN_HASH, false),
                user("viewer", DEFAULT_ADMIN_HASH, false)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void overrideExplicitoPermiteTemporalmenteCredencialesSeedEnProduccion() {
        when(users.findAll()).thenReturn(List.of(
                user("admin", DEFAULT_ADMIN_HASH, true),
                user("viewer", DEFAULT_ADMIN_HASH, true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("prod"), true).run())
                .doesNotThrowAnyException();
    }

    @Test
    void bloqueaLaClaveDeCifradoDelLaboratorioAunqueSeAutoriceTemporalmenteElAdminSeed() {
        assertThatThrownBy(() -> new AdminProductionGuard(
                users, Set.of("prod"), true,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "database-secret").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SAAS_SECRET_ENCRYPTION_KEY");
    }

    @Test
    void bloqueaLaPasswordDeBaseDeDatosDelEjemploEnProduccion() {
        assertThatThrownBy(() -> new AdminProductionGuard(
                users, Set.of("prod"), true,
                "production-encryption-key",
                "replace-with-a-strong-database-password").run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SAAS_DB_PASSWORD");
    }

    private SaasAdminUser user(String username, String passwordHash, boolean active) {
        return new SaasAdminUser(
                UUID.randomUUID(),
                username,
                passwordHash,
                active,
                Instant.parse("2026-07-02T00:00:00Z"));
    }
}
