package com.tpverp.backend.excel;

import com.tpverp.backend.document.DocumentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record StockSalesHistoryExportRequest(
        LocalDate from,
        LocalDate to,
        DocumentStatus status,
        @NotNull @Valid Labels labels,
        @Size(min = 1, max = 11) List<@Valid Column> columns) {

    public record Labels(
            @NotBlank @Size(max = 100) String title,
            @NotBlank @Size(max = 100) String product,
            @NotBlank @Size(max = 100) String code,
            @NotBlank @Size(max = 100) String period,
            @NotBlank @Size(max = 100) String status,
            @NotBlank @Size(max = 100) String allStatuses,
            @NotBlank @Size(max = 100) String totalQuantity,
            @NotBlank @Size(max = 100) String totalAmount) {
    }

    public record Column(
            @NotBlank @Size(max = 40) String key,
            @NotBlank @Size(max = 100) String label) {
    }
}
