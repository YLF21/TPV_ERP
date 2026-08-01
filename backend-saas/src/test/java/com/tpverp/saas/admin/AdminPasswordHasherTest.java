package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminPasswordHasherTest {

    private final AdminPasswordHasher hasher = new AdminPasswordHasher();

    @Test
    void createsAdaptiveHashesAndNeverRepeatsSalt() {
        String first = hasher.hash("a-secure-password");
        String second = hasher.hash("a-secure-password");

        assertThat(first).startsWith("$2");
        assertThat(second).startsWith("$2").isNotEqualTo(first);
        assertThat(hasher.matches("a-secure-password", first)).isTrue();
        assertThat(hasher.matches("wrong-password", first)).isFalse();
        assertThat(hasher.needsUpgrade(first)).isFalse();
    }

    @Test
    void acceptsLegacySha256OnlyForProgressiveMigration() {
        String legacyAdmin = "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

        assertThat(hasher.matches("admin", legacyAdmin)).isTrue();
        assertThat(hasher.matches("wrong", legacyAdmin)).isFalse();
        assertThat(hasher.needsUpgrade(legacyAdmin)).isTrue();
    }
}
