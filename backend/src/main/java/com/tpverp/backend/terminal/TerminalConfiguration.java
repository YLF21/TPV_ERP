package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenseRepository;
import com.tpverp.backend.organization.StoreRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tpverp.backend.security.domain.UserSessionRepository;
import java.time.Clock;
import com.tpverp.backend.audit.AuditService;

@Configuration
class TerminalConfiguration {

    @Bean
    TerminalRegistrationService terminalRegistrationService(
            TerminalRepository terminalRepository,
            StoreRepository tiendaRepository,
            CurrentOrganization organization,
            LicenseRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            UserSessionRepository sesionRepository,
            Clock clock,
            AuditService auditService) {
        return new TerminalRegistrationService(
                terminalRepository,
                tiendaRepository,
                organization,
                licenciaRepository,
                installationStatusService,
                passwordEncoder,
                sesionRepository,
                clock,
                auditService);
    }
}
