package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.DocumentReportService;
import com.tpverp.backend.document.DocumentReportView;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentView;
import com.tpverp.backend.inventory.WarehouseInputService;
import com.tpverp.backend.inventory.WarehouseInputView;
import com.tpverp.backend.inventory.WarehouseOutputService;
import com.tpverp.backend.inventory.WarehouseOutputView;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SalesReportExcelExportService {

    private static final int MAX_ROWS = 50_000;
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> ALLOWED_COLUMNS = Set.of(
            "date", "time", "ticket", "invoice", "invoiced", "deliveryNote", "terminal", "user",
            "productCount", "customer", "customerName", "supplier", "supplierName", "comment",
            "warehouse", "input", "output", "total", "pending", "payment", "status", "reason",
            "origin", "dueDate", "tickets");
    private static final Set<String> REPORT_KEYS = Set.of(
            "salesReport.dailySales", "salesReport.tickets", "salesReport.deliveryNotes",
            "salesReport.invoices", "salesReport.warehouseOutputs", "salesReport.inputDeliveryNotes",
            "salesReport.inputInvoices", "salesReport.inputWarehouse");

    private final DocumentService documents;
    private final DocumentReportService documentReports;
    private final WarehouseInputService warehouseInputs;
    private final WarehouseOutputService warehouseOutputs;
    private final CurrentOrganization organization;
    private final DocumentAttributionResolver attributions;
    private final AuditService auditService;

    public SalesReportExcelExportService(
            DocumentService documents,
            DocumentReportService documentReports,
            WarehouseInputService warehouseInputs,
            WarehouseOutputService warehouseOutputs,
            CurrentOrganization organization,
            DocumentAttributionResolver attributions,
            AuditService auditService) {
        this.documents = documents;
        this.documentReports = documentReports;
        this.warehouseInputs = warehouseInputs;
        this.warehouseOutputs = warehouseOutputs;
        this.organization = organization;
        this.attributions = attributions;
        this.auditService = auditService;
    }

    @Transactional
    public byte[] export(SalesReportExportRequest request, Authentication authentication) {
        validateRequest(request);
        requireAccess(request.reportKey(), authentication);
        var rows = filteredRows(request, rows(request.reportKey()));
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("El informe supera el limite de 50000 filas exportables");
        }
        byte[] workbook = workbook(request, rows);
        auditService.record("SALES_REPORT_EXPORTED", AuditResult.EXITO,
                Map.of("reportKey", request.reportKey(), "rows", rows.size()));
        return workbook;
    }

    private List<Map<String, Object>> rows(String reportKey) {
        return switch (reportKey) {
            case "salesReport.tickets" -> ticketRows();
            case "salesReport.invoices" -> documentRows(
                    documentReports.allInvoices(true, false), true, false);
            case "salesReport.inputInvoices" -> documentRows(
                    documentReports.allInvoices(false, true), true, true);
            case "salesReport.deliveryNotes" -> documentRows(
                    documentReports.allDeliveryNotes(true, false), false, false);
            case "salesReport.inputDeliveryNotes" -> documentRows(
                    documentReports.allDeliveryNotes(false, true), false, true);
            case "salesReport.warehouseOutputs" -> warehouseOutputs.list().stream()
                    .map(WarehouseOutputView::from).map(this::outputRow).toList();
            case "salesReport.inputWarehouse" -> warehouseInputs.list().stream()
                    .map(WarehouseInputView::from).map(this::inputRow).toList();
            case "salesReport.dailySales" -> dailyRows();
            default -> throw new IllegalArgumentException("Informe no soportado");
        };
    }

    private List<Map<String, Object>> ticketRows() {
        var values = documents.listTickets();
        var attributionIndex = attributions.resolve(values);
        return values.stream().map(document -> {
            var attribution = attributionIndex.get(document.getId());
            var value = DocumentView.from(document, null, attribution);
            var row = baseRow(value.fecha(), value.usuarioNombre(),
                    value.terminalOrigenNombre(), value.ocurridoEn());
            row.put("ticket", text(value.numTicket(), value.numero()));
            row.put("payment", payments(value.payments()));
            row.put("total", value.total());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> documentRows(
            List<DocumentReportView> values,
            boolean invoice,
            boolean purchase) {
        return values.stream().map(value -> {
            var row = baseRow(value.fecha(), value.usuarioNombre(),
                    value.terminalOrigenNombre(), value.ocurridoEn());
            row.put(invoice ? "invoice" : "deliveryNote", value.numero());
            row.put("status", value.estado().name());
            row.put("comment", value.numeroExterno());
            row.put("pending", value.pendiente());
            row.put("dueDate", displayDate(value.fechaVencimiento()));
            row.put("productCount", value.lineas());
            row.put("warehouse", text(value.almacenNombre(), id(value.almacenId())));
            row.put("payment", payments(value.payments()));
            row.put("total", value.total());
            if (purchase) {
                row.put("supplier", text(value.proveedorCodigo(), id(value.proveedorId())));
                row.put("supplierName", value.proveedorNombre());
            } else {
                row.put("customer", text(value.clienteCodigo(), id(value.clienteId())));
                row.put("customerName", value.clienteNombre());
            }
            return row;
        }).toList();
    }

    private List<Map<String, Object>> dailyRows() {
        var grouped = new LinkedHashMap<LocalDate, Map<String, Object>>();
        ticketRows().forEach(row -> addDaily(grouped, row, true));
        documentRows(documentReports.allInvoices(true, false), true, false)
                .forEach(row -> addDaily(grouped, row, false));
        return grouped.entrySet().stream().sorted(Map.Entry.<LocalDate, Map<String, Object>>comparingByKey().reversed())
                .map(Map.Entry::getValue).toList();
    }

    private void addDaily(Map<LocalDate, Map<String, Object>> grouped, Map<String, Object> source, boolean ticket) {
        var date = (LocalDate) source.get("__date");
        var row = grouped.computeIfAbsent(date, value -> {
            var created = baseRow(value, String.valueOf(source.get("user")),
                    String.valueOf(source.get("terminal")), null);
            created.put("tickets", 0);
            created.put("invoice", 0);
            created.put("total", BigDecimal.ZERO);
            return created;
        });
        row.put(ticket ? "tickets" : "invoice", ((Number) row.get(ticket ? "tickets" : "invoice")).intValue() + 1);
        row.put("total", ((BigDecimal) row.get("total")).add(amount(source.get("total"))));
    }

    private Map<String, Object> outputRow(WarehouseOutputView value) {
        var row = baseRow(value.date(), "", "", null);
        row.put("output", text(value.number(), id(value.id())));
        row.put("warehouse", id(value.warehouseId()));
        row.put("productCount", value.lines().stream().mapToInt(line -> line.quantity()).sum());
        row.put("comment", value.concept());
        row.put("reason", text(value.destination(), value.status().name()));
        row.put("total", BigDecimal.ZERO);
        return row;
    }

    private Map<String, Object> inputRow(WarehouseInputView value) {
        var row = baseRow(value.date(), "", "", null);
        row.put("input", text(value.number(), id(value.id())));
        row.put("warehouse", id(value.warehouseId()));
        row.put("supplier", id(value.supplierId()));
        row.put("productCount", value.lines().stream().mapToInt(line -> line.quantity()).sum());
        row.put("comment", value.concept());
        row.put("origin", text(value.origin(), value.status().name()));
        row.put("total", BigDecimal.ZERO);
        return row;
    }

    private Map<String, Object> baseRow(
            LocalDate date,
            String user,
            String terminal,
            Instant occurredAt) {
        var row = new LinkedHashMap<String, Object>();
        row.put("__date", date);
        row.put("date", displayDate(date));
        row.put("time", displayTime(occurredAt));
        row.put("user", user);
        row.put("terminal", terminal);
        return row;
    }

    private List<Map<String, Object>> filteredRows(
            SalesReportExportRequest request,
            List<Map<String, Object>> source) {
        var filters = request.filters() == null ? new SalesReportExportRequest.Filters(
                "", "", "", "", "", "", "", "", "") : request.filters();
        LocalDate from = date(filters.dateFrom());
        LocalDate to = date(filters.dateTo());
        String search = normalize(request.search());
        return source.stream().filter(row -> {
            LocalDate rowDate = (LocalDate) row.get("__date");
            return (from == null || !rowDate.isBefore(from))
                    && (to == null || !rowDate.isAfter(to))
                    && exact(row, "user", filters.user())
                    && contains(row, List.of("customer", "customerName", "supplier", "supplierName"), filters.customer())
                    && contains(row, List.of("supplier", "supplierName"), filters.supplier())
                    && exact(row, "payment", filters.payment())
                    && exact(row, "terminal", filters.terminal())
                    && (blank(filters.status()) || exact(row, "status", filters.status()) || exact(row, "payment", filters.status()))
                    && exact(row, "warehouse", filters.warehouse())
                    && (search.isEmpty() || normalize(row.values().toString()).contains(search));
        }).toList();
    }

    private byte[] workbook(SalesReportExportRequest request, List<Map<String, Object>> rows) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Informe");
            var headerStyle = workbook.createCellStyle();
            var headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var header = sheet.createRow(0);
            for (int column = 0; column < request.columns().size(); column++) {
                var cell = header.createCell(column);
                cell.setCellValue(request.columns().get(column).label());
                cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < rows.size(); index++) {
                var excelRow = sheet.createRow(index + 1);
                var source = rows.get(index);
                for (int column = 0; column < request.columns().size(); column++) {
                    Object value = source.get(request.columns().get(column).key());
                    var cell = excelRow.createCell(column);
                    if (value instanceof Number number) cell.setCellValue(number.doubleValue());
                    else cell.setCellValue(value == null ? "" : String.valueOf(value));
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, request.columns().size() - 1));
            for (int column = 0; column < request.columns().size(); column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 12_000));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el informe Excel", exception);
        }
    }

    private void validateRequest(SalesReportExportRequest request) {
        if (!REPORT_KEYS.contains(request.reportKey())) throw new IllegalArgumentException("Informe no soportado");
        if (request.columns() == null || request.columns().isEmpty()
                || request.columns().stream().anyMatch(column -> !ALLOWED_COLUMNS.contains(column.key()))) {
            throw new IllegalArgumentException("Columnas de informe no validas");
        }
    }

    private void requireAccess(String reportKey, Authentication authentication) {
        boolean allowed = switch (reportKey) {
            case "salesReport.dailySales", "salesReport.tickets" ->
                    canExportSales(authentication);
            case "salesReport.invoices", "salesReport.deliveryNotes" -> canExportSales(authentication);
            case "salesReport.inputInvoices", "salesReport.inputDeliveryNotes" ->
                    PermissionChecks.hasPurchaseDocumentRead(authentication);
            case "salesReport.warehouseOutputs", "salesReport.inputWarehouse" ->
                    PermissionChecks.hasWarehouseManagement(authentication);
            default -> false;
        };
        if (!allowed) throw new AccessDeniedException("No tiene permiso para exportar este informe");
    }

    private boolean canExportSales(Authentication authentication) {
        return PermissionChecks.hasRole(authentication, "ADMIN")
                || PermissionChecks.hasAuthority(authentication, "GESTION_VENTAS");
    }

    private static String payments(List<DocumentView.PaymentView> values) {
        return values == null ? "" : values.stream().map(DocumentView.PaymentView::methodName)
                .filter(value -> value != null && !value.isBlank()).distinct().reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static boolean exact(Map<String, Object> row, String key, String expected) {
        return blank(expected) || String.valueOf(row.getOrDefault(key, "")).equals(expected);
    }

    private static boolean contains(Map<String, Object> row, List<String> keys, String expected) {
        if (blank(expected)) return true;
        String target = normalize(expected);
        return keys.stream().map(key -> String.valueOf(row.getOrDefault(key, "")))
                .map(SalesReportExcelExportService::normalize).anyMatch(value -> value.contains(target));
    }

    private static String normalize(String value) {
        return value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).trim();
    }

    private static LocalDate date(String value) {
        return blank(value) ? null : LocalDate.parse(value);
    }

    private static String displayDate(LocalDate value) {
        return value == null ? "" : value.format(DISPLAY_DATE);
    }

    private String displayTime(Instant value) {
        return value == null ? "" : value.atZone(ZoneId.of(
                organization.currentStore().getTimezone())).format(DISPLAY_TIME);
    }

    private static String id(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String text(String preferred, String fallback) {
        return blank(preferred) ? (fallback == null ? "" : fallback) : preferred;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BigDecimal amount(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }
}
