package com.tpverp.backend.cash;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.domain.UserAccountRepository;
import com.tpverp.backend.shared.api.PagedResult;
import com.tpverp.backend.terminal.TerminalRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashActivityService {
    private static final Set<String> ACTIONS = Set.of("OPENING", "CLOSING", "ENTRADA", "RETIRADA", "RETIRADA_CIERRE",
            "ENTRADA_ENTRE_SESIONES", "RETIRADA_ENTRE_SESIONES", "DEVOLUCION_EFECTIVO");
    private final CashActivityRepository repository;
    private final CurrentOrganization organization;
    private final TerminalRepository terminals;
    private final UserAccountRepository users;
    private final CashPermissionService permissions;
    private final Clock clock;

    public CashActivityService(CashActivityRepository repository, CurrentOrganization organization,
            TerminalRepository terminals, UserAccountRepository users, CashPermissionService permissions, Clock clock) {
        this.repository = repository;
        this.organization = organization;
        this.terminals = terminals;
        this.users = users;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<CashActivityView> list(LocalDate from, LocalDate to, UUID terminalId, UUID userId,
            String action, String cashState, int limit, String cursor, String sortBy, String sortDirection,
            Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("El limite debe estar entre 1 y 100");
        action = normalizedFilter(action);
        cashState = normalizedFilter(cashState);
        if (action != null && !ACTIONS.contains(action)) throw new IllegalArgumentException("Accion de caja no valida");
        if (cashState != null && !Set.of("ABIERTA", "CERRADA").contains(cashState)) {
            throw new IllegalArgumentException("Estado de caja no valido");
        }
        var sort = sortBy == null || sortBy.isBlank() ? "date" : sortBy.trim();
        CashActivityRepository.sortExpression(sort);
        var direction = sortDirection == null || sortDirection.isBlank() ? "asc"
                : sortDirection.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("asc", "desc").contains(direction)) throw new IllegalArgumentException("Direccion de ordenacion no valida");
        var includeAmounts = permissions.canSeeExpectedTotals(authentication);
        if (!includeAmounts && (sort.equals("quantity") || sort.equals("balance"))) {
            throw new AccessDeniedException("Se requiere permiso para consultar importes de caja");
        }
        var store = organization.currentStore();
        var storeId = store.getId();
        if (terminalId != null && terminals.findByIdAndTiendaId(terminalId, storeId).isEmpty()) {
            throw new IllegalArgumentException("Terminal no encontrada");
        }
        if (userId != null && users.findByIdAndTiendaId(userId, storeId).isEmpty()
                && !repository.userHasStoreActivity(storeId, userId)) {
            throw new IllegalArgumentException("Usuario no encontrado");
        }
        var zone = ZoneId.of(store.getTimezone());
        var day = LocalDate.now(clock.withZone(zone));
        var first = from == null ? (to == null ? day : to) : from;
        var last = to == null ? first : to;
        if (first.isAfter(last) || first.getYear() < 1 || last.getYear() > 9999) {
            throw new IllegalArgumentException("Rango de fechas de actividad de caja no valido");
        }
        var context = UUID.nameUUIDFromBytes((storeId + "|" + first + "|" + last + "|" + terminalId + "|" + userId
                + "|" + action + "|" + cashState + "|" + sort + "|" + direction).getBytes(StandardCharsets.UTF_8)).toString();
        var rows = repository.find(storeId, first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant(),
                terminalId, userId, action, cashState, sort, direction, decode(cursor, context, sort), limit + 1);
        var hasMore = rows.size() > limit;
        var page = hasMore ? rows.subList(0, limit) : rows;
        var items = page.stream().map(row -> includeAmounts ? row.item() : row.item().withoutAmounts()).toList();
        return new PagedResult<>(items, hasMore ? encode(page.getLast(), context) : null, hasMore);
    }

    @Transactional(readOnly = true)
    public CashActivityFilterOptionsView filterOptions(Authentication authentication) {
        permissions.requireCashStatusPermission(authentication);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var day = LocalDate.now(clock.withZone(zone));
        var earliest = repository.earliestDate(store.getId(), zone.getId());
        return new CashActivityFilterOptionsView(day, zone.getId(), earliest == null ? day : earliest,
                repository.terminalOptions(store.getId()), repository.userOptions(store.getId()));
    }

    private static String normalizedFilter(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String encode(CashActivityRepository.Row row, String context) {
        return "v1." + context + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(row.sortValue().getBytes(StandardCharsets.UTF_8)) + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(row.item().occurredAt().toString().getBytes(StandardCharsets.UTF_8))
                + "." + row.priority() + "." + row.item().id();
    }

    private static CashActivityRepository.Cursor decode(String cursor, String context, String sort) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            if (cursor.length() > 32768) throw new IllegalArgumentException();
            var parts = cursor.split("\\.", -1);
            if (parts.length != 6 || !parts[0].equals("v1") || !parts[1].equals(context)) throw new IllegalArgumentException();
            var value = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            CashActivityRepository.cursorValue(sort, value);
            var at = Instant.parse(new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8));
            var priority = Integer.parseInt(parts[4]);
            if (priority < 0 || priority > 2) throw new IllegalArgumentException();
            var id = parts[5];
            UUID.fromString(id.startsWith("opening:") || id.startsWith("closing:") ? id.substring(8) : id);
            return new CashActivityRepository.Cursor(value, at, priority, id);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Cursor de actividad de caja no valido para los filtros actuales", error);
        }
    }
}
