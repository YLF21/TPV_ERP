package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SecurityStateMigrationContractTest {

    @Test
    void v43PersistsHashedSessionsAndScopesAttemptsByUserAndAddress() throws Exception {
        String sql = migration("V43__persistent_security_state.sql");

        assertThat(sql).contains("token_hash varchar(64) primary key");
        assertThat(sql).doesNotContain("access_token", "password_hash");
        assertThat(sql).contains("primary key(scope, username_key, remote_address)");
        assertThat(sql).contains("idx_saas_session_expiry", "idx_saas_login_attempt_expiry");
    }

    private String migration(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
            assertThat(stream).as(name).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
