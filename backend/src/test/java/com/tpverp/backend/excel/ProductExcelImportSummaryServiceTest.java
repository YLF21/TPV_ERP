package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ProductExcelImportSummaryServiceTest {
    private final ProductExcelImportPreviewService preview = mock(ProductExcelImportPreviewService.class);
    private final ProductExcelImportSummaryService service = new ProductExcelImportSummaryService(preview);

    @Test
    void fullSummaryContainsExcelDatabaseAndBeforeAfterColumns() throws Exception {
        var row = row(false);
        when(preview.preview(any(), any())).thenReturn(result(row, List.of()));
        var exported = service.export(file(), request(false));
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var sheet = workbook.getSheetAt(0);
            var headers = sheet.getRow(0).cellIterator();
            List<String> values = new java.util.ArrayList<>();
            while (headers.hasNext()) values.add(headers.next().getStringCellValue());
            assertThat(values).containsExactly("Fila", "Estado", "Valores nuevos (Excel)", "Valores actuales (BD)", "Antes → Después");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Precio de compra cambiado");
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).contains("Precio de compra");
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).contains("=2+2");
            for (var sheetRow : sheet) for (var cell : sheetRow) assertThat(cell.toString()).doesNotContain("technical-secret-token");
            assertThat(exported.fileName()).isEqualTo("catalogo-resumen.xlsx");
        }
    }

    @Test
    void importedOnlyPhysicallyOmitsDatabaseAndBeforeAfterColumns() throws Exception {
        var sentinel = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("code", "A", "name", "Excel"), Map.of("name", "SECRET_DB"), 7L,
                Map.of("name", Map.of("before", "SECRET_BEFORE", "after", "Excel")), List.of(), false, "SECRET_TOKEN");
        when(preview.preview(any(), any())).thenReturn(result(sentinel, List.of()));
        var exported = service.export(file(), request(true));
        org.mockito.Mockito.verify(preview).preview(any(), org.mockito.ArgumentMatchers.argThat(config ->
                Boolean.FALSE.equals(config.options().showOnlyImported())));
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(List.of(header.getCell(0).getStringCellValue(), header.getCell(1).getStringCellValue(), header.getCell(2).getStringCellValue()))
                    .containsExactly("Fila", "Estado", "Valores nuevos (Excel)");
            for (var row : workbook.getSheetAt(0)) for (var cell : row) assertThat(cell.toString()).doesNotContain("SECRET_DB", "SECRET_BEFORE", "SECRET_TOKEN");
        }
    }

    @Test
    void endpointContractExportsOnlyTheExactPreviewFingerprint() {
        String fingerprint = "b".repeat(64);
        var source = new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(row(true)), 1, 1, 0,
                List.of(), List.of(), fingerprint);
        when(preview.preview(any(), any())).thenReturn(source);

        var exported = service.export(file(),
                new ProductExcelImportSummaryService.SummaryRequest(request(true), fingerprint), "es");
        assertThat(exported.bytes()).isNotEmpty();

        assertThatThrownBy(() -> service.export(file(),
                new ProductExcelImportSummaryService.SummaryRequest(request(true), "c".repeat(64)), "es"))
                .isInstanceOf(ProductExcelImportSummaryService.SummaryValidationException.class)
                .satisfies(exception -> assertThat(((ProductExcelImportSummaryService.SummaryValidationException) exception)
                        .errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .containsExactly("VERSION_STALE"));
        assertThatThrownBy(() -> service.export(file(),
                new ProductExcelImportSummaryService.SummaryRequest(request(true), null), "es"))
                .isInstanceOf(ProductExcelImportSummaryService.SummaryValidationException.class)
                .satisfies(exception -> assertThat(((ProductExcelImportSummaryService.SummaryValidationException) exception)
                        .errors()).extracting(ProductExcelImportPreviewService.ImportError::code)
                        .containsExactly("CONCURRENCY_TOKEN_REQUIRED"));
    }

    @Test
    void supportsEnglishVisibleLabelsWithoutChangingPreviewContract() throws Exception {
        when(preview.preview(any(), any())).thenReturn(result(row(true), List.of()));
        var exported = service.export(file(), request(true), "en");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var header = workbook.getSheetAt(0).getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Row");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Status");
            assertThat(header.getCell(2).getStringCellValue()).isEqualTo("New values (Excel)");
        }
    }

    @Test
    void supportsChineseHeadersAndSaneBoundedDownloadName() {
        var source = new ProductExcelImportPreviewService.PreviewResult("..\\" + "x".repeat(300) + "\r\n.xlsx", "a".repeat(64), "Hoja", List.of(row(true)), 1, 1, 0, List.of());
        when(preview.preview(any(), any())).thenReturn(source);
        var exported = service.export(file(), request(true), "zh");
        assertThat(exported.fileName()).doesNotContain("\\", "\r", "\n").hasSizeLessThan(140);
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("行");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(2).getStringCellValue()).isEqualTo("新值（Excel）");
            for (var row : workbook.getSheetAt(0)) for (var cell : row) assertThat(cell.toString()).doesNotContain("BD", "before");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @Test
    void localizesSummarySheetNameForEachSupportedLocale() throws Exception {
        for (List<String> localeAndName : List.of(
                List.of("es", "Resumen importación"),
                List.of("en", "Import summary"),
                List.of("zh", "导入摘要"))) {
            when(preview.preview(any(), any())).thenReturn(result(row(true), List.of()));
            var exported = service.export(file(), request(true), localeAndName.get(0));
            try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo(localeAndName.get(1));
            }
        }
    }

    @Test
    void rejectsUnsupportedOrOversizedLocalesBeforeGeneratingThePreview() {
        assertThatThrownBy(() -> service.export(file(), request(true), "fr"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).code())
                        .isEqualTo("LOCALE_INVALID"));
        assertThatThrownBy(() -> service.export(file(), request(true), "x".repeat(1_000_000)))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class)
                .satisfies(error -> assertThat(((ProductExcelImportReadService.ProductExcelImportException) error).receivedValue())
                        .hasSize(256));
        verifyNoInteractions(preview);
    }

    @Test
    void rowErrorsRemainVisibleButIntegrityErrorsBlockDownload() {
        var rowError = new ProductExcelImportPreviewService.ImportError("IDENTITY_REQUIRED", 2, 27, "code", "", "falta identidad", "code/barcode/name", "corrige");
        when(preview.preview(any(), any())).thenReturn(result(row(false, List.of(rowError)), List.of(rowError)));
        var exported = service.export(file(), request(false));
        assertThat(exported.bytes()).isNotEmpty();
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(5).getStringCellValue()).isEqualTo("Detalle del error");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).contains("IDENTITY_REQUIRED", "falta identidad", "Valor recibido", "AA");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), 0, 0, 0, List.of(
                        new ProductExcelImportPreviewService.ImportError("HASH_MISMATCH", null, null, null, "x", "archivo cambiado", "SHA-256", "vuelve a leer"))));
        assertThatThrownBy(() -> service.export(file(), request(false)))
                .isInstanceOf(ProductExcelImportSummaryService.SummaryValidationException.class);
    }

    @Test
    void localizesAdditionalFieldsAndErrorAttributes() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("EDIT_LIMIT", 2, 3,
                "comments", "nota", "invalid", "texto", "corrige");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR",
                Map.of("code", "A", "barcode2", "B2", "comments", "nota"), null, null,
                Map.of(), List.of(error), false, null);
        when(preview.preview(any(), any())).thenReturn(result(row, List.of(error)));

        var exported = service.export(file(), request(true), "en");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue())
                    .contains("Barcode 2: B2", "Comments: nota");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .contains("Attribute: Comments");
            assertThat(sheet.getRow(1).getCell(3).getStringCellValue())
                    .contains("There are too many edits", "Up to 250,000 edits", "Reduce the edits")
                    .doesNotContain("invalid", "texto", "corrige");
        }

        var zh = service.export(file(), request(true), "zh");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(zh.bytes()))) {
            var text = workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue();
            assertThat(text).contains("编辑过多", "最多 250,000 次编辑", "减少编辑", "属性: 备注")
                    .doesNotContain("invalid", "texto", "corrige");
        }
    }

    @Test
    void localizesNumericHardeningErrorsInEnglishAndChinese() throws Exception {
        var errors = List.of(
                new ProductExcelImportPreviewService.ImportError("ERROR_LIMIT", 2, 3, "comments", "x", "es", "es", "es"),
                new ProductExcelImportPreviewService.ImportError("NUMBER_FORMAT_UNSUPPORTED", 2, 3, "purchasePrice", "x", "es", "es", "es"),
                new ProductExcelImportPreviewService.ImportError("PERCENTAGE_FORMAT_NOT_ALLOWED", 2, 3, "quantity", "x", "es", "es", "es"),
                new ProductExcelImportPreviewService.ImportError("IDENTIFIER_NUMERIC_PRECISION", 2, 3, "code", "x", "es", "es", "es"));
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR",
                Map.of("code", "A"), null, null, Map.of(), errors, false, null);
        when(preview.preview(any(), any())).thenReturn(result(row, errors));

        var english = service.export(file(), request(true), "en");
        var chinese = service.export(file(), request(true), "zh");
        try (var en = new XSSFWorkbook(new java.io.ByteArrayInputStream(english.bytes()));
             var zh = new XSSFWorkbook(new java.io.ByteArrayInputStream(chinese.bytes()))) {
            String enText = en.getSheetAt(0).getRow(1).getCell(3).getStringCellValue();
            String zhText = zh.getSheetAt(0).getRow(1).getCell(3).getStringCellValue();
            assertThat(enText).contains("Some row error details were omitted", "numeric format is not supported",
                    "percentage format is not allowed", "safe precision");
            assertThat(zhText).contains("部分行错误详情已省略", "数字格式不受支持",
                    "不允许使用百分比格式", "安全精度");
        }
    }

    @Test
    void globalIntegrityErrorBlocksEvenWhenValidRowsArePresent() {
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(row(true)), 1, 1, 0, List.of(
                        new ProductExcelImportPreviewService.ImportError("FILE_CHANGED", null, null, null, "hash",
                                "archivo cambiado", "SHA-256", "vuelve a leer"))));
        assertThatThrownBy(() -> service.export(file(), request(true)))
                .isInstanceOf(ProductExcelImportSummaryService.SummaryValidationException.class);
    }

    @Test
    void startRowErrorWithoutRowsBlocksExport() {
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), 0, 0, 0, List.of(
                        new ProductExcelImportPreviewService.ImportError("START_ROW_INVALID", 999, null, null, "999",
                                "fila inicial invalida", "2..10", "corrige la fila"))));
        assertThatThrownBy(() -> service.export(file(), request(true)))
                .isInstanceOf(ProductExcelImportSummaryService.SummaryValidationException.class);
    }

    @Test
    void splitsLongSummaryCellsWithoutTruncatingOrBreakingSurrogatePairs() throws Exception {
        String longComments = "x".repeat(32_766) + "😀" + "tail";
        var source = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("comments", longComments), Map.of("id", UUID.randomUUID().toString()), 1L,
                Map.of("comments", Map.of("before", "old", "after", longComments)), List.of(), false, "token");
        when(preview.preview(any(), any())).thenReturn(result(source, List.of()));

        var exported = service.export(file(), request(false));
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isGreaterThan(1);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Fila");
            StringBuilder comments = new StringBuilder();
            for (var row : sheet) {
                if (row.getRowNum() == 0) continue;
                assertThat(row.getCell(0).getStringCellValue()).isEqualTo("2");
                assertThat(row.getCell(1).getStringCellValue()).isEqualTo("Existente");
                for (var cell : row) {
                    String text = cell.toString();
                    assertThat(text.length()).isLessThanOrEqualTo(32_767);
                    for (int index = 0; index < text.length(); index++) {
                        if (Character.isHighSurrogate(text.charAt(index))) {
                            assertThat(index + 1).isLessThan(text.length());
                            assertThat(Character.isLowSurrogate(text.charAt(++index))).isTrue();
                        } else {
                            assertThat(Character.isLowSurrogate(text.charAt(index))).isFalse();
                        }
                    }
                }
                if (row.getCell(2) != null) comments.append(row.getCell(2).getStringCellValue());
            }
            assertThat(comments.toString()).contains("Comentarios: ", longComments);
        }
    }

    @Test
    void exportsAllViewsFromSourceRowsWithoutRegroupingDuplicateOriginalRows() throws Exception {
        var first = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2, 3), "EXISTING",
                Map.of("code", "A", "purchasePrice", "10"), Map.of("code", "A", "purchasePrice", "9"), 1L,
                Map.of("purchasePrice", Map.of("before", "9", "after", "10")), List.of(), true, null);
        var missing = new ProductExcelImportPreviewService.PreviewRow(4, List.of(4), "ERROR",
                Map.of("code", "B"), null, null, Map.of(), List.of(new ProductExcelImportPreviewService.ImportError(
                        "IDENTIFIER_REQUIRED", 4, 1, "code", "", "missing", "code", "fix")), false, null);
        var source = new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(first, missing), 3, 1, 1, List.of(), List.of(), "b".repeat(64), List.of(
                        new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", first.excelData(), first.databaseData(), 1L, first.changes(), List.of(), true, null),
                        new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "EXISTING", first.excelData(), first.databaseData(), 1L, first.changes(), List.of(), true, null), missing));
        when(preview.preview(any(), any())).thenReturn(source);
        List<String> columns = List.of("rowNumber", "status", "excel.code", "current.code", "errors");
        for (String view : List.of("SUMMARY", "MISSING", "PURCHASE_CHANGED", "IMPORTABLE", "ERRORS")) {
            boolean only = "SUMMARY".equals(view);
            var request = new ProductExcelImportSummaryService.SummaryRequest(request(only), "b".repeat(64), view,
                    only ? List.of("rowNumber", "status", "excel.code", "errors") : columns);
            var exported = service.export(file(), request, "es");
            try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                var sheet = workbook.getSheetAt(0);
                assertThat((int) sheet.getRow(0).getLastCellNum()).isEqualTo(only ? 4 : 5);
                if ("SUMMARY".equals(view)) assertThat(sheet.getLastRowNum()).isEqualTo(3);
                if ("MISSING".equals(view)) assertThat(sheet.getLastRowNum()).isEqualTo(1);
                if ("PURCHASE_CHANGED".equals(view)) assertThat(sheet.getLastRowNum()).isEqualTo(2);
                if ("IMPORTABLE".equals(view)) assertThat(sheet.getLastRowNum()).isEqualTo(2);
                if ("ERRORS".equals(view)) assertThat(sheet.getLastRowNum()).isEqualTo(1);
            }
        }
    }

    @Test
    void rejectsCurrentColumnsForSummaryOnlyAndQuantityForOrganizedStock() {
        var summary = new ProductExcelImportSummaryService.SummaryRequest(request(true), "a".repeat(64), "SUMMARY",
                List.of("rowNumber", "current.code"));
        assertThatThrownBy(() -> service.export(file(), summary, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        var stockPreview = new ProductExcelImportPreviewService.PreviewRequest(request(false).mapping(), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), false, "STOCK", UUID.randomUUID(), UUID.randomUUID(), false, false),
                UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 2, null, Map.of());
        var stock = new ProductExcelImportSummaryService.SummaryRequest(stockPreview, "a".repeat(64), "MISSING", List.of("excel.quantity"));
        assertThatThrownBy(() -> service.export(file(), stock, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
    }

    @Test
    void importableContainsExistingErrorsButNeverMissingAndIgnoresSummaryOnlyOption() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("DATE_INVALID", 2, 3, "offerFrom", "31-02-26", "date", "valid", "correct");
        var existing = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", Map.of("code", "A"),
                Map.of("id", UUID.randomUUID().toString(), "code", "A"), 1L, Map.of(), List.of(error), false, null);
        var missing = new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "MISSING", Map.of("code", "B"), null, null, Map.of(), List.of(), false, null);
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(existing, missing), 2, 1, 1, List.of(), List.of(), "b".repeat(64)));
        var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(true), "b".repeat(64),
                "IMPORTABLE", List.of("rowNumber", "current.code", "excel.code", "errors")), "es");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("A");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue()).contains("DATE_INVALID");
        }
    }

    @Test
    void exportsGlobalValidationErrorsEvenWithoutDetectedRows() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("MAPPING_REQUIRED", null, null, null, null, "mapping", "columns", "assign");
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(), 0, 0, 0, List.of(error), List.of(), "b".repeat(64)));
        var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "b".repeat(64),
                "ERRORS", List.of("rowNumber", "errors")), "en");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).contains("MAPPING_REQUIRED");
        }
    }

    @Test
    void explicitViewsRequireColumnsAndRawValidatesLocaleBeforeReading() {
        assertThatThrownBy(() -> service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "MISSING", List.of()), "es"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        assertThatThrownBy(() -> service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "RAW", List.of()), "unknown"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        verifyNoInteractions(preview);
    }

    @Test
    void rejectsDuplicateColumnsAndUnknownViewsBeforePreview() {
        assertThatThrownBy(() -> service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "WHAT", List.of("rowNumber")), "es"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        assertThatThrownBy(() -> service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "SUMMARY", List.of("rowNumber", "rowNumber")), "es"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        assertThatThrownBy(() -> service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "SUMMARY", List.of("excel.unknown")), "es"))
                .isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        verifyNoInteractions(preview);
    }

    @Test
    void rawExportsEditedRowsWithoutMappingOrFingerprintAndPreservesFormulaResultAsText() throws Exception {
        when(preview.readRaw(any(), any())).thenReturn(new ProductExcelImportReadService.ReadResult("raw.xlsx", "c".repeat(64), "Sheet",
                List.of(List.of(new ProductExcelImportReadService.CellView("000000000000001", null), new ProductExcelImportReadService.CellView("=2+2", "=2+2"))),
                List.of(), 1, 2, 2));
        var rawService = new ProductExcelImportSummaryService(preview);
        var config = new ProductExcelImportSummaryService.SummaryRequest(new ProductExcelImportPreviewService.PreviewRequest(
                Map.of(), List.of(new ProductExcelImportPreviewService.CellEdit(1, "A", "000000000000001")), null,
                null, null, null, null, null, Map.of()), "not-required", "RAW", List.of("rowNumber"));
        var exported = rawService.export(file(), config, "en");
        org.mockito.Mockito.verify(preview).readRaw(any(), any());
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("000000000000001");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(1).getStringCellValue()).isEqualTo("=2+2");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
        }
    }

    @Test
    void localizesExplicitColumnHeadersAndErrorTextInEnglishAndChinese() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("IDENTIFIER_REQUIRED", 2, 1, "code", "", "raw spanish", "code", "fix");
        var source = new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", Map.of("code", "A"), null, null, Map.of(), List.of(error), false, null)),
                1, 0, 0, List.of(), List.of(), "a".repeat(64));
        when(preview.preview(any(), any())).thenReturn(source);
        for (String locale : List.of("en", "zh")) {
            var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "ERRORS",
                    List.of("rowNumber", "status", "excel.code", "errors")), locale);
            try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                String header = workbook.getSheetAt(0).getRow(0).getCell(2).getStringCellValue();
                String text = workbook.getSheetAt(0).getRow(1).getCell(3).getStringCellValue();
                assertThat(header).isEqualTo(locale.equals("en") ? "Code" : "编码");
                assertThat(text).contains("[IDENTIFIER_REQUIRED]").doesNotContain("raw spanish");
            }
        }
    }

    @Test
    void previewErrorTextsAreStableAcrossJsonAndXlsxForAllLocales() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("EDIT_LIMIT", 2, 3, "comments", "x", "backend", "accepted", "fix");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", Map.of("code", "A"), null, null, Map.of(), List.of(error), false, null);
        var result = new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet", List.of(row), 1, 0, 0, List.of(error), List.of(), "a".repeat(64));
        assertThat(row.errorTexts()).containsKeys("es", "en", "zh");
        assertThat(result.errorTexts()).containsKeys("es", "en", "zh");
        when(preview.preview(any(), any())).thenReturn(result);
        for (String locale : List.of("es", "en", "zh")) {
            var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "a".repeat(64), "ERRORS", List.of("errors")), locale);
            try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo(row.errorTexts().get(locale));
            }
        }
    }

    @Test
    void errorSnapshotExportsWithoutPreviewOrFileRead() throws Exception {
        var rows = List.of(Map.of("errors", "=HYPERLINK(\"https://example.invalid\")"));
        var request = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS",
                List.of("rowNumber", "status", "errors"), rows);
        var exported = service.export(new MockMultipartFile("file", "bad.xlsx", "application/octet-stream", new byte[] {1}), request, "es");
        assertThat(exported.bytes()).isNotEmpty();
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEmpty();
        }
        verifyNoInteractions(preview);
    }

    @Test
    void errorSnapshotRejectsOtherViewsInvalidLocaleContextAndUnexpectedColumns() {
        for (String view : List.of("RAW", "SUMMARY", "IMPORTABLE")) {
            var invalid = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, view, List.of("errors"), List.of());
            assertThatThrownBy(() -> service.export(file(), invalid, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        }
        var valid = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS", List.of("errors"), List.of());
        assertThatThrownBy(() -> service.export(file(), valid, "XX")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        var contextless = new ProductExcelImportSummaryService.SummaryRequest(null, null, "ERRORS", List.of("errors"), List.of());
        assertThatThrownBy(() -> service.export(file(), contextless, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        var unknown = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS", List.of("errors"), List.of(Map.of("current.id", "secret")));
        assertThatThrownBy(() -> service.export(file(), unknown, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        verifyNoInteractions(preview);
    }

    @Test
    void errorSnapshotLimitsRowsAndUtf8BytesButPreservesLongCellTextAcrossContinuationRows() throws Exception {
        var rows = java.util.Collections.nCopies(5_002, Map.of("errors", "x"));
        var tooManyRows = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS", List.of("errors"), rows);
        assertThatThrownBy(() -> service.export(file(), tooManyRows, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        var tooManyBytes = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS", List.of("errors"),
                List.of(Map.of("errors", "错".repeat(3_000_000))));
        assertThatThrownBy(() -> service.export(file(), tooManyBytes, "es")).isInstanceOf(ProductExcelImportReadService.ProductExcelImportException.class);
        String longError = "[ROW_INVALID] " + "x".repeat(33_000);
        var longCell = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS", List.of("rowNumber", "status", "errors"),
                List.of(Map.of("errors", longError)));
        var exported = service.export(file(), longCell, "en");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()
                    + workbook.getSheetAt(0).getRow(2).getCell(2).getStringCellValue()).isEqualTo(longError);
        }
        verifyNoInteractions(preview);
    }

    @Test
    void everyKnownImporterCodeHasLocalizedSummaryCopyWithoutDefaultFallback() throws Exception {
        var knownCodes = List.of(
                "FILE_EMPTY", "FILE_TOO_LARGE", "FILE_READ_FAILED", "FILE_EXTENSION_INVALID", "FILE_SIGNATURE_INVALID",
                "WORKBOOK_ENCRYPTED", "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE", "WORKBOOK_MACRO_UNSUPPORTED", "WORKBOOK_LIMIT", "SHEET_MISSING",
                "GRID_ROW_LIMIT", "GRID_CELL_LIMIT", "CELL_LIMIT", "CELL_TEXT_LIMIT", "TEXT_LIMIT", "FORMULA_LIMIT", "FORMULA_NO_CACHE",
                "CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR",
                "MAPPING_REQUIRED", "IDENTITY_MAPPING_REQUIRED", "MAPPING_FIELD_UNKNOWN", "COLUMN_INVALID", "COLUMN_NOT_FOUND", "COLUMN_LIMIT", "CONTRACT_LIMIT",
                "CONTEXT_REQUIRED", "CONTEXT_INVALID", "STORE_CONTEXT_MISMATCH", "COMPANY_CONTEXT_MISMATCH", "START_ROW_INVALID", "ROW_LIMIT", "NO_ROWS_DETECTED",
                "CELL_EDIT_INVALID", "EDIT_LIMIT", "EDIT_VALUE_LIMIT", "FIELD_LENGTH_INVALID", "IDENTIFIER_REQUIRED", "IDENTIFIER_DUPLICATE", "PRODUCT_AMBIGUOUS", "DUPLICATE_CONFLICT",
                "INVALID_PRICE_MODE", "INVALID_BOOLEAN", "PRODUCT_TYPE_INVALID", "DISCOUNT_PROHIBITED_PRICE_MODE", "ZERO_PRICE_INVALID", "NUMBER_INVALID", "NUMBER_FORMAT_AMBIGUOUS", "NUMBER_SCALE_INVALID", "NUMBER_PRECISION_INVALID", "NUMBER_FORMAT_UNSUPPORTED", "PERCENTAGE_FORMAT_NOT_ALLOWED", "IDENTIFIER_NUMERIC_PRECISION", "DATE_INVALID", "DATE_RANGE_INVALID",
                "OFFER_REQUIRED", "TAX_REQUIRED", "TAX_UNKNOWN", "TAX_AMBIGUOUS", "FAMILY_REQUIRED", "FAMILY_UNKNOWN", "FAMILY_AMBIGUOUS", "SUBFAMILY_UNKNOWN", "SUBFAMILY_AMBIGUOUS", "SUBFAMILY_FAMILY_MISMATCH", "NAME_REQUIRED", "QUANTITY_REQUIRED", "STOCK_RANGE_INVALID",
                "GLOBAL_VALUE_UNKNOWN", "VALUE_SOURCE_UNKNOWN", "VALUE_SOURCE_INVALID", "UPDATE_FIELD_UNKNOWN", "FILE_CHANGED", "HASH_REQUIRED", "TOKEN_LIMIT", "TOKEN_UNEXPECTED", "TOKEN_INVALID", "CONCURRENCY_TOKEN_REQUIRED", "VERSION_REQUIRED", "VERSION_STALE",
                "MISSING_REVIEW_REQUIRED", "CONFIRMATION_REQUIRED", "APPLY_CONTEXT_UNSUPPORTED", "APPLY_CONTEXT_INVALID", "APPLY_PROVENANCE_REQUIRED", "APPLY_REQUIRED_VALUE", "APPLY_OFFER_REQUIRED", "APPLY_TRANSACTION_FAILED", "SUMMARY_PREVIEW_INVALID", "PRODUCT_LOCAL_RESOLUTION_FAILED", "ROW_INVALID", "ERROR_LIMIT", "TRANSPORT_REQUEST_TOO_LARGE", "PERMISSION_DENIED", "PREVIEW_READ_FAILED");
        var errors = knownCodes.stream()
                .map(code -> new ProductExcelImportPreviewService.ImportError(code, 2, 27, "comments", "received", "backend reason", "accepted", "fix"))
                .toList();
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR",
                Map.of("code", "A"), null, null, Map.of(), errors, false, null);
        when(preview.preview(any(), any())).thenReturn(result(row, errors));

        for (String locale : List.of("en", "zh")) {
            var exported = service.export(file(), request(true), locale);
            try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                var text = new StringBuilder();
                for (var sheetRow : workbook.getSheetAt(0)) for (var cell : sheetRow) text.append(cell).append('\n');
                assertThat(text.toString()).doesNotContain("Could not validate import code", "无法验证导入代码");
            }
        }
    }

    private MockMultipartFile file() { return new MockMultipartFile("file", "catalogo.xlsx", "application/octet-stream", new byte[] {1}); }

    @Test
    void explicitSummaryMatchesUiColorsKeepsEmptyColumnsAndNeverRoundsSourceValues() throws Exception {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("name", "Excel", "purchasePrice", "1.234", "salePrice", "2.100", "productType", "2"),
                Map.of("name", "Actual", "purchasePrice", "1.100", "salePrice", "2.1", "productType", "WEIGHT"), 1L, Map.of(), List.of(), true, null);
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(row), 1, 1, 0, List.of(), List.of(), "b".repeat(64)));
        for (String view : List.of("SUMMARY", "IMPORTABLE")) {
            var columns = List.of("rowNumber", "current.name", "excel.name", "current.purchasePrice", "excel.purchasePrice", "excel.salePrice", "excel.comments", "excel.barcode2", "excel.productType");
            var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), "b".repeat(64), view, columns), "es");
            try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
                var sheet = book.getSheetAt(0);
                assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) columns.size());
                assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
                assertThat(sheet.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
                assertThat(sheet.getPaneInformation().getVerticalSplitPosition()).isEqualTo((short) 1);
                assertThat(fill(sheet.getRow(0).getCell(1))).isEqualTo("FF334D70");
                assertThat(fill(sheet.getRow(0).getCell(2))).isEqualTo("FF205B3B");
                assertThat(fill(sheet.getRow(1).getCell(1))).isEqualTo("FFEEF3FA");
                assertThat(fill(sheet.getRow(1).getCell(2))).isEqualTo("FFFFF0BD");
                assertThat(sheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("1.234");
                assertThat(book.getFontAt(sheet.getRow(1).getCell(4).getCellStyle().getFontIndex()).getBold()).isTrue();
                assertThat(book.getFontAt(sheet.getRow(1).getCell(5).getCellStyle().getFontIndex()).getBold()).isFalse();
                assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEmpty();
                assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEmpty();
                assertThat(sheet.getRow(1).getCell(8).getStringCellValue()).isEqualTo("2");
                assertThat(book.getFontAt(sheet.getRow(1).getCell(8).getCellStyle().getFontIndex()).getBold()).isFalse();
            }
        }
    }

    @Test
    void errorSnapshotUsesReadableFixedHeadersBordersAndErrorFillWithoutBusinessReads() throws Exception {
        var exported = service.export(file(), new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS",
                List.of("rowNumber", "errors"), List.of(Map.of("rowNumber", "69", "errors", "K69: #VALUE!"))), "es");
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(exported.bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(fill(sheet.getRow(0).getCell(0))).isEqualTo("FF263F63");
            assertThat(fill(sheet.getRow(1).getCell(1))).isEqualTo("FFFFF1F0");
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("K69: #VALUE!");
            assertThat(sheet.getRow(1).getCell(1).getCellStyle().getBorderBottom()).isEqualTo(org.apache.poi.ss.usermodel.BorderStyle.THIN);
            assertThat(sheet.getColumnWidth(1)).isEqualTo(18 * 256);
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
        }
        verifyNoInteractions(preview);
    }

    @Test
    void columnWidthsFollowLongestDataWithBothTextAttributesCappedAtFifty() throws Exception {
        var shortRow = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("name", "Corto", "description", "Corta", "purchasePrice", "1.234", "barcode2", "000000000000001"),
                Map.of("name", "BD", "description", "BD"), 1L, Map.of(), List.of(), true, null);
        String longName = "Nombre de producto muy largo para comprobar el ajuste de anchura y el texto completo. ".repeat(3);
        String longDescription = "Descripción extensa sin truncar, con saltos de línea y varias palabras. ".repeat(5);
        var longRow = new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "EXISTING",
                Map.of("name", longName, "description", longDescription), Map.of("name", "BD", "description", "BD"),
                1L, Map.of(), List.of(), true, null);
        var columns = List.of("rowNumber", "current.name", "excel.name", "current.description", "excel.description",
                "current.purchasePrice", "excel.purchasePrice", "excel.barcode2");
        for (String locale : List.of("es", "en", "zh")) {
            int shortNameWidth;
            when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                    List.of(shortRow), 1, 1, 0, List.of(), List.of(), "b".repeat(64)));
            var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), "b".repeat(64), "SUMMARY", columns);
            try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, locale).bytes()))) {
                var sheet = book.getSheetAt(0);
                shortNameWidth = sheet.getColumnWidth(2);
                assertThat(shortNameWidth).isLessThan(50 * 256);
                assertThat(sheet.getColumnWidth(4)).isLessThan(50 * 256);
                assertThat(sheet.getColumnWidth(5)).isEqualTo(12 * 256);
                assertThat(sheet.getColumnWidth(6)).isEqualTo(12 * 256);
                assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("1.234");
                assertThat(sheet.getRow(1).getCell(7).getStringCellValue()).isEqualTo("000000000000001");
            }
            when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                    List.of(shortRow, longRow), 2, 2, 0, List.of(), List.of(), "b".repeat(64)));
            try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, locale).bytes()))) {
                var sheet = book.getSheetAt(0);
                for (int column : List.of(1, 2, 3, 4)) assertThat(sheet.getColumnWidth(column)).isEqualTo(50 * 256);
                assertThat(sheet.getColumnWidth(2)).isGreaterThan(shortNameWidth);
                assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo(longName);
                assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo(longDescription);
                assertThat(sheet.getRow(2).getCell(4).getCellStyle().getWrapText()).isTrue();
                assertThat(sheet.getRow(2).getHeightInPoints()).isGreaterThan(24).isLessThanOrEqualTo(409.5f);
            }
        }
    }

    @Test
    void shortTextColumnsFitActualLongestDataInsteadOfTakingTheFiftyCharacterMaximum() throws Exception {
        String longest = "ESPONJAS PARA MAQUILLAJE";
        var rows = List.of(
                new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING", Map.of("name", "A", "description", "A"), Map.of(), 1L, Map.of(), List.of(), true, null),
                new ProductExcelImportPreviewService.PreviewRow(3, List.of(3), "EXISTING", Map.of("name", longest, "description", longest), Map.of(), 1L, Map.of(), List.of(), true, null));
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                rows, 2, 2, 0, List.of(), List.of(), "b".repeat(64)));
        var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), "b".repeat(64), "SUMMARY", List.of("excel.name", "excel.description"));
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, "es").bytes()))) {
            var sheet = book.getSheetAt(0);
            for (int column : List.of(0, 1)) {
                double measured = org.apache.poi.ss.util.SheetUtil.getColumnWidth(sheet, column, false, 1, 2);
                assertThat(sheet.getColumnWidth(column)).isEqualTo((int) Math.ceil((measured + 2) * 256));
                assertThat(sheet.getColumnWidth(column)).isBetween(8 * 256 + 1, 50 * 256 - 1);
            }
        }
    }

    @Test
    void rawExportFitsOriginalColumnsWithoutReorderingOrChangingText() throws Exception {
        String longText = "商品说明 con descripción extensa y varias palabras ".repeat(8);
        when(preview.readRaw(any(), any())).thenReturn(new ProductExcelImportReadService.ReadResult("raw.xlsx", "c".repeat(64), "Sheet",
                List.of(List.of(new ProductExcelImportReadService.CellView("Código", null), new ProductExcelImportReadService.CellView("Nombre", null)),
                        List.of(new ProductExcelImportReadService.CellView("000001", null), new ProductExcelImportReadService.CellView(longText, null))),
                List.of(), 2, 2, 4));
        var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), "unused", "RAW", List.of("rowNumber"));
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, "es").bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Código");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("000001");
            assertThat(sheet.getColumnWidth(0)).isLessThan(50 * 256);
            assertThat(sheet.getColumnWidth(1)).isEqualTo(50 * 256);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo(longText);
            assertThat(sheet.getRow(1).getHeightInPoints()).isGreaterThan(24);
        }
        verify(preview).readRaw(any(), any());
        verifyNoMoreInteractions(preview);
    }

    @Test
    void emptyExportRetainsRequestedColumnsAndUsableMinimumWidths() throws Exception {
        var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS",
                List.of("rowNumber", "current.name", "excel.name", "excel.description", "excel.barcode2", "errors"), List.of());
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, "es").bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 6);
            assertThat(sheet.getColumnWidth(1)).isEqualTo(8 * 256);
            assertThat(sheet.getColumnWidth(4)).isEqualTo(18 * 256);
        }
        verifyNoInteractions(preview);
    }

    @Test
    void layoutSupportsFiveThousandRowsWithSharedStylesAndDataAtTheEnd() throws Exception {
        var rows = java.util.stream.IntStream.rangeClosed(1, 5_000)
                .mapToObj(index -> Map.of("rowNumber", String.valueOf(index), "excel.code", "P" + index,
                        "current.name", "Actual " + index, "excel.name", "Producto " + index,
                        "excel.description", index == 5_000 ? "Última descripción larga. ".repeat(12) : "",
                        "excel.purchasePrice", "1.234", "excel.barcode2", "000000000000001"))
                .toList();
        var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), null, "ERRORS",
                List.of("rowNumber", "excel.code", "current.name", "excel.name", "excel.description", "excel.purchasePrice", "excel.barcode2"), rows);
        try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, "es").bytes()))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(5_000);
            assertThat(sheet.getColumnWidth(4)).isEqualTo(50 * 256);
            assertThat(sheet.getRow(5_000).getCell(4).getStringCellValue()).isEqualTo(rows.getLast().get("excel.description"));
            assertThat(sheet.getRow(5_000).getHeightInPoints()).isGreaterThan(24);
            assertThat(book.getNumCellStyles()).isLessThan(30);
        }
        verifyNoInteractions(preview);
    }

    @Test
    void identityAndDocumentQuantityHavePlainHeadersAndNoComparisonStyling() throws Exception {
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("code", "EXCEL-001", "barcode", "0000001234567", "quantity", "6", "name", "Nuevo"),
                Map.of("code", "BD-001", "barcode", "1234567", "quantity", "999", "name", "Actual"),
                1L, Map.of(), List.of(), false, null);
        when(preview.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet",
                List.of(row), 1, 1, 0, List.of(), List.of(), "b".repeat(64)));
        var columns = List.of("rowNumber", "excel.code", "excel.barcode", "excel.quantity", "current.name", "excel.name");
        for (String locale : List.of("es", "en", "zh")) {
            var headings = locale.equals("es") ? List.of("Código", "Código de barras", "Cantidad")
                    : locale.equals("en") ? List.of("Code", "Barcode", "Quantity") : List.of("编码", "条码", "数量");
            for (String view : List.of("SUMMARY", "IMPORTABLE")) {
                var config = new ProductExcelImportSummaryService.SummaryRequest(request(false), "b".repeat(64), view, columns);
                try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(service.export(file(), config, locale).bytes()))) {
                    var sheet = book.getSheetAt(0);
                    for (int column : List.of(1, 2, 3)) {
                        assertThat(sheet.getRow(0).getCell(column).getStringCellValue()).isEqualTo(headings.get(column - 1));
                        assertThat(fill(sheet.getRow(0).getCell(column))).isEqualTo("FF263F63");
                        assertThat(fill(sheet.getRow(1).getCell(column))).isEqualTo("FFFFFFFF");
                        assertThat(book.getFontAt(sheet.getRow(1).getCell(column).getCellStyle().getFontIndex()).getBold()).isFalse();
                    }
                    assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("EXCEL-001");
                    assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("0000001234567");
                    assertThat(sheet.getRow(1).getCell(3).getStringCellValue()).isEqualTo("6");
                    assertThat(fill(sheet.getRow(0).getCell(4))).isEqualTo("FF334D70");
                    assertThat(fill(sheet.getRow(0).getCell(5))).isEqualTo("FF205B3B");
                    assertThat(fill(sheet.getRow(1).getCell(5))).isEqualTo("FFFFF0BD");
                }
            }
        }
    }

    private String fill(org.apache.poi.ss.usermodel.Cell cell) {
        return ((org.apache.poi.xssf.usermodel.XSSFCellStyle) cell.getCellStyle()).getFillForegroundXSSFColor().getARGBHex();
    }

    private ProductExcelImportPreviewService.PreviewRequest request(boolean onlyImported) {
        return new ProductExcelImportPreviewService.PreviewRequest(Map.of("code", "A"), List.of(),
                new ProductExcelImportPreviewService.PreviewOptions(Map.of(), Map.of(), onlyImported, "WAREHOUSE_INPUT",
                        UUID.randomUUID(), UUID.randomUUID(), false, false), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), 2, null, Map.of());
    }

    private ProductExcelImportPreviewService.PreviewResult result(ProductExcelImportPreviewService.PreviewRow row,
            List<ProductExcelImportPreviewService.ImportError> errors) {
        return new ProductExcelImportPreviewService.PreviewResult("catalogo.xlsx", "a".repeat(64), "Hoja", List.of(row), 1, 1, 0, errors);
    }

    private ProductExcelImportPreviewService.PreviewRow row(boolean onlyImported) {
        return row(onlyImported, List.of());
    }

    private ProductExcelImportPreviewService.PreviewRow row(boolean onlyImported, List<ProductExcelImportPreviewService.ImportError> errors) {
        return new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "EXISTING",
                Map.of("code", "A", "name", "Excel", "purchasePrice", "=2+2"), Map.of("id", UUID.randomUUID().toString(), "name", "BD"),
                4L, Map.of("purchasePrice", Map.of("before", "1", "after", "2")), errors, true, "technical-secret-token");
    }
}
