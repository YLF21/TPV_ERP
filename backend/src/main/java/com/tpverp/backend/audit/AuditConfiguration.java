package com.tpverp.backend.audit;

import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
class AuditConfiguration {

    @Bean
    AuditService auditService(
            AuditoriaRepository auditoriaRepository,
            TiendaRepository tiendaRepository,
            UsuarioRepository usuarioRepository,
            Clock clock) {
        return new AuditService(auditoriaRepository, tiendaRepository, usuarioRepository, clock);
    }

    @Bean
    AuditRetentionJob auditRetentionJob(AuditService auditService) {
        return new AuditRetentionJob(auditService);
    }

    static final class AuditRetentionJob {
        private final AuditService auditService;

        AuditRetentionJob(AuditService auditService) {
            this.auditService = auditService;
        }

        @Scheduled(cron = "0 15 3 * * *")
        void purgeExpiredAudit() {
            auditService.purgeExpired();
        }
    }
}
