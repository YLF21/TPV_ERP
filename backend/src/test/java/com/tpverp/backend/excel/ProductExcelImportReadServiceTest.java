package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.tpverp.backend.audit.AuditService;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.hssf.record.DateWindow1904Record;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbookType;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProductExcelImportReadServiceTest {

    private final AuditService audit = mock(AuditService.class);
    private final ProductExcelImportReadService service = new ProductExcelImportReadService(audit);

    @Test
    void readsOnlyFirstXlsxSheetAndPreservesHashAndCachedCellValues() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var first = workbook.createSheet("Productos");
            first.createRow(0).createCell(0).setCellValue("Código");
            first.getRow(0).createCell(1).setCellValue("Fecha");
            first.createRow(1).createCell(0).setCellValue("A001");
            var formula = first.getRow(1).createCell(2);
            formula.setCellFormula("1+2");
            formula.getCTCell().setV("3");
            var date = first.getRow(1).createCell(1);
            date.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 9, 5)));
            CellStyle style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            date.setCellStyle(style);
            workbook.createSheet("Ignorada").createRow(0).createCell(0).setCellValue("NO");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var result = service.read(new MockMultipartFile("file", "C:/tmp/catalogo.xlsx", "text/plain", bytes));

        assertThat(result.sheetName()).isEqualTo("Productos");
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(1).get(0).value()).isEqualTo("A001");
        assertThat(result.rows().get(1).get(1).value()).isEqualTo("2026-09-05");
        assertThat(result.rows().get(1).get(2).value()).isEqualTo("3");
        assertThat(result.rows().get(1).get(2).formula()).isEqualTo("1+2");
        assertThat(result.formulas()).singleElement().satisfies(formula -> {
            assertThat(formula.formula()).isEqualTo("1+2");
            assertThat(formula.calculatedValue()).isEqualTo("3");
        });
        assertThat(result.nonEmptyRows()).isEqualTo(2);
        assertThat(result.sha256()).matches("[0-9a-f]{64}");
        assertThat(result.fileName()).isEqualTo("catalogo.xlsx");
    }

    @Test
    void normalizesNativeAndCachedBooleansToTheZeroOneImportContractWithoutCoercingText() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var row = workbook.createSheet("Productos").createRow(0);
                row.createCell(0).setCellValue(true);
                row.createCell(1).setCellValue(false);
                row.createCell(2).setCellValue("true");
                var cached = row.createCell(3);
                cached.setCellFormula("FALSE()");
                cached.setCellValue(true); // Deliberately different: the importer must never evaluate it.
                workbook.write(output);
                bytes = output.toByteArray();
            }
            var result = service.read(new MockMultipartFile("file", xlsx ? "boolean.xlsx" : "boolean.xls", null, bytes));
            assertThat(result.rows().getFirst()).extracting(ProductExcelImportReadService.CellView::value)
                    .containsExactly("1", "0", "true", "1");
            assertThat(result.formulas()).singleElement().satisfies(formula -> {
                assertThat(formula.formula()).isEqualTo("FALSE()");
                assertThat(formula.calculatedValue()).isEqualTo("1");
            });
        }
    }

    @Test
    void readsStreamingXlsxWithDataDescriptorsBeforePoiParsesWorkbook() throws Exception {
        byte[] bytes;
        try (var workbook = new SXSSFWorkbook(1); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Productos").createRow(0).createCell(0).setCellValue("Codigo");
            workbook.createSheet("Proveedores").createRow(0).createCell(0).setCellValue("Fila ID");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var result = service.read(new MockMultipartFile("file", "export.xlsx", null, bytes));

        assertThat(result.sheetName()).isEqualTo("Productos");
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("Codigo");
    }

    @Test
    void preservesRawNumericMeaningForXlsxAndXlsWhileKeepingDisplayIdentifiers() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("Productos");
                var row = sheet.createRow(0);
                row.createCell(0).setCellValue("001.234");
                row.createCell(1).setCellValue(1000d);
                row.createCell(2).setCellValue(1234567890.12d);
                row.createCell(3).setCellValue(0.2d);
                row.createCell(4).setCellValue(0.2d);
                row.createCell(5).setCellValue(0.2d);
                row.createCell(6).setCellValue(0.2d);
                String[] formats = {"0%", "0\\%", "0\"%\"", "0%%"};
                for (int index = 0; index < formats.length; index++) {
                    var style = workbook.createCellStyle();
                    style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(formats[index]));
                    row.getCell(index + 3).setCellStyle(style);
                }
                workbook.write(output);
                bytes = output.toByteArray();
            }
            var result = service.read(new MockMultipartFile("file", xlsx ? "raw.xlsx" : "raw.xls", null, bytes));
            var row = result.rows().get(0);
            assertThat(row.get(0).value()).isEqualTo("001.234");
            assertThat(row.get(1).rawNumeric()).isEqualTo("1000");
            assertThat(row.get(2).rawNumeric()).isEqualTo("1234567890.12");
            assertThat(row.get(3).rawNumeric()).isEqualTo("20");
            assertThat(row.get(4).rawNumeric()).isEqualTo("0.2");
            assertThat(row.get(5).rawNumeric()).isEqualTo("0.2");
            assertThat(row.get(6).rawNumeric()).isEqualTo("2000");
        }
    }

    @Test
    void rejectsUnsupportedPercentageMultiplierForXlsxAndXls() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var cell = workbook.createSheet("Productos").createRow(0).createCell(0);
                cell.setCellValue(0.2d);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("0%%%"));
                cell.setCellStyle(style);
                workbook.write(output);
                bytes = output.toByteArray();
            }
            assertThatThrownBy(() -> service.read(new MockMultipartFile("file", xlsx ? "unsupported.xlsx" : "unsupported.xls", null, bytes)))
                    .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                    .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                            .isEqualTo("NUMBER_FORMAT_UNSUPPORTED"));
        }
    }

    @Test
    void preservesNativeExcelErrorCellsForXlsxAndXlsWithReadableValueAndCoordinates() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var cell = workbook.createSheet("Productos").createRow(2).createCell(1);
                cell.setCellErrorValue(FormulaError.DIV0.getCode());
                workbook.write(output);
                bytes = output.toByteArray();
            }

            var result = service.read(new MockMultipartFile("file", xlsx ? "error.xlsx" : "error.xls", null, bytes));
            assertThat(result.rows().get(2).get(1).errorCode()).isEqualTo("CELL_ERROR_VALUE");
            assertThat(result.rows().get(2).get(1).value()).isEqualTo("#DIV/0!");
            assertThat(result.nonEmptyCells()).isEqualTo(1);
        }
    }

    @Test
    void preservesCachedFormulaErrorsForXlsxAndXlsAndContinuesWithoutEvaluatingDuringImport() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var cell = workbook.createSheet("Productos").createRow(1).createCell(2);
                cell.setCellFormula("1+2");
                cell.setCellErrorValue(FormulaError.VALUE.getCode()); // Valid formula, deliberately stale error cache.
                workbook.getSheetAt(0).createRow(2).createCell(0).setCellValue("AFTER-ERROR");
                workbook.write(output);
                bytes = output.toByteArray();
            }

            var result = service.read(new MockMultipartFile("file", xlsx ? "formula-error.xlsx" : "formula-error.xls", null, bytes));
            assertThat(result.rows().get(1).get(2).errorCode()).isEqualTo("FORMULA_RESULT_ERROR");
            assertThat(result.rows().get(1).get(2).value()).isEqualTo("#VALUE!");
            assertThat(result.rows().get(2).get(0).value()).isEqualTo("AFTER-ERROR");
            assertThat(result.formulas()).containsExactly(new ProductExcelImportReadService.FormulaView("C2", "1+2", "#VALUE!"));
        }
    }

    @Test
    void rejectsConditionalNumericFormatsForXlsxAndXls() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var cell = workbook.createSheet("Productos").createRow(3).createCell(4);
                cell.setCellValue(0.5d);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("[>1]0%;0.00"));
                cell.setCellStyle(style);
                workbook.write(output);
                bytes = output.toByteArray();
            }

            assertThatThrownBy(() -> service.read(new MockMultipartFile(
                    "file", xlsx ? "conditional.xlsx" : "conditional.xls", null, bytes)))
                    .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                    .satisfies(error -> {
                        var exception = (ProductExcelImportReadService.ProductExcelImportException) error;
                        assertThat(exception.code()).isEqualTo("NUMBER_FORMAT_UNSUPPORTED");
                        assertThat(exception.row()).isEqualTo(4);
                        assertThat(exception.column()).isEqualTo(5);
                        assertThat(exception.attribute()).isEqualTo("numberFormat");
                        assertThat(exception.receivedValue()).isEqualTo("[>1]0%;0.00");
                    });
        }
    }

    @Test
    void distinguishesLiteralEFormatsFromRealScientificNotationForXlsxAndXls() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var row = workbook.createSheet("Productos").createRow(0);
                row.createCell(0).setCellValue(123d);
                row.createCell(1).setCellValue(456d);
                row.createCell(2).setCellValue(123456d);
                String[] formats = {"\"REF-\"000", "\"EUR \"000", "0.00E+00"};
                for (int index = 0; index < formats.length; index++) {
                    var style = workbook.createCellStyle();
                    style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(formats[index]));
                    row.getCell(index).setCellStyle(style);
                }
                workbook.write(output);
                bytes = output.toByteArray();
            }

            var row = service.read(new MockMultipartFile(
                    "file", xlsx ? "scientific.xlsx" : "scientific.xls", null, bytes)).rows().getFirst();
            assertThat(row.get(0).value()).isEqualTo("REF-123");
            assertThat(row.get(1).value()).isEqualTo("EUR 456");
            assertThat(row.get(2).value()).isEqualTo("123456");
            assertThat(row).extracting(ProductExcelImportReadService.CellView::rawNumeric)
                    .containsExactly("123", "456", "123456");
        }
    }

    @Test
    void reusesOneSharedFormatAcrossTenThousandCellsForXlsxAndXls() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var sheet = workbook.createSheet("Productos");
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("0.00%"));
                for (int rowIndex = 0; rowIndex < 100; rowIndex++) {
                    var row = sheet.createRow(rowIndex);
                    for (int columnIndex = 0; columnIndex < 100; columnIndex++) {
                        var cell = row.createCell(columnIndex);
                        cell.setCellValue(0.25d);
                        cell.setCellStyle(style);
                    }
                }
                workbook.write(output);
                bytes = output.toByteArray();
            }

            var result = service.read(new MockMultipartFile(
                    "file", xlsx ? "shared-format.xlsx" : "shared-format.xls", null, bytes));
            assertThat(result.nonEmptyCells()).isEqualTo(10_000);
            assertThat(result.rows().get(99).get(99).rawNumeric()).isEqualTo("25");
            assertThat(result.rows().get(99).get(99).percentage()).isTrue();
        }
    }

    @Test
    void cachesOneStyleWithoutLosingItsPositiveNegativeAndZeroSections() throws Exception {
        for (boolean xlsx : new boolean[] {true, false}) {
            byte[] bytes;
            try (var workbook = xlsx ? new XSSFWorkbook() : new HSSFWorkbook();
                    var output = new ByteArrayOutputStream()) {
                var row = workbook.createSheet("Productos").createRow(0);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("0%;0.00;0%%"));
                for (int index = 0; index < 3; index++) row.createCell(index).setCellStyle(style);
                row.getCell(0).setCellValue(0.2d);
                row.getCell(1).setCellValue(-0.2d);
                row.getCell(2).setCellValue(0d);
                workbook.write(output);
                bytes = output.toByteArray();
            }

            var row = service.read(new MockMultipartFile(
                    "file", xlsx ? "sections.xlsx" : "sections.xls", null, bytes)).rows().getFirst();
            assertThat(row).extracting(ProductExcelImportReadService.CellView::rawNumeric)
                    .containsExactly("20", "-0.2", "0");
            assertThat(row).extracting(ProductExcelImportReadService.CellView::percentage)
                    .containsExactly(true, false, true);
        }
    }

    @Test
    void rejectsAPathologicalNumberFormatBeforeFormattingTheCell() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("Productos").createRow(0).createCell(0);
            cell.setCellValue(1d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(
                    "0" + "\"X\"".repeat(400)));
            cell.setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }

        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "long-format.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> {
                    var exception = (ProductExcelImportReadService.ProductExcelImportException) error;
                    assertThat(exception.code()).isEqualTo("NUMBER_FORMAT_UNSUPPORTED");
                    assertThat(exception.row()).isEqualTo(1);
                    assertThat(exception.column()).isEqualTo(1);
                    assertThat(exception.receivedValue()).hasSize(256);
                });
    }

    @Test
    void readsLegacyXlsThroughWorkbookFactory() throws Exception {
        byte[] bytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("Hoja").createRow(0);
            row.createCell(0).setCellValue("A001");
            row.createCell(1).setCellValue(8435693825937d);
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var result = service.read(new MockMultipartFile("file", "catalogo.xls", null, bytes));

        assertThat(result.sheetName()).isEqualTo("Hoja");
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("A001");
        assertThat(result.rows().get(0).get(1).value()).isEqualTo("8435693825937");
    }

    @Test
    void readsLegacyXlsWith1904DateWindowing() throws Exception {
        byte[] bytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var dateWindow = (DateWindow1904Record) workbook.getWorkbook()
                    .findFirstRecordBySid(DateWindow1904Record.sid);
            dateWindow.setWindowing((short) 1);
            var sheet = workbook.createSheet("Fechas");
            var date = sheet.createRow(0).createCell(0);
            date.setCellValue(1.0);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            date.setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "fechas.xls", null, bytes));
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("1904-01-02");
    }

    @Test
    void rejectsFictitiousExcel1900Serial60() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var date = workbook.createSheet("Fechas").createRow(0).createCell(0);
            date.setCellValue(60d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            date.setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "serial60.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("DATE_INVALID"));
    }

    @Test
    void rejectsFictitiousSerial60InCachedFormula() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var formula = workbook.createSheet("Fechas").createRow(0).createCell(0);
            formula.setCellFormula("1+59");
            formula.getCTCell().setV("60");
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            formula.setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "serial60-formula.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("DATE_INVALID"));
    }

    @Test
    void rejectsFractionalFictitiousSerialAndDateBeyondExcelMaximum() throws Exception {
        for (double serial : new double[] {60.5d, 2_958_466d}) {
            byte[] bytes;
            try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
                var date = workbook.createSheet("Fechas").createRow(0).createCell(0);
                date.setCellValue(serial);
                var style = workbook.createCellStyle();
                style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
                date.setCellStyle(style);
                workbook.write(output);
                bytes = output.toByteArray();
            }
            final byte[] workbookBytes = bytes;
            assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "invalid-date.xlsx", null, workbookBytes)))
                    .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                    .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                            .isEqualTo("DATE_INVALID"));
        }
    }

    @Test
    void readsXlsx1904DateWindowingAndLongNumericIdentifierExactly() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var workbookProperties = workbook.getCTWorkbook().getWorkbookPr();
            if (workbookProperties == null) workbookProperties = workbook.getCTWorkbook().addNewWorkbookPr();
            workbookProperties.setDate1904(true);
            var row = workbook.createSheet("Productos").createRow(0);
            row.createCell(0).setCellValue(123456789012345d);
            row.createCell(1).setCellValue(1d);
            row.createCell(2).setCellValue(987654321098765d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            row.getCell(1).setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "productos.xlsx", null, bytes));
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("123456789012345");
        assertThat(result.rows().get(0).get(1).value()).isEqualTo("1904-01-02");
        assertThat(result.rows().get(0).get(2).value()).isEqualTo("987654321098765");
    }

    @Test
    void readsXlsLongNumericIdentifierExactly() throws Exception {
        byte[] bytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet("Productos").createRow(0);
            row.createCell(0).setCellValue(123456789012345d);
            row.createCell(1).setCellValue(987654321098765d);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "productos.xls", null, bytes));
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("123456789012345");
        assertThat(result.rows().get(0).get(1).value()).isEqualTo("987654321098765");
    }

    @Test
    void rejectsNegativeNativeExcelDateWithCoordinates() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var date = workbook.createSheet("Fechas").createRow(4).createCell(2);
            date.setCellValue(-1d);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            date.setCellStyle(style);
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "negative-date.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> {
                    var exception = (ProductExcelImportReadService.ProductExcelImportException) error;
                    assertThat(exception.code()).isEqualTo("DATE_INVALID");
                    assertThat(exception.row()).isEqualTo(5);
                    assertThat(exception.column()).isEqualTo(3);
                    assertThat(exception.attribute()).isEqualTo("cell");
                    assertThat(exception.receivedValue()).isEqualTo("-1.0");
                });
    }

    @Test
    void auditsHashWhenExtensionIsInvalid() {
        byte[] bytes = "not-an-excel-file".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "catalogo.txt", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("FILE_EXTENSION_INVALID"));
        org.mockito.Mockito.verify(audit).record(
                org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_READ"),
                org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(details ->
                        "FILE_EXTENSION_INVALID".equals(details.get("code"))
                                && String.valueOf(details.get("sha256")).matches("[0-9a-f]{64}")));
    }

    @Test
    void rejectsMacroEnabledWorkbookEvenWhenRenamedToXlsx() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(XSSFWorkbookType.XLSM); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("A");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "macro-renamed.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_MACRO_UNSUPPORTED"));
    }

    @Test
    void reportsAnExplicitEmptyNumericFormulaCacheWithoutInventingZero() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var formula = workbook.createSheet("Hoja").createRow(0).createCell(0);
            formula.setCellFormula("1+2");
            formula.getCTCell().setV("");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "empty-numeric-cache.xlsx", null, bytes));
        assertThat(result.rows().get(0).get(0).errorCode()).isEqualTo("FORMULA_NO_CACHE");
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("=1+2");
        assertThat(result.formulas().get(0).calculatedValue()).isNull();
    }

    @Test
    void acceptsAnExplicitEmptyStringFormulaCache() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var formula = workbook.createSheet("Hoja").createRow(0).createCell(0);
            formula.setCellFormula("\"\"");
            formula.setCellValue("");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "empty-string-cache.xlsx", null, bytes));
        assertThat(result.formulas()).singleElement().satisfies(cached -> {
            assertThat(cached.formula()).isEqualTo("\"\"");
            assertThat(cached.calculatedValue()).isEmpty();
        });
    }

    @Test
    void rejectsRealEncryptedOfficeContainerAsEncrypted() throws Exception {
        byte[] plain;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("A");
            workbook.write(output);
            plain = output.toByteArray();
        }
        byte[] encrypted;
        try (var filesystem = new POIFSFileSystem(); var packageFile = OPCPackage.open(new ByteArrayInputStream(plain))) {
            EncryptionInfo encryption = new EncryptionInfo(EncryptionMode.standard);
            Encryptor encryptor = encryption.getEncryptor();
            encryptor.confirmPassword("test-password");
            try (var encryptedStream = encryptor.getDataStream(filesystem)) {
                packageFile.save(encryptedStream);
            }
            try (var output = new ByteArrayOutputStream()) {
                filesystem.writeFilesystem(output);
                encrypted = output.toByteArray();
            }
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "encrypted.xlsx", null, encrypted)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_ENCRYPTED"));
    }

    @Test
    void rejectsFalseSignaturesAndOversizedFilesBeforeOpeningWorkbook() {
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "empty.xlsx", null, new byte[0])))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .hasMessageContaining("vacio");

        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "evil.xlsx", null, "not excel".getBytes())))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .hasMessageContaining("firma");

        assertThatThrownBy(() -> service.read(new MockMultipartFile(
                "file", "large.xlsx", null, new byte[(int) ProductExcelImportReadService.MAX_FILE_BYTES + 1])))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .hasMessageContaining("10 MB");

        org.mockito.Mockito.verify(audit, org.mockito.Mockito.atLeast(3))
                .record(org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_READ"),
                        org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                        org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void rejectsUnsupportedExtensionEvenWhenPayloadHasAnOfficeSignature() {
        assertThatThrownBy(() -> service.read(new MockMultipartFile(
                "file", "macro.xlsm", null, new byte[] {0x50, 0x4b, 0x03, 0x04})))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("FILE_EXTENSION_INVALID"));
    }

    @Test
    void rejectsOfficePayloadRenamedToTheOtherSupportedExtension() throws Exception {
        byte[] xlsx;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("A");
            workbook.write(output);
            xlsx = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "renamed.xls", null, xlsx)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("FILE_SIGNATURE_INVALID"));

        byte[] xls;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("A");
            workbook.write(output);
            xls = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "renamed.xlsx", null, xls)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("FILE_SIGNATURE_INVALID"));
    }

    @Test
    void reportsFormulaWithoutCachedResultWithoutEvaluatingIt() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var formula = workbook.createSheet("Hoja").createRow(0).createCell(0);
            formula.setCellFormula("1+2");
            workbook.write(output);
            bytes = output.toByteArray();
        }

        var result = service.read(new MockMultipartFile("file", "formula.xlsx", null, bytes));
        assertThat(result.rows().get(0).get(0).errorCode()).isEqualTo("FORMULA_NO_CACHE");
        assertThat(result.rows().get(0).get(0).value()).isEqualTo("=1+2");
        assertThat(result.formulas().get(0).calculatedValue()).isNull();
    }

    @Test
    void acceptsTwentyThousandCachedFormulasButRejectsTheNextOne() throws Exception {
        byte[] accepted = formulaWorkbook(20_000);
        var result = service.read(new MockMultipartFile("file", "formulas.xlsx", null, accepted));
        assertThat(result.formulas()).hasSize(20_000);

        byte[] rejected = formulaWorkbook(20_001);
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "formulas-too-many.xlsx", null, rejected)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> {
                    var importError = (ProductExcelImportReadService.ProductExcelImportException) error;
                    assertThat(importError.code()).isEqualTo("FORMULA_LIMIT");
                    assertThat(importError.row()).isEqualTo(20_001);
                    assertThat(importError.column()).isEqualTo(1);
                });
    }

    private static byte[] formulaWorkbook(int count) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Formulas");
            for (int index = 0; index < count; index++) {
                var cell = sheet.createRow(index).createCell(0);
                cell.setCellFormula("1+2");
                cell.getCTCell().setV("3");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @Test
    void classifiesTruncatedWorkbookWithValidZipSignatureAsCorrupt() {
        byte[] truncated = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "truncated.xlsx", null, truncated)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_CORRUPT"));
        org.mockito.Mockito.verify(audit).record(
                org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_READ"),
                org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(details -> "WORKBOOK_CORRUPT".equals(details.get("code"))));
    }

    @Test
    void rejectsCellTextLongerThanExcelLimitWithCoordinates() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var cell = workbook.createSheet("Hoja").createRow(0).createCell(0);
            cell.setCellValue("x");
            workbook.write(output);
            bytes = replaceFirstCellWithOversizedInlineString(output.toByteArray());
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "long.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> {
                    var value = (ProductExcelImportReadService.ProductExcelImportException) error;
                    assertThat(value.code()).isEqualTo("CELL_TEXT_LIMIT");
                    assertThat(value.row()).isEqualTo(1);
                    assertThat(value.column()).isEqualTo(1);
                });
    }

    @Test
    void rejectsAggregateMaterializedTextBeforeReturningAnOversizedResponse() throws Exception {
        byte[] bytes;
        String value = "x".repeat(3_000);
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Hoja");
            for (int row = 0; row < 2_000; row++) {
                sheet.createRow(row).createCell(0).setCellValue(value);
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "text.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("TEXT_LIMIT"));
    }

    @Test
    void acceptsExactlyOneHundredThousandGridRows() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Hoja");
            sheet.createRow(ProductExcelImportReadService.MAX_GRID_ROWS - 1).createCell(0).setCellValue("A");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        var result = service.read(new MockMultipartFile("file", "rows.xlsx", null, bytes));
        assertThat(result.rows()).hasSize(ProductExcelImportReadService.MAX_GRID_ROWS);
    }

    @Test
    void rejectsOneRowBeyondGridRowLimit() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja")
                    .createRow(ProductExcelImportReadService.MAX_GRID_ROWS)
                    .createCell(0)
                    .setCellValue("A");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "rows-too-many.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("GRID_ROW_LIMIT"));
    }

    @Test
    void rejectsVbaStorageInAnOtherwiseValidLegacyWorkbook() throws Exception {
        byte[] workbookBytes;
        try (var workbook = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Hoja").createRow(0).createCell(0).setCellValue("A");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }
        try (var filesystem = new POIFSFileSystem(new ByteArrayInputStream(workbookBytes));
                var output = new ByteArrayOutputStream()) {
            filesystem.getRoot().createDocument("_VBA_PROJECT_CUR",
                    new ByteArrayInputStream(new byte[] {1, 2, 3}));
            filesystem.writeFilesystem(output);
            workbookBytes = output.toByteArray();
        }
        final byte[] macroBytes = workbookBytes;
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "macro.xls", null, macroBytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_MACRO_UNSUPPORTED"));
    }

    @Test
    void rejectsZipExpansionBeforePoiParsesTheWorkbook() throws Exception {
        byte[] bytes;
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            byte[] chunk = new byte[1024 * 1024];
            long remaining = ProductExcelImportReadService.MAX_ZIP_ENTRY_BYTES + 1L;
            while (remaining > 0) {
                int length = (int) Math.min(chunk.length, remaining);
                zip.write(chunk, 0, length);
                remaining -= length;
            }
            zip.closeEntry();
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "bomb.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_LIMIT"));
    }

    @Test
    void rejectsZipWithTooManyEntriesBeforePoiParsesTheWorkbook() throws Exception {
        byte[] bytes;
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            for (int index = 0; index <= ProductExcelImportReadService.MAX_ZIP_ENTRIES; index++) {
                zip.putNextEntry(new ZipEntry("xl/entry" + index + ".xml"));
                zip.closeEntry();
            }
            bytes = output.toByteArray();
        }
        assertThatThrownBy(() -> service.read(new MockMultipartFile("file", "many-entries.xlsx", null, bytes)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("WORKBOOK_LIMIT"));
    }

    private static byte[] replaceFirstCellWithOversizedInlineString(byte[] workbook) throws Exception {
        var oversized = "<t>"
                + "x".repeat(ProductExcelImportReadService.MAX_CELL_CHARACTERS + 1)
                + "</t>";
        try (var input = new ZipInputStream(new ByteArrayInputStream(workbook));
                var output = new ByteArrayOutputStream();
                var zip = new ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                zip.putNextEntry(new ZipEntry(entry.getName()));
                var content = input.readAllBytes();
                if ("xl/sharedStrings.xml".equals(entry.getName())) {
                    var xml = new String(content, StandardCharsets.UTF_8);
                    var patched = xml.replaceFirst("<t>x</t>", oversized);
                    if (xml.equals(patched)) throw new IllegalStateException("shared string fixture was not found");
                    xml = patched;
                    content = xml.getBytes(StandardCharsets.UTF_8);
                }
                zip.write(content);
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        }
    }
}
