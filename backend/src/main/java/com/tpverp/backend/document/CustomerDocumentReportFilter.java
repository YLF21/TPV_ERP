package com.tpverp.backend.document;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;

public record CustomerDocumentReportFilter(
        String search,
        DocumentStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
        String sortBy,
        String sortDirection) {

    private static final Set<String> SORT_COLUMNS = Set.of(
            "number", "date", "type", "status", "base", "tax", "total", "terminal", "user");

    public CustomerDocumentReportFilter {
        search = search == null || search.isBlank() ? null : search.trim();
        if (search != null && search.length() > 120) {
            throw new IllegalArgumentException("La busqueda del documento no puede superar 120 caracteres");
        }
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new IllegalArgumentException("La fecha inicial no puede ser posterior a la final");
        }
        if (sortBy != null) {
            sortBy = sortBy.trim();
            if (!SORT_COLUMNS.contains(sortBy)) {
                throw new IllegalArgumentException("Columna de ordenacion de documentos no valida");
            }
        }
        if (sortDirection != null) {
            sortDirection = sortDirection.trim().toLowerCase(Locale.ROOT);
            if (!sortDirection.equals("asc") && !sortDirection.equals("desc")) {
                throw new IllegalArgumentException("Direccion de ordenacion de documentos no valida");
            }
        }
    }

    public boolean isRequested() {
        return search != null || status != null || dateFrom != null || dateTo != null
                || sortBy != null || sortDirection != null;
    }

    String effectiveSortBy() {
        return sortBy == null ? "date" : sortBy;
    }

    String effectiveDirection() {
        return sortDirection == null ? "desc" : sortDirection;
    }
}
