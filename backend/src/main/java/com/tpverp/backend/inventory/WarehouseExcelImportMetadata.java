package com.tpverp.backend.inventory;

import jakarta.validation.Valid;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import java.util.Locale;

public record WarehouseExcelImportMetadata(
        @Size(max = 255) String fileName,
        @Size(max = 20_000) List<@Valid Formula> formulas,
        @Pattern(regexp = "[0-9a-fA-F]{64}") @Size(max = 64) String sha256,
        @Size(max = 255) String sheetName,
        boolean updateSupplier,
        boolean skipZeroPriceUpdate,
        @Size(max = 5_000) List<@Valid Line> lines) {

    private static final int EXCEL_CELL_TEXT_MAX_LENGTH = 32_767;

    public WarehouseExcelImportMetadata(String fileName, List<Formula> formulas) {
        this(fileName, formulas, null, null, false, false, List.of());
    }

    public WarehouseExcelImportMetadata {
        fileName = optional(fileName, 255);
        formulas = formulas == null ? List.of() : formulas.stream().map(Formula::copy).toList();
        long formulaCharacters = 0L;
        var formulaCells = new HashSet<String>();
        for (Formula formula : formulas) {
            formulaCharacters += formula.formula().length();
            if (formula.calculatedValue() != null) formulaCharacters += formula.calculatedValue().length();
            if (formulaCharacters > 5_000_000L) {
                throw new IllegalArgumentException("La metadata Excel supera 5.000.000 caracteres de formulas");
            }
            if (!formulaCells.add(formula.cell().trim().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("La metadata Excel contiene una celda formula duplicada");
            }
        }
        sha256 = optional(sha256, 64);
        if (sha256 != null && !sha256.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("sha256 debe contener 64 caracteres hexadecimales");
        }
        sheetName = optional(sheetName, 255);
        lines = lines == null ? List.of() : lines.stream().map(Line::copy).toList();
        if (lines.size() > 5_000) {
            throw new IllegalArgumentException("La metadata Excel supera 5.000 lineas");
        }
        int rowCount = lines.stream().mapToInt(line -> line.rowNumbers().size()).sum();
        if (rowCount > 5_000) {
            throw new IllegalArgumentException("La metadata Excel supera 5.000 filas originales");
        }
        var rows = new HashSet<Integer>();
        for (Line line : lines) {
            for (Integer row : line.rowNumbers()) {
                if (!rows.add(row)) {
                    throw new IllegalArgumentException("La metadata Excel contiene una fila asignada a varios productos");
                }
            }
        }
    }

    public record Line(
            @NotNull UUID productId,
            @NotNull @Size(min = 1, max = 5_000) List<Integer> rowNumbers,
            @Size(max = 128) String supplierReference,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossPurchasePrice,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal purchaseDiscountPercent) {

        public Line {
            if (productId == null) throw new IllegalArgumentException("linea de importacion sin producto");
            rowNumbers = normalizeRows(rowNumbers);
            supplierReference = optional(supplierReference, 128);
            grossPurchasePrice = monetary(grossPurchasePrice, "grossPurchasePrice", 17);
            purchaseDiscountPercent = percentage(purchaseDiscountPercent);
        }

        static Line copy(Line value) {
            if (value == null || value.productId() == null) {
                throw new IllegalArgumentException("linea de importacion sin producto");
            }
            return new Line(value.productId(), value.rowNumbers(), value.supplierReference(),
                    value.grossPurchasePrice(), value.purchaseDiscountPercent());
        }

        private static List<Integer> normalizeRows(List<Integer> values) {
            var rows = values == null ? List.<Integer>of() : values.stream()
                    .filter(Objects::nonNull)
                    .map(row -> {
                        if (row < 1) throw new IllegalArgumentException("rowNumber debe ser positivo");
                        return row;
                    })
                    .distinct().toList();
            if (rows.isEmpty()) throw new IllegalArgumentException("linea sin rowNumbers");
            if (rows.size() > 5_000) throw new IllegalArgumentException("Una linea supera 5.000 filas");
            return rows;
        }

        private static BigDecimal monetary(BigDecimal value, String field, int integerDigitsLimit) {
            if (value == null) return null;
            if (value.signum() < 0) throw new IllegalArgumentException(field + " no puede ser negativo");
            if (value.stripTrailingZeros().scale() > 3) throw new IllegalArgumentException(field + " no puede tener mas de 3 decimales");
            int integerDigits = Math.max(1, value.precision() - value.scale());
            if (integerDigits > integerDigitsLimit) {
                throw new IllegalArgumentException(field + " supera 17 digitos enteros");
            }
            return value;
        }

        private static BigDecimal percentage(BigDecimal value) {
            if (value == null) return null;
            if (value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("purchaseDiscountPercent debe estar entre 0 y 100");
            }
            if (value.scale() > 2) throw new IllegalArgumentException("purchaseDiscountPercent no puede tener mas de 2 decimales");
            int integerDigits = Math.max(1, value.precision() - value.scale());
            if (integerDigits > 3) throw new IllegalArgumentException("purchaseDiscountPercent supera 3 digitos enteros");
            return value;
        }
    }

    public record Formula(
            @NotBlank @Size(max = 16) String cell,
            @NotBlank @Size(max = EXCEL_CELL_TEXT_MAX_LENGTH) String formula,
            @Size(max = EXCEL_CELL_TEXT_MAX_LENGTH) String calculatedValue) {

        static Formula copy(Formula value) {
            if (value == null) {
                throw new IllegalArgumentException("formula no puede ser nula");
            }
            return new Formula(
                    required(value.cell(), "cell", 16),
                    required(value.formula(), "formula", EXCEL_CELL_TEXT_MAX_LENGTH),
                    optional(value.calculatedValue(), EXCEL_CELL_TEXT_MAX_LENGTH));
        }
    }

    public static WarehouseExcelImportMetadata copy(WarehouseExcelImportMetadata value) {
        return value == null ? null : new WarehouseExcelImportMetadata(
                value.fileName(), value.formulas(), value.sha256(), value.sheetName(),
                value.updateSupplier(), value.skipZeroPriceUpdate(), value.lines());
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
