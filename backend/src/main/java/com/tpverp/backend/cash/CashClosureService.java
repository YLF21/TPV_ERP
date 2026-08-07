package com.tpverp.backend.cash;

import com.tpverp.backend.document.Money;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.shared.api.PagedResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashClosureService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CashClosureQueryRepository repository;
    private final CurrentOrganization organization;
    private final CashPermissionService permissions;
    private final Clock clock;

    public CashClosureService(
            CashClosureQueryRepository repository,
            CurrentOrganization organization,
            CashPermissionService permissions,
            Clock clock) {
        this.repository = repository;
        this.organization = organization;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResult<CashClosureView> list(
            LocalDate from,
            LocalDate to,
            UUID terminalId,
            UUID closingUserId,
            boolean onlyDiscrepancies,
            int limit,
            String cursor,
            Authentication authentication) {
        permissions.requireReportPermission(authentication);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("El limite debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var businessDate = LocalDate.now(clock.withZone(zone));
        var effectiveFrom = from == null ? businessDate : from;
        var effectiveTo = to == null ? effectiveFrom : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("El rango de fechas de cierre no es valido");
        }
        Instant fromInstant = effectiveFrom.atStartOfDay(zone).toInstant();
        Instant toExclusive = effectiveTo.plusDays(1).atStartOfDay(zone).toInstant();
        var rows = repository.findClosures(
                store.getId(), fromInstant, toExclusive, terminalId, closingUserId,
                onlyDiscrepancies, decodeCursor(cursor), limit + 1);
        boolean hasMore = rows.size() > limit;
        var pageRows = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        List<CashClosureView> items = pageRows.stream().map(this::view).toList();
        String nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeCursor(pageRows.getLast())
                : null;
        return new PagedResult<>(items, nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public PagedResult<CashClosureView> list(
            LocalDate from,
            LocalDate to,
            UUID terminalId,
            UUID closingUserId,
            boolean onlyDiscrepancies,
            int limit,
            String cursor,
            String sortBy,
            String sortDirection,
            Authentication authentication) {
        permissions.requireReportPermission(authentication);
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("El limite debe estar entre 1 y " + MAX_PAGE_SIZE);
        }
        var normalizedSortBy = normalizeSortBy(sortBy);
        var normalizedDirection = normalizeSortDirection(sortDirection);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        var businessDate = LocalDate.now(clock.withZone(zone));
        var effectiveFrom = from == null ? businessDate : from;
        var effectiveTo = to == null ? effectiveFrom : to;
        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("El rango de fechas de cierre no es valido");
        }
        var fromInstant = effectiveFrom.atStartOfDay(zone).toInstant();
        var toExclusive = effectiveTo.plusDays(1).atStartOfDay(zone).toInstant();
        var rows = repository.findClosures(
                store.getId(), fromInstant, toExclusive, terminalId, closingUserId,
                onlyDiscrepancies,
                decodeSortCursor(cursor, normalizedSortBy, normalizedDirection),
                limit + 1,
                normalizedSortBy,
                normalizedDirection);
        var hasMore = rows.size() > limit;
        var pageRows = hasMore ? new ArrayList<>(rows.subList(0, limit)) : rows;
        var items = pageRows.stream().map(this::view).toList();
        var nextCursor = hasMore && !pageRows.isEmpty()
                ? encodeSortCursor(pageRows.getLast(), normalizedSortBy, normalizedDirection)
                : null;
        return new PagedResult<>(items, nextCursor, hasMore);
    }

    @Transactional(readOnly = true)
    public CashClosureFilterOptionsView filterOptions(Authentication authentication) {
        permissions.requireReportPermission(authentication);
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        return new CashClosureFilterOptionsView(
                LocalDate.now(clock.withZone(zone)),
                zone.getId(),
                repository.findTerminalOptions(store.getId()),
                repository.findUserOptions(store.getId()));
    }

    private CashClosureView view(CashClosureQueryRepository.CashClosureRow row) {
        return new CashClosureView(
                row.id(), row.terminalId(), row.terminalName(),
                row.closingUserId(), row.closingUserName(), row.closingUsername(),
                row.closedAt(), Money.euros(row.expectedCash()), Money.euros(row.retainedFund()),
                Money.euros(row.discrepancy()), row.lateClosing());
    }

    private static String encodeCursor(CashClosureQueryRepository.CashClosureRow row) {
        var terminal = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(row.terminalSortKey().getBytes(StandardCharsets.UTF_8));
        return terminal + "." + row.closedAt().toEpochMilli() + "." + row.id();
    }

    private static CashClosureQueryRepository.CashClosureCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var parts = cursor.split("\\.", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException();
            }
            var terminal = new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            return new CashClosureQueryRepository.CashClosureCursor(
                    terminal,
                    Instant.ofEpochMilli(Long.parseLong(parts[1])),
                    UUID.fromString(parts[2]));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Cursor de cierres de caja no valido", error);
        }
    }

    private static String normalizeSortBy(String sortBy) {
        return switch (sortBy == null ? "" : sortBy.trim()) {
            case "terminal", "date", "time", "user", "expectedCash", "retainedFund", "discrepancy" -> sortBy.trim();
            default -> throw new IllegalArgumentException("Columna de ordenacion de cierres de caja no valida");
        };
    }

    private static String normalizeSortDirection(String sortDirection) {
        var normalized = sortDirection == null ? "" : sortDirection.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("asc") && !normalized.equals("desc")) {
            throw new IllegalArgumentException("Direccion de ordenacion de cierres de caja no valida");
        }
        return normalized;
    }

    private static String encodeSortCursor(
            CashClosureQueryRepository.CashClosureRow row,
            String sortBy,
            String sortDirection) {
        var value = switch (sortBy) {
            case "terminal" -> row.terminalSortKey();
            case "date", "time" -> Long.toString(row.closedAt().toEpochMilli());
            case "user" -> row.closingUserName().toLowerCase(java.util.Locale.ROOT);
            case "expectedCash" -> row.expectedCash().toPlainString();
            case "retainedFund" -> row.retainedFund().toPlainString();
            case "discrepancy" -> row.discrepancy().toPlainString();
            default -> throw new IllegalArgumentException("Columna de ordenacion de cierres de caja no valida");
        };
        var encodedValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        return "v2." + sortBy + "." + sortDirection + "." + encodedValue + "." + row.id();
    }

    private static CashClosureQueryRepository.CashClosureSortCursor decodeSortCursor(
            String cursor,
            String sortBy,
            String sortDirection) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var parts = cursor.split("\\.", -1);
            if (parts.length != 5 || !parts[0].equals("v2")
                    || !parts[1].equals(sortBy) || !parts[2].equals(sortDirection)) {
                throw new IllegalArgumentException();
            }
            var value = new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8);
            return new CashClosureQueryRepository.CashClosureSortCursor(value, UUID.fromString(parts[4]));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Cursor de cierres de caja no valido para la ordenacion actual", error);
        }
    }
}
