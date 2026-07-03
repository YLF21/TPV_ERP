package com.tpverp.backend.audit;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.tpverp.backend.organization.Store;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

public class AuditService {

    private final AuditEntryRepository auditoriaRepository;
    private final CurrentOrganization organization;
    private final Clock clock;

    public AuditService(
            AuditEntryRepository auditoriaRepository,
            CurrentOrganization organization,
            Clock clock) {
        this.auditoriaRepository = auditoriaRepository;
        this.organization = organization;
        this.clock = clock;
    }

    @Transactional
    public void record(String event, AuditResult result, Map<String, Object> details) {
        var store = currentStoreOrNull();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var user = resolveUser(authentication);
        auditoriaRepository.save(new AuditEntry(
                store, user, null, event, result, details, Instant.now(clock)));
    }

    // Records internal tasks without linking them to an interactive session or user.
    @Transactional
    public void recordSystem(
            Store store,
            String event,
            AuditResult result,
            Map<String, Object> details) {
        auditoriaRepository.save(new AuditEntry(
                store, null, null, event, result, details, Instant.now(clock)));
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
                .findByTiendaIdAndCreadaEnBetweenOrderByCreadaEnDesc(
                        organization.currentStore().getId(), effectiveFrom, effectiveUntil)
                .stream()
                .map(AuditItem::from)
                .toList();
    }

    @Transactional
    public void delete(UUID auditId, String confirmation) {
        if (!"ELIMINAR AUDITORIA".equals(confirmation)) {
            throw new IllegalArgumentException("La confirmacion de borrado no es valida");
        }
        var audit = auditoriaRepository.findByIdAndTiendaId(
                        auditId, organization.currentStore().getId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "message.audit.not_found"));
        auditoriaRepository.delete(audit);
        record("AUDIT_DELETED", AuditResult.EXITO, Map.of("deletedAuditId", auditId));
    }

    @Transactional
    public long purgeExpired() {
        return auditoriaRepository.deleteByCreadaEnBefore(
                Instant.now(clock).minus(5L * 365L, ChronoUnit.DAYS));
    }

    private com.tpverp.backend.security.domain.UserAccount resolveUser(
            org.springframework.security.core.Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return organization.currentUser(authentication);
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    private Store currentStoreOrNull() {
        try {
            return organization.currentStore();
        } catch (IllegalStateException exception) {
            return null;
        }
    }

    public record AuditItem(
            UUID id,
            String event,
            AuditResult result,
            Map<String, Object> details,
            Instant createdAt) {
        static AuditItem from(AuditEntry audit) {
            return new AuditItem(
                    audit.getId(),
                    audit.getEvento(),
                    audit.getResultado(),
                    audit.getDatos(),
                    audit.getCreadaEn());
        }
    }
}
