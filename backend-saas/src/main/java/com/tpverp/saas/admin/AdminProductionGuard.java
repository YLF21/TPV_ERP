package com.tpverp.saas.admin;

import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AdminProductionGuard implements ApplicationRunner {

    private static final String DEFAULT_SEED_HASH =
            "8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918";
    private static final String LOCAL_ADMIN_HASH =
            "9af15b336e6a9619928537df30b2e6a2376569fcf9d7e773eccede65606529a0";
    private static final String DEV_ENCRYPTION_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String DEV_DATABASE_PASSWORD =
            "replace-with-a-strong-database-password";
    private static final Set<String> KNOWN_DEFAULT_HASHES = Set.of(DEFAULT_SEED_HASH, LOCAL_ADMIN_HASH);

    private final SaasAdminUserRepository users;
    private final AdminPasswordHasher passwords;
    private final Set<String> activeProfiles;
    private final String encryptionKey;
    private final String databasePassword;
    private final String bootstrapAdminPassword;

    @Autowired
    public AdminProductionGuard(
            SaasAdminUserRepository users,
            AdminPasswordHasher passwords,
            Environment environment,
            @Value("${tpv.saas.secrets.encryption-key:}") String encryptionKey,
            @Value("${spring.datasource.password:}") String databasePassword,
            @Value("${tpv.saas.bootstrap-admin-password:}") String bootstrapAdminPassword) {
        this(users, passwords, Set.of(environment.getActiveProfiles()), encryptionKey,
                databasePassword, bootstrapAdminPassword);
    }

    AdminProductionGuard(SaasAdminUserRepository users, Set<String> activeProfiles, boolean ignoredDefaultAllowed) {
        this(users, new AdminPasswordHasher(), activeProfiles,
                "production-encryption-key", "production-database-password", null);
    }

    AdminProductionGuard(
            SaasAdminUserRepository users,
            Set<String> activeProfiles,
            boolean ignoredDefaultAllowed,
            String encryptionKey,
            String databasePassword) {
        this(users, new AdminPasswordHasher(), activeProfiles, encryptionKey, databasePassword, null);
    }

    AdminProductionGuard(
            SaasAdminUserRepository users,
            AdminPasswordHasher passwords,
            Set<String> activeProfiles,
            String encryptionKey,
            String databasePassword,
            String bootstrapAdminPassword) {
        this.users = users;
        this.passwords = passwords;
        this.activeProfiles = activeProfiles;
        this.encryptionKey = encryptionKey;
        this.databasePassword = databasePassword;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        run();
    }

    void run() {
        if (activeProfiles.contains("prod") && activeProfiles.contains("local")) {
            throw new IllegalStateException("Los perfiles prod y local no pueden activarse a la vez");
        }
        if (activeProfiles.contains("local") || activeProfiles.contains("test")) {
            return;
        }
        rejectUnsafeProductionSecrets();
        List<SaasAdminUser> unsafeUsers = users.findAll().stream()
                .filter(SaasAdminUser::isActive)
                .filter(this::usesKnownSeedCredential)
                .toList();
        if (!unsafeUsers.isEmpty()) {
            bootstrapOrReject(unsafeUsers);
        }
        boolean unsafeSeedUserExists = users.findAll().stream()
                .filter(SaasAdminUser::isActive)
                .anyMatch(this::usesKnownSeedCredential);
        if (unsafeSeedUserExists) {
            throw new IllegalStateException("Credenciales iniciales o locales activas no permitidas fuera de local/test");
        }
    }

    private void bootstrapOrReject(List<SaasAdminUser> unsafeUsers) {
        if (bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
            throw new IllegalStateException(
                    "Credenciales iniciales activas: configura TPV_SAAS_BOOTSTRAP_ADMIN_PASSWORD para el primer arranque");
        }
        String bootstrap = bootstrapAdminPassword.trim();
        if (bootstrap.length() < 12 || "admin".equalsIgnoreCase(bootstrap) || "0000".equals(bootstrap)) {
            throw new IllegalStateException("TPV_SAAS_BOOTSTRAP_ADMIN_PASSWORD no cumple la politica minima");
        }
        for (SaasAdminUser user : unsafeUsers) {
            if ("admin".equalsIgnoreCase(user.getUsername())) {
                user.changePasswordHash(passwords.hash(bootstrap));
                user.requirePasswordChange();
            } else {
                user.deactivate();
            }
            users.save(user);
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
                || passwords.matches("admin", user.getPasswordHash())
                || passwords.matches("0000", user.getPasswordHash());
    }
}
