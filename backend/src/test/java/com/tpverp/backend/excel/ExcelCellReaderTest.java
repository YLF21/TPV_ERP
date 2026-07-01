package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ExcelCellReaderTest {

    @Test
    void readsTextAndMoney() {
        var workbook = new XSSFWorkbook();
        var row = workbook.createSheet().createRow(0);
        row.createCell(0).setCellValue(" ABC ");
        row.createCell(1).setCellValue(12.345);

        assertThat(ExcelCellReader.text(row, "A")).isEqualTo("ABC");
        assertThat(ExcelCellReader.money(row, "B")).isEqualByComparingTo("12.35");
    }

    @Test
    void emptyColumnReturnsNull() {
        var workbook = new XSSFWorkbook();
        var row = workbook.createSheet().createRow(0);

        assertThat(ExcelCellReader.text(row, null)).isNull();
        assertThat(ExcelCellReader.money(row, "")).isNull();
    }
}
