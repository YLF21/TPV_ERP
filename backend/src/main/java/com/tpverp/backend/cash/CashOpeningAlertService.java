package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashOpeningAlertService {
    private final CashOpeningAlertRepository repository;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final Clock clock;
    public CashOpeningAlertService(CashOpeningAlertRepository repository, CurrentOrganization organization,
            CashPermissionService permissions, Clock clock) {
        this.repository=repository; this.organization=organization; this.permissions=permissions; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public CashOpeningAlertPage list(LocalDate from, LocalDate to, UUID terminalId, UUID userId,
            String status, int limit, String cursor, Authentication authentication) {
        permissions.requireReportPermission(authentication);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("El limite debe estar entre 1 y 100");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Rango de fechas no valido");
        if (status != null && !status.equals("PENDING") && !status.equals("REVIEWED")) throw new IllegalArgumentException("Estado de alerta no valido");
        var store=organization.currentStore();
        var zone=ZoneId.of(store.getTimezone());
        var rows=repository.find(store.getId(), from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), terminalId,userId,status,decode(cursor),limit+1);
        var hasMore=rows.size()>limit;
        var items=hasMore ? rows.subList(0,limit) : rows;
        var next=hasMore ? items.getLast().openedAt()+"|"+items.getLast().id() : null;
        if (!permissions.canSeeExpectedTotals(authentication)) {
            items = items.stream().map(CashOpeningAlertService::withoutAmounts).toList();
        }
        return new CashOpeningAlertPage(items,next,hasMore,repository.pendingCount(store.getId()));
    }
    @Transactional
    public CashOpeningAlertView review(UUID id, String comment, long expectedVersion, Authentication authentication) {
        permissions.requireAccountingPermission(authentication);
        if (comment == null || comment.isBlank() || comment.trim().length()>1000 || expectedVersion<0) {
            throw new IllegalArgumentException("Comentario de revision obligatorio (maximo 1000 caracteres) y version valida");
        }
        var storeId=organization.currentStore().getId();
        repository.findById(storeId,id).orElseThrow(() -> new NoSuchElementException("Alerta de apertura no encontrada"));
        var user=organization.currentUser(authentication);
        if (!repository.review(storeId,id,user.getId(),Instant.now(clock),comment.trim(),expectedVersion)) {
            throw new IllegalStateException("La alerta ya fue revisada o ha cambiado; actualice la lista");
        }
        return repository.findById(storeId,id).orElseThrow();
    }
    private static CashOpeningAlertView withoutAmounts(CashOpeningAlertView alert) {
        return new CashOpeningAlertView(alert.id(), alert.sessionId(), alert.terminalId(), alert.terminalName(),
                alert.userId(), alert.username(), alert.userName(), alert.openedAt(), null, null, null,
                alert.status(), alert.reviewerId(), alert.reviewerUsername(), alert.reviewerName(),
                alert.reviewedAt(), alert.comment(), alert.version());
    }

    private static CashOpeningAlertRepository.Cursor decode(String cursor) {
        if (cursor==null || cursor.isBlank()) return null;
        try {
            var parts=cursor.split("\\|",-1);
            if (parts.length!=2) throw new IllegalArgumentException();
            return new CashOpeningAlertRepository.Cursor(Instant.parse(parts[0]),UUID.fromString(parts[1]));
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Cursor de alertas no valido",ex); }
    }
}
