package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminProductionGuardTest {

    private static final String DEFAULT_ADMIN_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

    private final SaasAdminUserRepository users = mock(SaasAdminUserRepository.class);

    @Test
    void bloqueaArranqueProductivoConAdminPorDefectoActivo() {
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin(DEFAULT_ADMIN_HASH, true)));

        assertThatThrownBy(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TPV_SAAS_ADMIN_DEFAULT_ALLOWED");
    }

    @Test
    void permiteAdminPorDefectoEnTest() {
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin(DEFAULT_ADMIN_HASH, true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("test"), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void permiteAdminPorDefectoSinPerfilParaDesarrolloLocal() {
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin(DEFAULT_ADMIN_HASH, true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of(), false).run())
                .doesNotThrowAnyException();
    }

    @Test
    void permiteProduccionCuandoAdminTienePasswordCambiada() {
        when(users.findByUsernameIgnoreCase("admin")).thenReturn(Optional.of(admin("changed", true)));

        assertThatCode(() -> new AdminProductionGuard(users, Set.of("prod"), false).run())
                .doesNotThrowAnyException();
    }

    private SaasAdminUser admin(String passwordHash, boolean active) {
        return new SaasAdminUser(UUID.randomUUID(), "admin", passwordHash, active, Instant.parse("2026-07-02T00:00:00Z"));
    }
}
