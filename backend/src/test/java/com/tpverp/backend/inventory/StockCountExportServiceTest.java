package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class StockCountExportServiceTest {
    @Test
    void exportsPendingCountsAsBlankAndExplicitZeroAsZero() throws Exception {
        var counts = mock(StockCountService.class);
        var id = UUID.randomUUID();
        var pending = new StockCountView.Line(UUID.randomUUID(), "PENDING", null, "Pendiente",
                BigDecimal.TEN, null, null, null);
        var zero = new StockCountView.Line(UUID.randomUUID(), "ZERO", null, "Cero",
                BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN.negate(), null);
        when(counts.get(id)).thenReturn(new StockCountView(id, "INV-2026-000001", UUID.randomUUID(), UUID.randomUUID(),
                StockCountStatus.DRAFT, null, UUID.randomUUID(), Instant.now(), null, null,
                null, null, List.of(pending, zero), 0, java.time.LocalDate.of(2026, 8, 3), "ADMIN"));
        var exports = new StockCountExportService(counts);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.excel(id)))) {
            var sheet = workbook.getSheet("Inventario");
            assertThat(sheet.getRow(5).getCell(4).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(5).getCell(5).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(6).getCell(4).getNumericCellValue()).isZero();
        }
        try (var pdf = Loader.loadPDF(exports.pdf(id))) {
            assertThat(new PDFTextStripper().getText(pdf)).contains("PENDING", "ZERO", "-10");
        }
    }

    @Test
    void exportsNeutralInventoryPdfAndExcelWithDifferences() throws Exception {
        var counts = mock(StockCountService.class);
        var id = UUID.randomUUID();
        var line = new StockCountView.Line(UUID.randomUUID(), "CAF", "8430000000001", "Café 茶",
                new BigDecimal("10.000"), new BigDecimal("8.000"), new BigDecimal("-2.000"), null);
        when(counts.get(id)).thenReturn(new StockCountView(id, "INV-2026-000001", UUID.randomUUID(), UUID.randomUUID(),
                StockCountStatus.DRAFT, null, UUID.randomUUID(), Instant.now(), null, null,
                null, null, List.of(line), 0, java.time.LocalDate.of(2026, 8, 3), "ADMIN"));
        var exports = new StockCountExportService(counts);

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exports.excel(id)))) {
            var sheet = workbook.getSheet("Inventario");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("CAF");
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("8430000000001");
            assertThat(sheet.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(-2d);
        }
        try (var pdf = Loader.loadPDF(exports.pdf(id))) {
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("INVENTARIO", "CAF", "8430000000001", "-2");
            var windows = System.getenv("WINDIR");
            if (Files.isRegularFile(Path.of(windows == null ? "C:/Windows" : windows,
                    "Fonts", "NotoSansSC-VF.ttf"))
                    || Files.isRegularFile(Path.of("/usr/share/fonts/truetype/noto/NotoSansSC-Regular.ttf"))) {
                assertThat(text).contains("茶");
            }
        }
    }
}
