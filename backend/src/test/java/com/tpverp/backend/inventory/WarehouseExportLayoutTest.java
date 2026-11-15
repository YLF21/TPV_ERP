package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class WarehouseExportLayoutTest {
    @Test
    void keepsMultilineExcelTextAndWrapHeight() throws Exception {
        String text = "Primera línea\n" + "Descripción muy larga ".repeat(12) + "绿茶";
        byte[] bytes;
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("Almacén");
            var layout = WarehouseExportLayout.workbook(book, sheet, "DOCUMENTO DE ALMACÉN",
                    List.of(new WarehouseExportLayout.MetadataField("Tienda", "门店中央")),
                    List.of("Producto", "Cantidad"), new int[] {24, 16}, "zh");
            var row = sheet.createRow(layout.headerRow() + 1);
            row.createCell(0).setCellValue(text);
            row.getCell(0).setCellStyle(layout.text(false));
            row.createCell(1).setCellValue(1);
            row.getCell(1).setCellStyle(layout.number(false));
            WarehouseExportLayout.bodyRow(row, layout, 0);
            assertThat(row.getHeightInPoints()).isGreaterThan(30);
            var shortMultiline = sheet.createRow(layout.headerRow() + 2);
            shortMultiline.createCell(0).setCellValue("Uno\nDos\nTres");
            shortMultiline.getCell(0).setCellStyle(layout.text(true));
            WarehouseExportLayout.bodyRow(shortMultiline, layout, 1);
            assertThat(shortMultiline.getHeightInPoints()).isGreaterThanOrEqualTo(53);
            book.write(output);
            bytes = output.toByteArray();
        }
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(sheet.getPaneInformation().getHorizontalSplitPosition())
                    .getCell(0).getStringCellValue()).isEqualTo(text);
        }
        save("warehouse-layout-multiline.xlsx", bytes);
    }

    @Test
    void placesCjkMetadataOnFirstPageAndRepeatsHeadersWithPageTotals() throws Exception {
        var rows = IntStream.range(0, 95).mapToObj(i -> new WarehouseExportLayout.PdfRow(
                List.of("COD-" + i, "Descripción extensa de producto " + i + " 绿茶", "12,5"))).toList();
        byte[] bytes = WarehouseExportLayout.pdf(new WarehouseExportLayout.PdfSpec("INFORME DE ALMACÉN", "es",
                List.of(new WarehouseExportLayout.MetadataField("Tienda", "门店中央"),
                        new WarehouseExportLayout.MetadataField("Dirección", "Calle Mayor 1")),
                List.of("Código", "Producto", "Cantidad"), new float[] {100, 280, 80}, rows));
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            var first = new PDFTextStripper(); first.setStartPage(1); first.setEndPage(1);
            var second = new PDFTextStripper(); second.setStartPage(2); second.setEndPage(2);
            assertThat(first.getText(pdf)).contains("门店中央", "Calle Mayor 1", "Página 1 de " + pdf.getNumberOfPages());
            assertThat(second.getText(pdf)).contains("Código", "Producto", "Página 2 de " + pdf.getNumberOfPages())
                    .doesNotContain("门店中央", "Calle Mayor 1");
            assertThat(new PDFTextStripper().getText(pdf)).contains("COD-0", "COD-94", "绿茶");
        }
        save("warehouse-layout-cjk.pdf", bytes);
    }

    private static void save(String name, byte[] bytes) throws Exception {
        Path dir = Path.of("target", "warehouse-design");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), bytes);
    }
}
