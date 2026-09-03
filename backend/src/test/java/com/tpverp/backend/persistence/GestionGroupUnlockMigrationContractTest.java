package com.tpverp.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class GestionGroupUnlockMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V239__gestion_group_session_unlock.sql";

    @Test
    void bindsUnlockAndThrottleStateToTheExactBackendSessionWithoutCredentials()
            throws IOException {
        try (var stream = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(stream).as("Debe existir %s", MIGRATION).isNotNull();
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                    .contains("session_id uuid not null references sesion(id) on delete cascade")
                    .contains("unique (session_id, group_code)")
                    .contains("failed_attempts integer not null")
                    .contains("blocked_until timestamptz")
                    .contains("unlocked_at timestamptz")
                    .contains("auth_version bigint not null default 0")
                    .doesNotContain("password")
                    .doesNotContain("contrasena");
        }
    }
}
