package com.tpverp.backend.excel;

import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CustomerDocumentExportRequest(
        @NotNull UUID customerId,
        @NotBlank @Pattern(regexp = "tickets|invoices|delivery-notes") String reportKey,
        @Valid Filters filters,
        @Size(max = 40) String sortBy,
        @Size(max = 4) String sortDirection,
        List<@NotNull UUID> documentIds,
        @NotNull @Size(min = 1, max = 9) List<@NotNull @Valid Column> columns,
        @NotNull @Valid Labels labels) {

    public CustomerDocumentReportFilter queryFilter() {
        return new CustomerDocumentReportFilter(
                filters == null ? null : filters.search(),
                filters == null ? null : filters.status(),
                filters == null ? null : filters.dateFrom(),
                filters == null ? null : filters.dateTo(), sortBy, sortDirection);
    }

    @Schema(name = "CustomerDocumentExportFilters")
    public record Filters(
            @Size(max = 120) String search,
            DocumentStatus status,
            LocalDate dateFrom,
            LocalDate dateTo) {
    }

    @Schema(name = "CustomerDocumentExportColumn")
    public record Column(
            @NotBlank @Size(max = 40) String key,
            @NotBlank @Size(max = 100) String label) {
    }

    @Schema(name = "CustomerDocumentExportLabels")
    public record Labels(
            @NotBlank @Size(max = 100) String sheetName,
            @NotNull @Size(max = 4) Map<CommercialDocumentType, @NotBlank @Size(max = 100) String> types,
            @NotNull @Size(max = 6) Map<DocumentStatus, @NotBlank @Size(max = 100) String> statuses,
            @NotBlank @Size(max = 100) String customerCode,
            @NotBlank @Size(max = 100) String customerTaxId,
            @NotBlank @Size(max = 100) String customerName,
            @NotBlank @Size(max = 100) String grandTotal,
            @NotNull @Valid FilterLabels filters) {

        public Labels {
            // Older clients omit these labels; their existing request remains valid.
            customerCode = customerCode == null ? "Código del cliente" : customerCode;
            customerTaxId = customerTaxId == null ? "NIF" : customerTaxId;
            customerName = customerName == null ? "Nombre del cliente" : customerName;
            grandTotal = grandTotal == null ? "Total documentos" : grandTotal;
            filters = filters == null ? new FilterLabels(null, null, null, null, null, null) : filters;
        }

        public Labels(String sheetName, Map<CommercialDocumentType, String> types,
                Map<DocumentStatus, String> statuses) {
            this(sheetName, types, statuses, null, null, null, null, null);
        }

        public Labels(String sheetName, Map<CommercialDocumentType, String> types,
                Map<DocumentStatus, String> statuses, String customerCode, String customerTaxId,
                String customerName, String grandTotal) {
            this(sheetName, types, statuses, customerCode, customerTaxId, customerName, grandTotal, null);
        }
    }

    @Schema(name = "CustomerDocumentExportFilterLabels")
    public record FilterLabels(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 100) String search,
            @NotBlank @Size(max = 100) String status,
            @NotBlank @Size(max = 100) String dateFrom,
            @NotBlank @Size(max = 100) String dateTo,
            @NotBlank @Size(max = 100) String none) {
        public FilterLabels {
            title = title == null ? "Filtros" : title;
            search = search == null ? "Búsqueda" : search;
            status = status == null ? "Estado" : status;
            dateFrom = dateFrom == null ? "Desde" : dateFrom;
            dateTo = dateTo == null ? "Hasta" : dateTo;
            none = none == null ? "Sin filtros" : none;
        }
    }
}
