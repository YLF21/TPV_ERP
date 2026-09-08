package com.tpverp.backend.excel;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/product-excel-imports")
public class ProductExcelImportController {

    private final ProductExcelImportReadService reader;
    private final ProductExcelImportPreviewService previewService;
    private final ProductExcelImportApplyService applyService;
    private final ProductExcelImportSummaryService summaryService;
    public ProductExcelImportController(ProductExcelImportReadService reader,
            ProductExcelImportPreviewService previewService, ProductExcelImportApplyService applyService,
            ProductExcelImportSummaryService summaryService) {
        this.reader = reader;
        this.previewService = previewService;
        this.applyService = applyService;
        this.summaryService = summaryService;
    }

    @PostMapping(value = "/read", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN')")
    public ProductExcelImportReadService.ReadResult read(@RequestPart("file") MultipartFile file) {
        rejectOversized(file);
        return reader.read(file);
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN')")
    public ResponseEntity<ProductExcelImportPreviewService.PreviewResult> preview(
            @RequestPart("file") MultipartFile file,
            @RequestPart("config") ProductExcelImportPreviewService.PreviewRequest config) {
        rejectOversized(file);
        ProductExcelImportPreviewService.PreviewResult result = previewService.preview(file, config);
        boolean contractError = result.errors().stream().anyMatch(error ->
                "CONTEXT_REQUIRED".equals(error.code()) || "CONTEXT_INVALID".equals(error.code()));
        return ResponseEntity.status(contractError ? HttpStatus.BAD_REQUEST : HttpStatus.OK).body(result);
    }

    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN')")
    public ResponseEntity<ProductExcelImportApplyService.ApplyResult> apply(
            @RequestPart("file") MultipartFile file,
            @RequestPart("config") ProductExcelImportApplyService.ApplyRequest config) {
        rejectOversized(file);
        ProductExcelImportApplyService.ApplyResult result = applyService.apply(file, config);
        HttpStatus status = result.errors().stream().anyMatch(error -> "VERSION_STALE".equals(error.code()))
                ? HttpStatus.CONFLICT
                : result.errors().stream().anyMatch(error -> "APPLY_TRANSACTION_FAILED".equals(error.code()))
                        ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.OK;
        if (!result.errors().isEmpty() && status == HttpStatus.OK) status = HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(result);
    }

    @PostMapping(value = "/summary.xlsx", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN')")
    public ResponseEntity<?> summary(
            @RequestPart("file") MultipartFile file,
            @RequestPart("config") ProductExcelImportSummaryService.SummaryRequest config,
            @RequestPart(value = "locale", required = false) String locale) {
        rejectOversized(file);
        ProductExcelImportSummaryService.ExportedSummary exported = summaryService.export(file, config, locale);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(exported.contentType()))
                .header("Content-Disposition", "attachment; filename=\"" + exported.fileName() + "\"")
                .body(exported.bytes());
    }

    private static void rejectOversized(MultipartFile file) {
        if (file != null && file.getSize() > ProductExcelImportReadService.MAX_FILE_BYTES) {
            throw new ProductExcelImportReadService.ProductExcelImportException(
                    "FILE_TOO_LARGE", "El fichero supera 10 MB", null, null, null);
        }
    }

    @ExceptionHandler(ProductExcelImportSummaryService.SummaryValidationException.class)
    public ResponseEntity<Map<String, Object>> summaryError(ProductExcelImportSummaryService.SummaryValidationException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SUMMARY_PREVIEW_INVALID");
        body.put("message", exception.getMessage());
        body.put("fileName", exception.fileName());
        body.put("sha256", exception.sha256());
        body.put("errors", exception.errors());
        boolean stale = exception.errors().stream().anyMatch(error -> "VERSION_STALE".equals(error.code()));
        return ResponseEntity.status(stale ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ProductExcelImportReadService.ProductExcelImportException.class)
    public ResponseEntity<Map<String, Object>> importError(ProductExcelImportReadService.ProductExcelImportException exception) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", exception.code());
        body.put("message", exception.getMessage());
        body.put("attribute", exception.attribute());
        body.put("receivedValue", exception.receivedValue());
        body.put("reason", exception.getMessage());
        body.put("acceptedValues", acceptedValues(exception.code()));
        body.put("recommendedFix", recommendedFix(exception.code()));
        if (exception.row() != null) body.put("row", exception.row());
        if (exception.column() != null) body.put("column", exception.column());
        HttpStatus status = "FILE_TOO_LARGE".equals(exception.code())
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(body);
    }

    private static Object acceptedValues(String code) {
        return switch (code) {
            case "FILE_EMPTY" -> "Un fichero XLS o XLSX con contenido";
            case "FILE_TOO_LARGE" -> "<= 10 MB";
            case "FILE_READ_FAILED" -> "Un fichero legible por el servidor";
            case "FILE_EXTENSION_INVALID" -> "Extensión .xls o .xlsx";
            case "FILE_SIGNATURE_INVALID" -> "Firma OLE2 para XLS o PK/OOXML para XLSX";
            case "WORKBOOK_ENCRYPTED" -> "Libro sin contraseña";
            case "WORKBOOK_CORRUPT", "WORKBOOK_UNREADABLE" -> "Libro XLS/XLSX válido y completo";
            case "WORKBOOK_MACRO_UNSUPPORTED" -> "Libro sin VBA/macros";
            case "SHEET_MISSING" -> "Al menos una hoja de cálculo";
            case "GRID_ROW_LIMIT" -> "Hasta 100.000 filas de cuadrícula";
            case "ROW_LIMIT" -> "<= 5.000 filas detectadas";
            case "COLUMN_LIMIT" -> "<= 256 columnas";
            case "GRID_CELL_LIMIT" -> "Hasta 250.000 celdas materializadas";
            case "WORKBOOK_LIMIT" -> "Contenedor XLSX dentro de los limites de entradas y expansion";
            case "CELL_LIMIT" -> "<= 250.000 celdas no vacías";
            case "CELL_TEXT_LIMIT" -> "Hasta 32.767 caracteres por celda";
            case "TEXT_LIMIT" -> "Hasta 5.000.000 caracteres materializados";
            case "FORMULA_LIMIT" -> "Hasta 20.000 fórmulas";
            case "FORMULA_NO_CACHE" -> "Fórmula con resultado almacenado";
            case "CELL_ERROR_VALUE" -> "Celda con un valor válido, sin errores de Excel";
            case "FORMULA_RESULT_ERROR" -> "Fórmula con un resultado almacenado válido, sin errores de Excel";
            case "DATE_INVALID" -> "Fecha Excel válida o DD-MM-AA, DD-MM-AAAA e ISO válidos";
            case "IDENTIFIER_NUMERIC_PRECISION" -> "Hasta 15 dígitos numéricos o columna formateada como Texto";
            case "NUMBER_FORMAT_UNSUPPORTED" -> "Formato numérico estándar de hasta 1.024 caracteres, sin condiciones y con 0, 1 o 2 operadores %";
            case "PERCENTAGE_FORMAT_NOT_ALLOWED" -> "Formato numérico sin porcentaje para este atributo";
            case "LOCALE_INVALID" -> "es, en o zh";
            case "CONTRACT_LIMIT" -> "Mapas de configuración dentro de los límites del contrato";
            default -> "Valores válidos según el formato XLS/XLSX";
        };
    }

    private static String recommendedFix(String code) {
        return switch (code) {
            case "FILE_SIGNATURE_INVALID" -> "Selecciona un XLS o XLSX real, no cambies solo la extension";
            case "FORMULA_NO_CACHE" -> "Guarda el libro en Excel para conservar el resultado de la formula";
            case "CELL_ERROR_VALUE" -> "Corrige el error de la celda en Excel y vuelve a guardar el libro";
            case "FORMULA_RESULT_ERROR" -> "Corrige la fórmula, recalcula el libro en Excel y vuelve a guardarlo";
            case "DATE_INVALID" -> "Corrige la fecha y utiliza un día de calendario real";
            case "IDENTIFIER_NUMERIC_PRECISION" -> "Formatea la columna como Texto y vuelve a leer el fichero";
            case "NUMBER_FORMAT_UNSUPPORTED" -> "Cambia el formato de la celda a número o porcentaje estándar";
            case "PERCENTAGE_FORMAT_NOT_ALLOWED" -> "Cambia la celda a formato numérico sin porcentaje";
            case "LOCALE_INVALID" -> "Selecciona uno de los idiomas disponibles";
            case "FORMULA_LIMIT" -> "Reduce el numero de formulas a 20.000 o menos";
            case "WORKBOOK_LIMIT" -> "Reduce las entradas o el contenido descomprimido del XLSX";
            case "CONTRACT_LIMIT" -> "Elimina claves de configuración que no utilices";
            case "ROW_LIMIT" -> "Divide el fichero en lotes de 5.000 filas o menos";
            default -> "Corrige el fichero y vuelve a intentarlo";
        };
    }
}
