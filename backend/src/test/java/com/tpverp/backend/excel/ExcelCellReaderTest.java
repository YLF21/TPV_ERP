package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelCellReaderTest {

    @Test
    void readsTextAndMoney() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue(" ABC ");
            row.createCell(1).setCellValue(12.345);

            assertThat(ExcelCellReader.text(row, "A")).isEqualTo("ABC");
            assertThat(ExcelCellReader.money(row, "B")).isEqualByComparingTo("12.35");
        }
    }

    @Test
    void emptyColumnReturnsNull() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);

            assertThat(ExcelCellReader.text(row, null)).isNull();
            assertThat(ExcelCellReader.money(row, "")).isNull();
        }
    }

    @Test
    void readsMoneyWithPercentSuffix() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("12,345%");
            row.createCell(1).setCellValue("12,34 %");

            assertThat(ExcelCellReader.money(row, "A")).isEqualByComparingTo("12.35");
            assertThat(ExcelCellReader.money(row, "B")).isEqualByComparingTo("12.34");
        }
    }

    @Test
    void readsInteger() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("42");

            assertThat(ExcelCellReader.integer(row, "A")).isEqualTo(42);
        }
    }

    @Test
    void rejectsDecimalInteger() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var row = workbook.createSheet().createRow(0);
            row.createCell(0).setCellValue("42.5");

            assertThatThrownBy(() -> ExcelCellReader.integer(row, "A"))
                    .isInstanceOf(ArithmeticException.class);
        }
    }
}
