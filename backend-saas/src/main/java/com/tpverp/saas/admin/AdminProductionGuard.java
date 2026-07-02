package com.tpverp.saas.admin;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AdminProductionGuard implements ApplicationRunner {

    private static final String DEFAULT_ADMIN_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";

    private final SaasAdminUserRepository users;
    private final Set<String> activeProfiles;
    private final boolean defaultAllowed;

    @Autowired
    public AdminProductionGuard(
            SaasAdminUserRepository users,
            Environment environment,
            @Value("${tpv.saas.admin-default-allowed:false}") boolean defaultAllowed) {
        this(users, Set.of(environment.getActiveProfiles()), defaultAllowed);
    }

    AdminProductionGuard(SaasAdminUserRepository users, Set<String> activeProfiles, boolean defaultAllowed) {
        this.users = users;
        this.activeProfiles = activeProfiles;
        this.defaultAllowed = defaultAllowed;
    }

    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    void run() {
        if (defaultAllowed || !activeProfiles.contains("prod")) {
            return;
        }
        users.findByUsernameIgnoreCase("admin")
                .filter(SaasAdminUser::isActive)
                .filter(user -> DEFAULT_ADMIN_HASH.equalsIgnoreCase(user.getPasswordHash()))
                .ifPresent(user -> {
                    throw new IllegalStateException(
                            "Password admin por defecto no permitida con perfil prod. "
                                    + "Cambiala o usa TPV_SAAS_ADMIN_DEFAULT_ALLOWED=true solo temporalmente.");
                });
    }
}
