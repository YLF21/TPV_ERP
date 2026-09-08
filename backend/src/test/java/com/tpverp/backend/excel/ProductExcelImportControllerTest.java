package com.tpverp.backend.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.reset;

import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.shared.api.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.security.access.AccessDeniedException;

@WebMvcTest(ProductExcelImportController.class)
@Import({ProductExcelImportControllerTest.MethodSecurityConfiguration.class, ApiExceptionHandler.class})
class ProductExcelImportControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ProductExcelImportController controller;
    @Autowired private ApiExceptionHandler exceptionHandler;
    @MockitoBean private ProductExcelImportReadService reader;
    @MockitoBean private ProductExcelImportPreviewService previewService;
    @MockitoBean private ProductExcelImportApplyService applyService;
    @MockitoBean private ProductExcelImportSummaryService summaryService;
    @MockitoBean private AuditService audit;

    @Test
    void allowsWarehouseAndProductManagementReaders() throws Exception {
        when(reader.read(any())).thenReturn(new ProductExcelImportReadService.ReadResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), List.of(), 0, 0, 0));
        var file = new MockMultipartFile("file", "catalogo.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});

        mvc.perform(excelMultipart("/api/v1/product-excel-imports/read").file(file)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN"))
                        .with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/read").file(file)
                        .with(user("catalog").authorities(() -> "GESTION_PRODUCTO"))
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void forbidsUsersWithoutImportPermission() throws Exception {
        var file = new MockMultipartFile("file", "catalogo.xlsx", null, new byte[] {1});
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/read").file(file)
                        .with(user("sales"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void exposesStructuredReadErrors() throws Exception {
        when(reader.read(any())).thenThrow(new ProductExcelImportReadService.ProductExcelImportException(
                "FILE_SIGNATURE_INVALID", "La firma no corresponde a XLS/XLSX", null, null, null));
        var file = new MockMultipartFile("file", "catalogo.xlsx", null, new byte[] {1});

        mvc.perform(excelMultipart("/api/v1/product-excel-imports/read").file(file)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN"))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_SIGNATURE_INVALID"))
                .andExpect(jsonPath("$.reason").exists())
                .andExpect(jsonPath("$.recommendedFix").exists());
    }

    @Test
    void preservesReadCellAttributeAndBoundedReceivedValue() {
        var response = controller.importError(new ProductExcelImportReadService.ProductExcelImportException(
                "DATE_INVALID", "Fecha invalida", 5, 3, "cell", "-1.0", null));

        assertThat(response.getBody()).containsEntry("attribute", "cell")
                .containsEntry("receivedValue", "-1.0");
    }

    @Test
    void serializesLocalizedErrorCellsForRowsAndGlobalReview() throws Exception {
        var error = new ProductExcelImportPreviewService.ImportError("DATE_INVALID", 2, 3, "offerFrom", "31-02-26", "Fecha no válida", "DD-MM-AA", "Corrige fecha");
        var row = new ProductExcelImportPreviewService.PreviewRow(2, List.of(2), "ERROR", java.util.Map.of("code", "A"), null, null, java.util.Map.of(), List.of(error));
        when(previewService.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult("x.xlsx", "a".repeat(64), "Sheet", List.of(row), 1, 0, 0, List.of(error)));
        var file = new MockMultipartFile("file", "x.xlsx", null, new byte[] {1});
        var config = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"STOCK\"}}".getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(config)
                        .with(user("stock").authorities(() -> "GESTION_PRODUCTO")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].errorTexts.en").value(row.errorTexts().get("en")))
                .andExpect(jsonPath("$.errorTexts.zh").value(row.errorTexts().get("zh")));
    }

    @Test
    void rejectsOversizedMultipartWith413BeforeEveryImportOperation() throws Exception {
        var bytes = new byte[(int) ProductExcelImportReadService.MAX_FILE_BYTES + 1];
        var file = new MockMultipartFile("file", "catalogo.xlsx",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, bytes);
        var previewConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},\"startRow\":2}".getBytes());
        var applyConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},"
                        + "\"expectedSha256\":\"%s\",\"startRow\":2},\"expectedConcurrencyTokens\":{},"
                        + "\"autoAddMissing\":false,\"confirmMasterChanges\":false}").formatted("a".repeat(64)).getBytes());
        var summaryConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},"
                        + "\"startRow\":2},\"expectedPreviewFingerprint\":\"%s\"}").formatted("a".repeat(64)).getBytes());
        var authorized = user("warehouse").authorities(() -> "GESTION_ALMACEN");

        mvc.perform(excelMultipart("/api/v1/product-excel-imports/read").file(file)
                        .with(authorized)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"))
                .andExpect(jsonPath("$.acceptedValues").value("<= 10 MB"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(previewConfig)
                        .with(authorized).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(applyConfig)
                        .with(authorized).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/summary.xlsx").file(file).file(summaryConfig)
                        .with(authorized).with(csrf()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
        verifyNoInteractions(reader, previewService, applyService, summaryService);
    }

    @Test
    void everyReadErrorHasAcceptedValuesAndRecommendedFix() {
        for (String code : List.of("FILE_EMPTY", "FILE_TOO_LARGE", "FILE_READ_FAILED", "FILE_EXTENSION_INVALID",
                "FILE_SIGNATURE_INVALID", "WORKBOOK_ENCRYPTED", "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE",
                "WORKBOOK_MACRO_UNSUPPORTED", "SHEET_MISSING", "GRID_ROW_LIMIT", "COLUMN_LIMIT", "GRID_CELL_LIMIT",
                "CELL_LIMIT", "CELL_TEXT_LIMIT", "TEXT_LIMIT", "FORMULA_LIMIT", "FORMULA_NO_CACHE",
                "CELL_ERROR_VALUE", "FORMULA_RESULT_ERROR", "WORKBOOK_LIMIT", "DATE_INVALID")) {
            var response = controller.importError(new ProductExcelImportReadService.ProductExcelImportException(
                    code, "error", 2, 27, null));
            assertThat(response.getBody()).containsKey("acceptedValues").containsKey("recommendedFix");
            assertThat(response.getBody().get("acceptedValues")).isNotNull();
            assertThat(response.getBody().get("recommendedFix")).isNotNull();
        }
    }

    @Test
    void gridCellErrorAdvertisesTheActualMaterializedCellLimit() {
        var response = controller.importError(new ProductExcelImportReadService.ProductExcelImportException(
                "GRID_CELL_LIMIT", "error", 2, 27, null));

        assertThat(response.getBody()).containsEntry("acceptedValues", "Hasta 250.000 celdas materializadas");
    }

    @Test
    void mapsTransportSizeFailureToStructuredErrorAndAuditsIt() {
        var response = exceptionHandler.uploadTooLarge(new MaxUploadSizeExceededException(10L * 1024L * 1024L));
        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("code", "FILE_TOO_LARGE")
                .containsEntry("acceptedValues", "<= 10 MB");
        verify(audit).record(org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_READ"),
                org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(details ->
                        "FILE_TOO_LARGE".equals(details.get("code"))));
    }

    @Test
    void doesNotClassifyNonExcelMultipartAsAnExcelFailure() {
        var request = new MockHttpServletRequest("POST", "/api/v1/products/images");
        var response = exceptionHandler.uploadTooLarge(
                new MaxUploadSizeExceededException(10L * 1024L * 1024L), request);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isInstanceOf(org.springframework.http.ProblemDetail.class);
        assertThat(((org.springframework.http.ProblemDetail) response.getBody()).getProperties())
                .containsEntry("code", "PAYLOAD_TOO_LARGE");
        verifyNoInteractions(audit);
    }

    @Test
    void localizesNonExcelTransportFailure() {
        var request = new MockHttpServletRequest("POST", "/api/v1/products/images");
        request.addHeader("Accept-Language", "en");
        var response = exceptionHandler.uploadTooLarge(
                new MaxUploadSizeExceededException(10L * 1024L * 1024L), request);

        assertThat(((org.springframework.http.ProblemDetail) response.getBody()).getDetail())
                .isEqualTo("The request body exceeds the permitted limit");
    }

    @Test
    void localizesExcelTransportFailure() {
        var request = new MockHttpServletRequest("POST", "/api/v1/product-excel-imports/read");
        request.addHeader("Accept-Language", "zh");
        var response = exceptionHandler.uploadTooLarge(
                new MaxUploadSizeExceededException(10L * 1024L * 1024L), request);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
        assertThat(body.get("message")).isEqualTo("工作簿超过 10 MB");
        assertThat(body.get("recommendedFix")).isEqualTo("拆分工作簿或减小其大小");
    }

    @Test
    void mapsExcelPermissionFailureToStructured403AndAuditsIt() {
        var request = new MockHttpServletRequest("POST", "/api/v1/product-excel-imports/preview");
        var response = exceptionHandler.accessDenied(new AccessDeniedException("denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isInstanceOf(java.util.Map.class);
        java.util.Map<?, ?> body = (java.util.Map<?, ?>) response.getBody();
        assertThat(body.get("code")).isEqualTo("PERMISSION_DENIED");
        assertThat(body.get("attribute")).isEqualTo("context");
        verify(audit).record(org.mockito.ArgumentMatchers.eq("PRODUCT_EXCEL_IMPORT_PREVIEW"),
                org.mockito.ArgumentMatchers.eq(com.tpverp.backend.audit.AuditResult.FALLO),
                org.mockito.ArgumentMatchers.<java.util.Map<String, Object>>argThat(details ->
                        "PERMISSION_DENIED".equals(details.get("code"))));
    }

    @Test
    void acceptsMultipartPreviewConfigurationForBothManagementAuthorities() throws Exception {
        when(previewService.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), 0, 0, 0, List.of()));
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var stockConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"STOCK\"},\"startRow\":2}".getBytes());
        var warehouseConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},\"startRow\":2}".getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(warehouseConfig)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectedRows").value(0));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(stockConfig)
                        .with(user("catalog").authorities(() -> "GESTION_PRODUCTO"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectedRows").value(0));
    }

    @Test
    void previewRequiresContextSpecificPermissionAndAllowsAdmin() throws Exception {
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var stock = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"STOCK\"}}".getBytes());
        var input = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"}}".getBytes());
        when(previewService.preview(any(), any())).thenThrow(new AccessDeniedException("denied"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(stock)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(input)
                        .with(user("catalog").authorities(() -> "GESTION_PRODUCTO")).with(csrf()))
                .andExpect(status().isForbidden());
        reset(previewService);
        when(previewService.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), 0, 0, 0, List.of()));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(stock)
                        .with(user("catalog").authorities(() -> "GESTION_PRODUCTO")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(input)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isOk());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(input)
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void previewWithMissingOptionsReturnsStructuredBadRequestWithoutSpelServerError() throws Exception {
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var config = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                "{\"mapping\":{\"code\":\"A\"},\"options\":null}".getBytes());
        when(previewService.preview(any(), any())).thenReturn(new ProductExcelImportPreviewService.PreviewResult(
                "catalogo.xlsx", "a".repeat(64), "Hoja", List.of(), 0, 0, 0,
                List.of(new ProductExcelImportPreviewService.ImportError("CONTEXT_REQUIRED", null, null,
                        "context", null, "context required", "STOCK", "select context"))));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/preview").file(file).file(config)
                        .with(user("catalog").authorities(() -> "GESTION_PRODUCTO")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CONTEXT_REQUIRED"));
    }

    @Test
    void applyUsesWarehouseInputPermissionAndRejectsStockForWarehouseOnly() throws Exception {
        when(applyService.apply(any(), any())).thenReturn(new ProductExcelImportApplyService.ApplyResult(
                "catalogo.xlsx", "a".repeat(64), List.of(), List.of(), 1));
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var input = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},\"expectedSha256\":\"%s\"},\"expectedConcurrencyTokens\":{},\"autoAddMissing\":false,\"confirmMasterChanges\":true}").formatted("a".repeat(64)).getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(input)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.appliedCount").value(1));
        var stock = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"STOCK\"},\"expectedSha256\":\"%s\"},\"expectedConcurrencyTokens\":{},\"autoAddMissing\":false}").formatted("a".repeat(64)).getBytes());
        when(applyService.apply(any(), any())).thenThrow(new AccessDeniedException("denied"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(stock)
                        .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void applyMapsStructuredErrorCodesToConflictValidationAndServerStatuses() throws Exception {
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var input = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"},\"expectedSha256\":\"%s\"},\"expectedConcurrencyTokens\":{},\"autoAddMissing\":false,\"confirmMasterChanges\":true}").formatted("a".repeat(64)).getBytes());
        var stale = new ProductExcelImportApplyService.ApplyResult("catalogo.xlsx", "a".repeat(64), List.of(),
                List.of(new ProductExcelImportApplyService.ApplyError("VERSION_STALE", 2, 1, "name", "Nuevo",
                        "version obsoleta", "version", "previsualiza")), 0);
        when(applyService.apply(any(), any())).thenReturn(stale);
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(input)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].receivedValue").value("Nuevo"));
        var validation = new ProductExcelImportApplyService.ApplyResult("catalogo.xlsx", "a".repeat(64), List.of(),
                List.of(new ProductExcelImportApplyService.ApplyError("HASH_REQUIRED", null, null, null, null,
                        "hash requerido", "SHA-256", "previsualiza")), 0);
        when(applyService.apply(any(), any())).thenReturn(validation);
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(input)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isBadRequest());
        var transaction = new ProductExcelImportApplyService.ApplyResult("catalogo.xlsx", "a".repeat(64), List.of(),
                List.of(new ProductExcelImportApplyService.ApplyError("APPLY_TRANSACTION_FAILED", null, null, null, null,
                        "fallo", "lote", "reintenta")), 0);
        when(applyService.apply(any(), any())).thenReturn(transaction);
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/apply").file(file).file(input)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void summaryReturnsSafeXlsxDownloadAndUsesContextualPermission() throws Exception {
        when(summaryService.export(any(), any(ProductExcelImportSummaryService.SummaryRequest.class), any())).thenReturn(
                new ProductExcelImportSummaryService.ExportedSummary(new byte[] { 'P', 'K' }, "catalogo_resumen.xlsx"));
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var config = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"}},"
                        + "\"expectedPreviewFingerprint\":\"" + "a".repeat(64) + "\"}").getBytes());
        var locale = new MockMultipartFile("locale", "", MediaType.TEXT_PLAIN_VALUE, "en".getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/summary.xlsx").file(file).file(config).file(locale)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"catalogo_resumen.xlsx\""));
        var stockConfig = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"STOCK\"}},"
                        + "\"expectedPreviewFingerprint\":\"" + "a".repeat(64) + "\"}").getBytes());
        when(summaryService.export(any(), any(ProductExcelImportSummaryService.SummaryRequest.class), any()))
                .thenThrow(new AccessDeniedException("denied"));
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/summary.xlsx").file(file).file(stockConfig)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void summaryReturnsStructuredBadRequestWhenHashOrPreviewIntegrityFails() throws Exception {
        when(summaryService.export(any(), any(ProductExcelImportSummaryService.SummaryRequest.class), any()))
                .thenThrow(new ProductExcelImportSummaryService.SummaryValidationException(
                "catalogo.xlsx", "a".repeat(64), List.of(new ProductExcelImportPreviewService.ImportError(
                        "HASH_MISMATCH", null, null, null, "nuevo", "archivo cambiado", "SHA-256", "vuelve a leer"))));
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var config = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"}},"
                        + "\"expectedPreviewFingerprint\":\"" + "a".repeat(64) + "\"}").getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/summary.xlsx").file(file).file(config)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SUMMARY_PREVIEW_INVALID"))
                .andExpect(jsonPath("$.errors[0].receivedValue").value("nuevo"));
    }

    @Test
    void summaryReturnsConflictWhenTheVisiblePreviewIsStale() throws Exception {
        when(summaryService.export(any(), any(ProductExcelImportSummaryService.SummaryRequest.class), any()))
                .thenThrow(new ProductExcelImportSummaryService.SummaryValidationException(
                        "catalogo.xlsx", "a".repeat(64), List.of(new ProductExcelImportPreviewService.ImportError(
                                "VERSION_STALE", null, null, null, null, "cambio", "preview actual", "actualiza"))));
        var file = new MockMultipartFile("file", "catalogo.xlsx", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[] {1});
        var config = new MockMultipartFile("config", "config.json", MediaType.APPLICATION_JSON_VALUE,
                ("{\"preview\":{\"mapping\":{\"code\":\"A\"},\"options\":{\"context\":\"WAREHOUSE_INPUT\"}},"
                        + "\"expectedPreviewFingerprint\":\"" + "a".repeat(64) + "\"}").getBytes());
        mvc.perform(excelMultipart("/api/v1/product-excel-imports/summary.xlsx").file(file).file(config)
                .with(user("warehouse").authorities(() -> "GESTION_ALMACEN")).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].code").value("VERSION_STALE"));
    }

    private static org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder excelMultipart(String uri) {
        return multipart(uri).header(org.springframework.http.HttpHeaders.CONTENT_LENGTH, "1024");
    }

    @EnableMethodSecurity
    static class MethodSecurityConfiguration { }
}
