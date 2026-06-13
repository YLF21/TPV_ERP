package com.tpverp.backend.terminal;

import com.tpverp.backend.installation.InstallationStatusService;
import com.tpverp.backend.licensing.LicenciaRepository;
import com.tpverp.backend.organization.TiendaRepository;
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
            LicenciaRepository licenciaRepository,
            InstallationStatusService installationStatusService,
            PasswordEncoder passwordEncoder,
            SesionRepository sesionRepository,
            Clock clock,
            AuditService auditService) {
        return new TerminalRegistrationService(
                terminalRepository,
                tiendaRepository,
                licenciaRepository,
                installationStatusService,
                passwordEncoder,
                sesionRepository,
                clock,
                auditService);
    }
}
