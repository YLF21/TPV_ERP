package com.tpverp.backend.excel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;

final class ExcelCellReader {

    private static final DataFormatter FORMATTER = new DataFormatter();

    private ExcelCellReader() {
    }

    static String text(Row row, String column) {
        var cell = cell(row, column);
        if (cell == null) {
            return null;
        }
        var value = FORMATTER.formatCellValue(cell).trim();
        return value.isBlank() ? null : value;
    }

    static BigDecimal money(Row row, String column) {
        var value = text(row, column);
        if (value == null) {
            return null;
        }
        return new BigDecimal(value.replace("%", "").trim().replace(",", "."))
                .setScale(2, RoundingMode.HALF_UP);
    }

    static Integer integer(Row row, String column) {
        var value = text(row, column);
        return value == null ? null : new BigDecimal(value.replace(",", ".")).intValueExact();
    }

    private static Cell cell(Row row, String column) {
        if (row == null || column == null || column.isBlank()) {
            return null;
        }
        return row.getCell(ExcelColumn.index(column));
    }
}
