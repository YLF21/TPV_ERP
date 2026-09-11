package com.tpverp.saas.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** M2M read contract. Local adapters must enforce the existing operator permissions. */
public final class CommercialDocumentApi {
    public static final String COVERAGE = "RECEIVED_V2_ONLY";
    private CommercialDocumentApi() { }

    public interface CustomerContext {
        UUID companyId();
        UUID storeId();
        UUID localCustomerId();
        UUID expectedCustomerId();
    }

    public record Filters(@Size(max = 120) String search, String status, LocalDate dateFrom, LocalDate dateTo) {
        boolean active() {
            return search != null && !search.isBlank() || status != null && !status.isBlank()
                    || dateFrom != null || dateTo != null;
        }
    }

    public record PageRequest(@NotNull UUID companyId, @NotNull UUID storeId,
            @NotNull UUID localCustomerId, @NotNull UUID expectedCustomerId,
            @NotNull @Pattern(regexp = "tickets|invoices|delivery-notes") String reportKey,
            @Valid Filters filters, String sortBy, String sortDirection,
            @Min(1) @Max(200) int size, @Size(max = 2048) String cursor) implements CustomerContext { }

    public record DocumentKey(@NotNull UUID storeId, @NotNull UUID documentId) { }

    public record ExportRequest(@NotNull UUID companyId, @NotNull UUID storeId,
            @NotNull UUID localCustomerId, @NotNull UUID expectedCustomerId,
            @NotNull @Pattern(regexp = "tickets|invoices|delivery-notes") String reportKey,
            @Valid Filters filters, String sortBy, String sortDirection,
            List<@NotNull @Valid DocumentKey> documentKeys) implements CustomerContext { }

    public record AnnualRequest(@NotNull UUID companyId, @NotNull UUID storeId,
            @NotNull UUID localCustomerId, @NotNull UUID expectedCustomerId,
            @Min(1) @Max(9998) int year) implements CustomerContext { }

    public record CustomerProfile(UUID id, String code, String name, String taxId, String address) { }
    public record IssuerProfile(UUID id, String name, String taxId, String address) { }

    /** Amounts are decimal strings; id is scoped and cannot be sent to local document command endpoints. */
    public record DocumentRow(String id, UUID storeId, String storeCode, UUID documentId, UUID installationId,
            long sourceRevision, UUID customerId, String type, String status, String number,
            LocalDate date, String currency, String subtotal, String taxTotal, String total,
            String terminalName, String userName) { }

    public record PageResponse(UUID companyId, CustomerProfile customer, List<DocumentRow> items,
            String nextCursor, boolean hasMore, String coverage) {
        public PageResponse { items = List.copyOf(items); }
    }
    public record CurrencyTotal(String currency, long documentCount, String subtotal, String taxTotal, String total) { }
    public record ExportResponse(UUID companyId, CustomerProfile customer, List<DocumentRow> items,
            List<CurrencyTotal> totals, String coverage) {
        public ExportResponse { items = List.copyOf(items); totals = List.copyOf(totals); }
    }
    public record Quarter(int number, String currency, long documentCount, String total) { }
    public record AnnualTotal(String currency, long documentCount, String total) { }
    public record AnnualResponse(UUID companyId, CustomerProfile customer, IssuerProfile issuer, int year,
            List<Quarter> quarters, List<AnnualTotal> totals, String coverage) {
        public AnnualResponse { quarters = List.copyOf(quarters); totals = List.copyOf(totals); }
    }
}
