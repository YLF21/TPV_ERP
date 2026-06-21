package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.tpverp.backend.security.domain.SesionRepository;
import java.time.Clock;
import com.tpverp.backend.audit.AuditService;

@Configuration
class TerminalConfiguration {

    @Bean
    TerminalRegistrationService terminalRegistrationService(
            TerminalRepository terminalRepository,
            TiendaRepository tiendaRepository,
            CurrentOrganization organization,
            LicenciaRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            SesionRepository sesionRepository,
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
