package com.tpverp.saas.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class LocalAdminCredentialPolicyTest {

    @Test
    void admin0000IsAcceptedOnlyWithLocalProfile() {
        assertThat(new LocalAdminCredentialPolicy(Set.of("local")).permits("ADMIN", "0000")).isTrue();
        assertThat(new LocalAdminCredentialPolicy(Set.of("prod")).permits("ADMIN", "0000")).isFalse();
        assertThat(new LocalAdminCredentialPolicy(Set.of("test")).permits("admin", "0000")).isFalse();
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
}
