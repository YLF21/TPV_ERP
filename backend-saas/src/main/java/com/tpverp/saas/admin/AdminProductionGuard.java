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

    private static final String DEFAULT_SEED_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";
    private static final String DEV_ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String DEV_DATABASE_PASSWORD =
            "replace-with-a-strong-database-password";
    private static final Set<String> KNOWN_DEFAULT_HASHES = Set.of(DEFAULT_SEED_HASH);

    private final SaasAdminUserRepository users;
    private final AdminPasswordHasher passwords;
    private final Set<String> activeProfiles;
    private final boolean defaultAllowed;
    private final String encryptionKey;
    private final String databasePassword;

    @Autowired
    public AdminProductionGuard(
            SaasAdminUserRepository users,
            AdminPasswordHasher passwords,
            Environment environment,
            @Value("${tpv.saas.admin-default-allowed:false}") boolean defaultAllowed,
            @Value("${tpv.saas.secrets.encryption-key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String databasePassword) {
        this(users, passwords, Set.of(environment.getActiveProfiles()), defaultAllowed,
                encryptionKey, databasePassword);
    }

    AdminProductionGuard(SaasAdminUserRepository users, Set<String> activeProfiles, boolean defaultAllowed) {
        this(users, new AdminPasswordHasher(), activeProfiles, defaultAllowed,
                "production-encryption-key", "production-database-password");
    }

    AdminProductionGuard(
            SaasAdminUserRepository users,
            Set<String> activeProfiles,
            boolean defaultAllowed,
            String encryptionKey,
            String databasePassword) {
        this(users, new AdminPasswordHasher(), activeProfiles, defaultAllowed,
                encryptionKey, databasePassword);
    }

    AdminProductionGuard(
            SaasAdminUserRepository users,
            AdminPasswordHasher passwords,
            Set<String> activeProfiles,
            boolean defaultAllowed,
            String encryptionKey,
            String databasePassword) {
        this.users = users;
        this.passwords = passwords;
        this.activeProfiles = activeProfiles;
        this.defaultAllowed = defaultAllowed;
        this.encryptionKey = encryptionKey;
        this.databasePassword = databasePassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    void run() {
        if (!activeProfiles.contains("prod")) {
            return;
        }
        rejectUnsafeProductionSecrets();
        if (defaultAllowed) {
            return;
        }
        boolean unsafeSeedUserExists = users.findAll().stream()
                .filter(SaasAdminUser::isActive)
                .anyMatch(this::usesKnownSeedCredential);
        if (unsafeSeedUserExists) {
            throw new IllegalStateException(
                    "Credenciales iniciales activas no permitidas con perfil prod. "
                            + "Cambialas o usa TPV_SAAS_ADMIN_DEFAULT_ALLOWED=true solo temporalmente.");
        }
    }

    private void rejectUnsafeProductionSecrets() {
        if (encryptionKey == null || encryptionKey.isBlank() || DEV_ENCRYPTION_KEY.equals(encryptionKey.trim())) {
            throw new IllegalStateException(
                    "TPV_SAAS_SECRET_ENCRYPTION_KEY debe ser una clave exclusiva y no la clave del laboratorio DEV.");
        }
        if (databasePassword == null || databasePassword.isBlank()
                || DEV_DATABASE_PASSWORD.equals(databasePassword.trim())) {
            throw new IllegalStateException(
                    "TPV_SAAS_DB_PASSWORD debe ser una clave exclusiva y no el valor de ejemplo DEV.");
        }
    }

    private boolean usesKnownSeedCredential(SaasAdminUser user) {
        return KNOWN_DEFAULT_HASHES.stream()
                .anyMatch(knownHash -> knownHash.equalsIgnoreCase(user.getPasswordHash()))
                || passwords.matches("admin", user.getPasswordHash());
    }
}
