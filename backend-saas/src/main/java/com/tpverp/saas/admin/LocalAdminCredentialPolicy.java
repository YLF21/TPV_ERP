package com.tpverp.saas.admin;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class LocalAdminCredentialPolicy {

    private final Set<String> activeProfiles;

    @Autowired
    LocalAdminCredentialPolicy(Environment environment) {
        this(Arrays.stream(environment.getActiveProfiles()).collect(Collectors.toUnmodifiableSet()));
    }

    LocalAdminCredentialPolicy(Set<String> activeProfiles) {
        this.activeProfiles = Set.copyOf(activeProfiles);
    }

    boolean permits(String username, String password) {
        if (activeProfiles.contains("test")) {
            return true;
        }
        return password != null && password.length() >= 4;
    }
}
