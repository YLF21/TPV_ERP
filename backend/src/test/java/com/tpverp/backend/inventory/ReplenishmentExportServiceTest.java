package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ReplenishmentExportServiceTest {
    private final ReplenishmentExportService service = new ReplenishmentExportService();

    private ReplenishmentView item(int index) {
        return new ReplenishmentView(UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(),
                "Almacén principal", "=COD-" + index, "0012345678905", "Té verde 绿茶 " + index,
                new BigDecimal("12.500"), index == 0 ? "=Motivo urgente" : "Reposición habitual",
                new BigDecimal("3.250"), new BigDecimal("7.800"), UUID.randomUUID(), "Proveedor",
                UUID.randomUUID(), "Bebidas", UUID.randomUUID(), "Tés", "ORDERED", LocalDate.of(2026, 11, 4));
    }

    @Test void excelPreservesTextAndNumericTypesAcrossLocales() throws Exception {
        for (String locale : List.of("es", "en", "zh")) {
            byte[] bytes = service.export("xlsx", locale, List.of(item(0)));
            try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                var row = book.getSheetAt(0).getRow(1);
                assertThat(book.getSheetAt(0).getRow(0).getLastCellNum()).isEqualTo((short) 13);
                assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(1).getStringCellValue()).isEqualTo("=COD-0");
                assertThat(row.getCell(2).getStringCellValue()).isEqualTo("0012345678905");
                assertThat(row.getCell(5).getCellType()).isEqualTo(CellType.STRING);
                assertThat(row.getCell(5).getStringCellValue()).isEqualTo("=Motivo urgente");
                assertThat(row.getCell(4).getNumericCellValue()).isEqualTo(12.5);
                assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(3.25);
                assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(7.8);
                assertThat(row.getCell(12).getStringCellValue()).isEqualTo("04/11/2026");
            }
        }
    }

    @Test void pdfRetainsUnicodeAndRepeatsHeadersAcrossPages() throws Exception {
        assertThat(ReplenishmentExportService.class.getResource("/fonts/NotoSansSC-VF.ttf")).isNotNull();
        var items = java.util.stream.IntStream.range(0, 85).mapToObj(this::item).toList();
        byte[] bytes = service.export("pdf", "zh", items);
        try (var pdf = Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(1);
            var text = new PDFTextStripper().getText(pdf);
            assertThat(text).contains("绿茶", "=COD-0", "=COD-84", "补货申请", "仓库");
            assertThat(text.split("补货申请", -1).length - 1).isEqualTo(pdf.getNumberOfPages());
        }
    }

    @Test void validatesFormatAndProducesEmptyReport() throws Exception {
        assertThatThrownBy(() -> service.export("csv", "es", List.of())).isInstanceOf(IllegalArgumentException.class);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export("xlsx", "es", List.of())))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isZero();
        }
        try (var pdf = Loader.loadPDF(service.export("pdf", "es", List.of()))) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test void leavesCostBlankWhenItIsHiddenFromTheUser() throws Exception {
        var source = item(1);
        var hidden = new ReplenishmentView(source.id(), source.version(), source.productId(), source.warehouseId(),
                source.warehouseName(), source.code(), source.barcode(), source.name(), source.quantity(),
                source.reason(), source.stock(), null, source.supplierId(), source.supplierName(),
                source.familyId(), source.familyName(), source.subfamilyId(), source.subfamilyName(),
                "PENDING", null);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(service.export("xlsx", "en", List.of(hidden))))) {
            var row = book.getSheetAt(0).getRow(1);
            assertThat(row.getCell(7).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(7).getStringCellValue()).isEmpty();
            assertThat(row.getCell(11).getStringCellValue()).isEqualTo("Pending");
        }
    }
}
