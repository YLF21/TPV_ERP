package com.tpverp.backend.audit;

import com.tpverp.backend.organization.TiendaRepository;
import com.tpverp.backend.security.domain.UsuarioRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

public class AuditService {

    private final AuditoriaRepository auditoriaRepository;
    private final TiendaRepository tiendaRepository;
    private final UsuarioRepository usuarioRepository;
    private final Clock clock;

    public AuditService(
            AuditoriaRepository auditoriaRepository,
            TiendaRepository tiendaRepository,
            UsuarioRepository usuarioRepository,
            Clock clock) {
        this.auditoriaRepository = auditoriaRepository;
        this.tiendaRepository = tiendaRepository;
        this.usuarioRepository = usuarioRepository;
        this.clock = clock;
    }

    @Transactional
    public void record(String event, ResultadoAuditoria result, Map<String, Object> details) {
        var store = tiendaRepository.findAll().stream().findFirst().orElse(null);
        String userName = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getName();
        var user = store == null || userName == null
                ? null
                : usuarioRepository.findByTiendaIdAndNombre(store.getId(), userName).orElse(null);
        auditoriaRepository.save(new Auditoria(
                store, user, null, event, result, details, Instant.now(clock)));
    }

    @Transactional(readOnly = true)
    public List<AuditItem> query(Instant from, Instant until) {
        Instant effectiveUntil = until == null ? Instant.now(clock) : until;
        Instant effectiveFrom = from == null
                ? effectiveUntil.minus(30, ChronoUnit.DAYS)
                : from;
        if (effectiveUntil.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("El intervalo de auditoria no es valido");
        }
        return auditoriaRepository
                .findByCreadaEnBetweenOrderByCreadaEnDesc(effectiveFrom, effectiveUntil)
                .stream()
                .map(AuditItem::from)
                .toList();
    }

    @Transactional
    public void delete(UUID auditId, String confirmation) {
        if (!"ELIMINAR AUDITORIA".equals(confirmation)) {
            throw new IllegalArgumentException("La confirmacion de borrado no es valida");
        }
        auditoriaRepository.deleteById(auditId);
        record("AUDIT_DELETED", ResultadoAuditoria.EXITO, Map.of("deletedAuditId", auditId));
    }

    @Transactional
    public long purgeExpired() {
        return auditoriaRepository.deleteByCreadaEnBefore(
                Instant.now(clock).minus(5L * 365L, ChronoUnit.DAYS));
    }

    public record AuditItem(
            UUID id,
            String event,
            ResultadoAuditoria result,
            Map<String, Object> details,
            Instant createdAt) {
        static AuditItem from(Auditoria audit) {
            return new AuditItem(
                    audit.getId(),
                    audit.getEvento(),
                    audit.getResultado(),
                    audit.getDatos(),
                    audit.getCreadaEn());
        }
    }
}
