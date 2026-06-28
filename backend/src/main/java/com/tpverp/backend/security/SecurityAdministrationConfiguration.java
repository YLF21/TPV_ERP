package com.tpverp.backend.security;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.SecurityAdministrationService;
import com.tpverp.backend.security.domain.PermissionRepository;
import com.tpverp.backend.security.domain.RoleRepository;
import com.tpverp.backend.security.domain.UserSessionRepository;
import com.tpverp.backend.security.domain.UserAccountRepository;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.BackupSettingsRepository;
import com.tpverp.backend.installation.InstallationRepository;

@Configuration
class SecurityAdministrationConfiguration {

    @Bean
    CorePermissionBootstrap corePermissionBootstrap(PermissionRepository permisoRepository) {
        return new CorePermissionBootstrap(permisoRepository);
    }

    @Bean
    @Order(10)
    ApplicationRunner corePermissionRunner(CorePermissionBootstrap bootstrap) {
        return arguments -> bootstrap.initialize();
    }

    @Bean
    SecurityAdministrationService securityAdministrationService(
            CurrentOrganization organization,
            UserAccountRepository usuarioRepository,
            RoleRepository rolRepository,
            PermissionRepository permisoRepository,
            UserSessionRepository sesionRepository,
            StoreRepository storeRepository,
            JdbcTemplate jdbc,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            BackupKeyStore backupKeyStore,
            BackupSettingsRepository backupConfigurationRepository,
            InstallationRepository installationRepository) {
        return new SecurityAdministrationService(
                organization,
                usuarioRepository,
                rolRepository,
                permisoRepository,
                sesionRepository,
                storeRepository,
                jdbc,
                passwordEncoder,
                clock,
                auditService,
                backupKeyStore,
                backupConfigurationRepository,
                installationRepository);
    }
}
