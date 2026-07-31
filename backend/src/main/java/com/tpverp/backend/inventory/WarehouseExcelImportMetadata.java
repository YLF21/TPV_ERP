package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record WarehouseExcelImportMetadata(
        @Size(max = 255) String fileName,
        @Size(max = 20_000) List<@Valid Formula> formulas) {

    public WarehouseExcelImportMetadata {
        fileName = optional(fileName, 255);
        formulas = formulas == null ? List.of() : formulas.stream().map(Formula::copy).toList();
    }

    public record Formula(
            @NotBlank @Size(max = 16) String cell,
            @NotBlank @Size(max = 4_000) String formula,
            @Size(max = 1_024) String calculatedValue) {

        static Formula copy(Formula value) {
            if (value == null) {
                throw new IllegalArgumentException("formula no puede ser nula");
            }
            return new Formula(
                    required(value.cell(), "cell", 16),
                    required(value.formula(), "formula", 4_000),
                    optional(value.calculatedValue(), 1_024));
        }
    }

    public static WarehouseExcelImportMetadata copy(WarehouseExcelImportMetadata value) {
        return value == null ? null : new WarehouseExcelImportMetadata(value.fileName(), value.formulas());
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        var normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " supera la longitud maxima");
        }
        return normalized;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException("valor supera la longitud maxima");
        }
        return normalized;
    }
}
