package com.tpverp.backend.document;

import com.tpverp.backend.excel.CustomerDocumentExportRequest;
import com.tpverp.backend.excel.CustomerDocumentExportRequest.Column;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class SaasCustomerDocumentApi {
    public static final String COVERAGE = "RECEIVED_V2_ONLY";
    private static final Set<String> SORTS = Set.of("number", "date", "type", "status", "base", "tax", "total", "terminal", "user", "store", "currency");
    private SaasCustomerDocumentApi() { }

    public record Filters(String search, DocumentStatus status, LocalDate dateFrom, LocalDate dateTo) {
        public Filters {
            search = search == null || search.isBlank() ? null : search.strip();
            if (search != null && (search.length() > 120 || search.codePoints().anyMatch(Character::isISOControl))) invalid();
            if (status == DocumentStatus.BORRADOR) invalid();
            if (dateFrom != null && (dateFrom.getYear() < 1 || dateFrom.getYear() > 9999)
                    || dateTo != null && (dateTo.getYear() < 1 || dateTo.getYear() > 9999)
                    || dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) invalid();
        }
        public boolean active() { return search != null || status != null || dateFrom != null || dateTo != null; }
    }
    public record DocumentKey(@NotNull UUID storeId, @NotNull UUID documentId) { }
    public record ExportRequest(@NotNull UUID customerId,
            @NotNull @Pattern(regexp = "tickets|invoices|delivery-notes") String reportKey,
            @Valid Filters filters, String sortBy, String sortDirection,
            List<@NotNull @Valid DocumentKey> documentKeys,
            @NotNull @Size(min = 1, max = 11) List<@NotNull @Valid Column> columns,
            @NotNull @Valid CustomerDocumentExportRequest.Labels labels) {
        public CustomerDocumentExportRequest presentation() {
            var value = filters == null ? new Filters(null, null, null, null) : filters;
            return new CustomerDocumentExportRequest(customerId, reportKey,
                    new CustomerDocumentExportRequest.Filters(value.search(), value.status(), value.dateFrom(), value.dateTo()),
                    null, null, null, columns, labels);
        }
    }
    public record CustomerProfile(UUID id, String code, String name, String taxId, String address) { }
    public record Row(String id, UUID storeId, String storeCode, UUID documentId, UUID installationId,
            long sourceRevision, UUID customerId, CommercialDocumentType type, DocumentStatus status,
            String number, LocalDate date, String currency, String subtotal, String taxTotal, String total,
            String terminalName, String userName) { }
    public record Page(UUID localCustomerId, UUID companyId, CustomerProfile customer, List<Row> items,
            String nextCursor, boolean hasMore, String coverage) {
        public Page { items = List.copyOf(items); }
    }

    static String sort(String value) {
        if (value == null) return "date";
        value = value.strip();
        if (!SORTS.contains(value)) invalid();
        return value;
    }
    static String direction(String value) {
        if (value == null) return "desc";
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("asc", "desc").contains(value)) invalid();
        return value;
    }
    static void invalid() { throw new IllegalArgumentException("Consulta documental central no válida"); }
}
