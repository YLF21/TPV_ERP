package com.tpverp.backend.security;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.SecurityAdministrationService;
import com.tpverp.backend.security.domain.PermisoRepository;
import com.tpverp.backend.security.domain.RolRepository;
import com.tpverp.backend.security.domain.SesionRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.backup.application.BackupKeyStore;
import com.tpverp.backend.backup.ConfiguracionBackupRepository;
import com.tpverp.backend.installation.InstalacionRepository;

@Configuration
class SecurityAdministrationConfiguration {

    @Bean
    CorePermissionBootstrap corePermissionBootstrap(PermisoRepository permisoRepository) {
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
            UsuarioRepository usuarioRepository,
            RolRepository rolRepository,
            PermisoRepository permisoRepository,
            SesionRepository sesionRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuditService auditService,
            BackupKeyStore backupKeyStore,
            ConfiguracionBackupRepository backupConfigurationRepository,
            InstalacionRepository installationRepository) {
        return new SecurityAdministrationService(
                organization,
                usuarioRepository,
                rolRepository,
                permisoRepository,
                sesionRepository,
                passwordEncoder,
                clock,
                auditService,
                backupKeyStore,
                backupConfigurationRepository,
                installationRepository);
    }
}
