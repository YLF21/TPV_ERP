package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;

class LocalAdminCredentialPolicyTest {

    @Test
    void runtimeProfilesAcceptFourCharactersAndRejectShorterPasswords() {
        assertThat(new LocalAdminCredentialPolicy(Set.of("local")).permits("ADMIN", "0000")).isTrue();
        assertThat(new LocalAdminCredentialPolicy(Set.of("prod")).permits("ADMIN", "0000")).isTrue();
        assertThat(new LocalAdminCredentialPolicy(Set.of("test")).permits("admin", "0000")).isTrue();
        assertThat(new LocalAdminCredentialPolicy(Set.of("staging")).permits("ADMIN", "000")).isFalse();
        assertThat(new LocalAdminCredentialPolicy(Set.of("prod")).permits("ADMIN", "strong-password")).isTrue();
    }
    @Test
    void springSelectsEnvironmentConstructorWhenAuxiliaryTestConstructorExists() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            context.register(LocalAdminCredentialPolicy.class);
            context.refresh();

            assertThat(context.getBean(LocalAdminCredentialPolicy.class).permits("ADMIN", "0000")).isTrue();
        }
    }

    @Test
    void localSeedUsesRequestedFourCharacterPassword() throws IOException {
        var migration = new ClassPathResource("db/local/R__saas_local_admin_credentials.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "password_hash = '9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0'",
                "must_change_password = false");
    }

    @Test
    void testFixtureKeepsLegacyAdminCredentialsIsolatedFromProduction() throws IOException {
        var migration = new ClassPathResource("db/test/R__test_admin_password_change_bypass.sql");
        String sql = migration.getContentAsString(StandardCharsets.UTF_8);

        assertThat(sql).contains(
                "password_hash = '8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918'",
                "active = true",
                "must_change_password = false");
    }
}
