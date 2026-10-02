package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class CashAlertService {
    private final CashAlertRepository repository;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final Clock clock;

    public CashAlertService(CashAlertRepository repository, CurrentOrganization organization,
            CashPermissionService permissions, Clock clock) {
        this.repository = repository;
        this.organization = organization;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CashAlertPage list(LocalDate from, LocalDate to, UUID terminalId, UUID userId,
            String status, String type, int limit, String cursor, Authentication authentication) {
        permissions.requireReportPermission(authentication);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("El limite debe estar entre 1 y 100");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("Rango de fechas no valido");
        if (status != null && !status.equals("PENDING") && !status.equals("REVIEWED")) throw new IllegalArgumentException("Estado de alerta no valido");
        if (type != null) validateType(type);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var rows = repository.find(store.getId(), from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant(), terminalId, userId,
                status, type, decode(cursor), limit + 1);
        var hasMore = rows.size() > limit;
        var items = hasMore ? rows.subList(0, limit) : rows;
        var next = hasMore ? items.getLast().occurredAt() + "|" + items.getLast().id() + "|" + items.getLast().type() : null;
        if (!permissions.canSeeExpectedTotals(authentication)) items = items.stream().map(CashAlertService::withoutAmounts).toList();
        return new CashAlertPage(items, next, hasMore, repository.pendingCount(store.getId()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CashAlertDetailView detail(UUID id, String type, Authentication authentication) {
        permissions.requireReportPermission(authentication);
        validateType(type);
        var storeId = organization.currentStore().getId();
        var alert = repository.findById(storeId, id, type)
                .orElseThrow(() -> new NoSuchElementException("Alerta de caja no encontrada"));
        var attempts = "CLOSING".equals(type) ? repository.findAttempts(storeId, alert.sessionId())
                : List.<CashAlertAttemptView>of();
        if (!permissions.canSeeExpectedTotals(authentication)) {
            alert = withoutAmounts(alert);
            attempts = attempts.stream().map(a -> new CashAlertAttemptView(a.id(), a.attemptNumber(),
                    a.occurredAt(), a.userId(), a.username(), a.userName(), null, null, null, a.sessionClosed())).toList();
        }
        return new CashAlertDetailView(alert, attempts);
    }

    @Transactional
    public CashAlertView review(UUID id, String type, String comment, long expectedVersion, Authentication authentication) {
        permissions.requireAccountingPermission(authentication);
        validateType(type);
        if (comment == null || comment.isBlank() || comment.trim().length() > 1000 || expectedVersion < 0) {
            throw new IllegalArgumentException("Comentario de revision obligatorio (maximo 1000 caracteres) y version valida");
        }
        var storeId = organization.currentStore().getId();
        repository.findById(storeId, id, type).orElseThrow(() -> new NoSuchElementException("Alerta de caja no encontrada"));
        var user = organization.currentUser(authentication);
        if (!repository.review(storeId, id, type, user.getId(), Instant.now(clock), comment.trim(), expectedVersion)) {
            throw new IllegalStateException("La alerta ya fue revisada o ha cambiado; actualice la lista");
        }
        var result = repository.findById(storeId, id, type).orElseThrow();
        return permissions.canSeeExpectedTotals(authentication) ? result : withoutAmounts(result);
    }

    private static void validateType(String type) {
        if (!"OPENING".equals(type) && !"CLOSING".equals(type)) throw new IllegalArgumentException("Tipo de alerta no valido");
    }

    private static CashAlertView withoutAmounts(CashAlertView a) {
        return new CashAlertView(a.id(), a.sessionId(), a.terminalId(), a.terminalName(), a.userId(),
                a.username(), a.userName(), a.type(), a.occurredAt(), a.attemptNumber(), a.sessionClosed(),
                null, null, null, a.status(), a.reviewerId(), a.reviewerUsername(), a.reviewerName(),
                a.reviewedAt(), a.comment(), a.version());
    }

    private static CashAlertRepository.Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            var parts = cursor.split("\\|", -1);
            if (parts.length != 3) throw new IllegalArgumentException();
            validateType(parts[2]);
            return new CashAlertRepository.Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]), parts[2]);
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Cursor de alertas no valido", ex); }
    }
}
