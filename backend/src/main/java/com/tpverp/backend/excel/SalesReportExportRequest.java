package com.tpverp.backend.excel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SalesReportExportRequest(
        @NotBlank String reportKey,
        @Valid Filters filters,
        @Size(max = 200) String search,
        @Size(min = 1, max = 30) List<@Valid Column> columns) {

    public record Filters(
            String dateFrom,
            String dateTo,
            String user,
            String customer,
            String supplier,
            String payment,
            String terminal,
            String status,
            String warehouse,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> users,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> customers,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> suppliers,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> payments,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> terminals,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> statuses,
            @Size(max = 200) List<@NotBlank @Size(max = 200) String> warehouses) {
        public Filters(String dateFrom, String dateTo, String user, String customer, String supplier,
                String payment, String terminal, String status, String warehouse) {
            this(dateFrom, dateTo, user, customer, supplier, payment, terminal, status, warehouse,
                    null, null, null, null, null, null, null);
        }
    }

    public record Column(
            @NotBlank @Size(max = 40) String key,
            @NotBlank @Size(max = 100) String label) {
    }
}
