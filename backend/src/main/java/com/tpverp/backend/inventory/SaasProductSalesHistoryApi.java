package com.tpverp.backend.inventory;

import com.tpverp.backend.excel.StockSalesHistoryExportRequest.Column;
import com.tpverp.backend.excel.StockSalesHistoryExportRequest.Labels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class SaasProductSalesHistoryApi {
    private SaasProductSalesHistoryApi() { }
    public record PdfResponse(com.tpverp.backend.document.template.RenderedDocumentView.RenderedArtifact renderedPdf, String fileName) { }
    public record Filters(LocalDate from, LocalDate to, String status, List<UUID> storeIds,
            String sortBy, String sortDirection) { }
    public record ExportRequest(LocalDate from, LocalDate to, String status, @Size(max = 2000) List<UUID> storeIds,
            String sortBy, String sortDirection, @Pattern(regexp = "detail|comparison") String view,
            @NotNull @Size(min = 1, max = 12) List<@Valid Column> columns, @NotNull @Valid Labels labels,
            @Pattern(regexp = "es|en|zh") String locale, String comparisonSortBy, String comparisonSortDirection) {
        public Filters filters() { return new Filters(from, to, status, storeIds, sortBy, sortDirection); }
    }
}
