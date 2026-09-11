package com.tpverp.saas.document;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Shared, bounded query vocabulary. No SQL identifiers or business formulas come from callers. */
public final class CommercialDocumentQuery {
    private CommercialDocumentQuery() { }

    public enum Type { TICKET, ALBARAN_VENTA, FACTURA_VENTA, RECTIFICATIVA_VENTA }
    public enum Status { CONFIRMADO, ANULADO, PENDIENTE, PARCIAL, PAGADO }
    public enum ActorRole { CREATED_BY, CONFIRMED_BY }
    public enum SortField { DATE, NUMBER, TYPE, STATUS, BASE, TAX, TOTAL, TERMINAL, USER, STORE, CURRENCY }
    public enum Direction { ASC, DESC }
    public enum Period { NONE, DAY, MONTH, QUARTER, YEAR }
    public enum Dimension { STORE, CUSTOMER, CREATED_BY, CONFIRMED_BY }

    /**
     * Trusted authorization output, NOT an HTTP request DTO. Future adapters must construct this
     * after checking their effective permissions. Empty restricted stores grant no access.
     */
    public record Scope(UUID companyId, boolean companyWide, Set<UUID> allowedStoreIds) {
        public Scope {
            Objects.requireNonNull(companyId, "companyId");
            allowedStoreIds = copy(allowedStoreIds, "allowedStoreIds");
            if (companyWide && !allowedStoreIds.isEmpty()) throw invalid("scope");
        }
        public static Scope company(UUID companyId) { return new Scope(companyId, true, Set.of()); }
        public static Scope stores(UUID companyId, Set<UUID> stores) { return new Scope(companyId, false, stores); }
    }

    /** Local user UUIDs are meaningful only together with their installation and actor role. */
    public record Actor(ActorRole role, UUID installationId, UUID localUserId) {
        public Actor {
            if (role == null || installationId == null || localUserId == null) throw invalid("actor");
        }
    }

    /** Types and states must be chosen explicitly; no hidden definition of sales or pending debt. */
    public record Filter(Set<UUID> storeIds, UUID customerId, Set<Type> types, Set<Status> statuses,
            LocalDate from, LocalDate to, Actor actor, String numberContains) {
        public Filter {
            storeIds = copy(storeIds, "storeIds");
            types = copy(types, "types");
            statuses = copy(statuses, "statuses");
            if (types.isEmpty() || statuses.isEmpty()) throw invalid("types/statuses");
            requireDate(from);
            requireDate(to);
            if (from != null && to != null && from.isAfter(to)) throw invalid("dateRange");
            if (numberContains != null) {
                numberContains = numberContains.strip();
                if (numberContains.isEmpty()) numberContains = null;
                else if (numberContains.length() > 120 || numberContains.codePoints().anyMatch(Character::isISOControl)) {
                    throw invalid("numberContains");
                }
            }
        }
    }

    public record Order(SortField field, Direction direction) {
        public Order {
            if (field == null || direction == null) throw invalid("order");
        }
        public static Order newestFirst() { return new Order(SortField.DATE, Direction.DESC); }
    }

    public record PageRequest(Order order, int size, String cursor) {
        public PageRequest {
            if (order == null) throw invalid("order");
            if (size < 1 || size > 200) throw invalid("size: 1..200");
            if (cursor != null && cursor.length() > 2048) throw invalid("cursor");
        }
    }

    /** Aggregates document amounts, ALWAYS separating type, status and currency. This is not net sales. */
    public record Aggregation(Period period, Set<Dimension> dimensions) {
        public Aggregation {
            if (period == null) throw invalid("period");
            dimensions = copy(dimensions, "dimensions");
        }
    }

    public record Row(UUID companyId, UUID storeId, UUID documentId, UUID installationId, long sourceRevision,
            Type type, Status status, String number, LocalDate date, String currency,
            BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
            UUID localCustomerId, UUID customerId, String customerCode, String customerName, String customerTaxId,
            UUID createdByLocalId, UUID confirmedByLocalId, UUID originTerminalLocalId,
            Instant createdAt, Instant confirmedAt, UUID cancelledByLocalId, Instant cancelledAt,
            LocalDate dueDate, Boolean settledByOrigin, boolean relationshipsComplete,
            String userName, String terminalName, String storeCode) {
        public Row(UUID companyId, UUID storeId, UUID documentId, UUID installationId, long sourceRevision,
                Type type, Status status, String number, LocalDate date, String currency,
                BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
                UUID localCustomerId, UUID customerId, String customerCode, String customerName, String customerTaxId,
                UUID createdByLocalId, UUID confirmedByLocalId, UUID originTerminalLocalId,
                Instant createdAt, Instant confirmedAt, UUID cancelledByLocalId, Instant cancelledAt,
                LocalDate dueDate, Boolean settledByOrigin, boolean relationshipsComplete) {
            this(companyId, storeId, documentId, installationId, sourceRevision, type, status, number, date,
                    currency, subtotal, taxTotal, total, localCustomerId, customerId, customerCode, customerName,
                    customerTaxId, createdByLocalId, confirmedByLocalId, originTerminalLocalId, createdAt,
                    confirmedAt, cancelledByLocalId, cancelledAt, dueDate, settledByOrigin, relationshipsComplete,
                    null, null, null);
        }
    }

    public record Page(List<Row> items, String nextCursor, boolean hasMore) {
        public Page { items = List.copyOf(items); }
    }

    /** Unresolved customers remain distinct by installation/local UUID, never merged by a guessed identity. */
    public record Group(LocalDate periodStart, UUID storeId, UUID customerId,
            UUID unresolvedCustomerInstallationId, UUID unresolvedCustomerLocalId,
            UUID actorInstallationId, UUID createdByLocalId, UUID confirmedByLocalId,
            Type type, Status status, String currency) { }

    public record Total(Group group, long documentCount, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total) { }

    public record Totals(Aggregation grouping, List<Total> items) {
        public Totals { items = List.copyOf(items); }
    }

    static void requireDate(LocalDate value) {
        if (value != null && (value.getYear() < 1 || value.getYear() > 9999)) throw invalid("date");
    }

    private static <T> Set<T> copy(Set<T> values, String field) {
        if (values == null) throw invalid(field);
        if (values.stream().anyMatch(Objects::isNull) || values.size() > 2000) throw invalid(field);
        return Set.copyOf(values);
    }

    static ResponseStatusException invalid(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Consulta documental invalida: " + field);
    }
}
