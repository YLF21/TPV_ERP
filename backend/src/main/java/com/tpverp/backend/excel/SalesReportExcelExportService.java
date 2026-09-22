package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.document.DocumentReportService;
import com.tpverp.backend.document.DocumentReportView;
import com.tpverp.backend.document.DocumentService;
import com.tpverp.backend.document.DocumentAttributionResolver;
import com.tpverp.backend.document.DocumentView;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.TicketReportService;
import com.tpverp.backend.document.TicketReportView;
import com.tpverp.backend.document.WarehouseInputReportService;
import com.tpverp.backend.document.WarehouseInputReportView;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
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
            "date", "time", "ticket", "invoice", "invoiced", "deliveryNote", "documentType", "terminal", "user",
            "productCount", "customer", "customerName", "supplier", "supplierName", "comment",
            "warehouse", "input", "output", "total", "pending", "payment", "status", "reason",
            "origin", "dueDate", "tickets", "base", "tax", "discount", "memberBalance", "subtotal", "globalDiscount");
    private static final Set<String> REPORT_KEYS = Set.of(
            "salesReport.dailySales", "salesReport.tickets", "salesReport.deliveryNotes",
            "salesReport.invoices", "salesReport.warehouseOutputs", "salesReport.inputDeliveryNotes",
            "salesReport.inputInvoices", "salesReport.inputWarehouse");
    private static final Set<String> STORE_INFORMATION_REPORT_KEYS = Set.of(
            "salesReport.warehouseOutputs",
            "salesReport.inputInvoices",
            "salesReport.inputDeliveryNotes",
            "salesReport.inputWarehouse");
    private static final Set<String> TICKET_PAYMENT_METHODS = Set.of(
            "EFECTIVO", "TARJETA", "VALE", "PENDIENTE", "TRANSFERENCIA", "DESCUENTO",
            "SALDO_MIEMBRO", "CREDITO_DEVOLUCION");

    private final DocumentService documents;
    private final DocumentReportService documentReports;
    private final WarehouseInputReportService warehouseInputs;
    private final WarehouseOutputService warehouseOutputs;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;
    private final DocumentAttributionResolver attributions;
    private final AuditService auditService;
    private final TicketReportService ticketReports;

    public SalesReportExcelExportService(
            DocumentService documents,
            DocumentReportService documentReports,
            WarehouseInputReportService warehouseInputs,
            WarehouseOutputService warehouseOutputs,
            WarehouseRepository warehouses,
            CurrentOrganization organization,
            DocumentAttributionResolver attributions,
            AuditService auditService,
            TicketReportService ticketReports) {
        this.documents = documents;
        this.documentReports = documentReports;
        this.warehouseInputs = warehouseInputs;
        this.warehouseOutputs = warehouseOutputs;
        this.warehouses = warehouses;
        this.organization = organization;
        this.attributions = attributions;
        this.auditService = auditService;
        this.ticketReports = ticketReports;
    }

    @Transactional
    public byte[] export(SalesReportExportRequest request, Authentication authentication) {
        validateRequest(request);
        requireAccess(request.reportKey(), authentication);
        var rows = filteredRows(request, rows(request, authentication));
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("El informe supera el limite de 50000 filas exportables");
        }
        byte[] workbook = workbook(request, rows);
        auditService.record("SALES_REPORT_EXPORTED", AuditResult.EXITO,
                Map.of("reportKey", request.reportKey(), "rows", rows.size()));
        return workbook;
    }

    private List<Map<String, Object>> rows(SalesReportExportRequest request, Authentication authentication) {
        return switch (request.reportKey()) {
            case "salesReport.tickets" -> ticketReportRows(request);
            case "salesReport.invoices" -> documentRows(
                    documentReports.allInvoices(true, false), true, false);
            case "salesReport.inputInvoices" -> inputRows(request, WarehouseInputDocumentType.FACTURA_ENTRADA, authentication);
            case "salesReport.deliveryNotes" -> documentRows(
                    documentReports.allDeliveryNotes(true, false), false, false);
            case "salesReport.inputDeliveryNotes" -> inputRows(request, WarehouseInputDocumentType.ALBARAN_ENTRADA, authentication);
            case "salesReport.warehouseOutputs" -> {
                var warehouseNames = warehouseNames();
                yield warehouseOutputs.list().stream()
                        .map(WarehouseOutputView::from)
                        .map(value -> outputRow(value, warehouseNames))
                        .toList();
            }
            case "salesReport.inputWarehouse" -> inputRows(request, WarehouseInputDocumentType.ENTRADA_ALMACEN, authentication);
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
            row.put("base", value.base());
            row.put("tax", value.impuesto());
            row.put("discount", BigDecimal.ZERO);
            row.put("memberBalance", value.memberBalance());
            row.put("total", value.total());
            return row;
        }).toList();
    }

    private List<Map<String, Object>> ticketReportRows(SalesReportExportRequest request) {
        var filters = request.filters();
        var dateFilter = new CustomerDocumentReportFilter(null, null,
                filters == null ? null : date(filters.dateFrom()),
                filters == null ? null : date(filters.dateTo()), null, null);
        var result = new ArrayList<Map<String, Object>>();
        String cursor = null;
        do {
            var page = ticketReports.list(500, cursor, null, dateFilter);
            result.addAll(filteredRows(request, page.items().stream().map(this::ticketReportRow).toList()));
            if (result.size() > MAX_ROWS) {
                throw new IllegalArgumentException("El informe supera el limite de 50000 filas exportables");
            }
            if (!page.hasMore()) break;
            if (page.nextCursor() == null || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("No se pudo continuar la paginacion del informe de tickets");
            }
            cursor = page.nextCursor();
        } while (true);
        return result;
    }

    private Map<String, Object> ticketReportRow(TicketReportView value) {
        var row = baseRow(value.fecha(), value.usuarioNombre(), value.terminalOrigenNombre(), value.ocurridoEn());
        row.put("ticket", value.numero());
        row.put("customer", text(value.customerCode(), id(value.customerId())));
        row.put("customerName", value.customerName());
        row.put("status", ticketStatus(value));
        row.put("invoiced", value.invoiceNumber());
        row.put("comment", value.comentarioInterno());
        var methods = new ArrayList<String>();
        if (amount(value.total()).signum() < 0) {
            if (value.refundMethods() != null) value.refundMethods().forEach(method -> methods.add(method.name()));
        } else if (value.paymentMethods() != null) {
            methods.addAll(value.paymentMethods());
        }
        if (value.estado() == com.tpverp.backend.document.DocumentStatus.PENDIENTE
                || value.estado() == com.tpverp.backend.document.DocumentStatus.PARCIAL) {
            methods.add("PENDIENTE");
        }
        var displayedMethods = methods.stream().map(SalesReportExcelExportService::normalizePayment)
                .filter(TICKET_PAYMENT_METHODS::contains).distinct().toList();
        row.put("__payments", displayedMethods);
        row.put("payment", displayedMethods.stream()
                .reduce((first, second) -> first + ", " + second).orElse(""));
        row.put("base", value.base());
        row.put("tax", value.impuesto());
        row.put("discount", value.descuentoGlobal());
        row.put("memberBalance", value.memberBalance());
        row.put("total", value.total());
        return row;
    }

    private static String ticketStatus(TicketReportView value) {
        if (value.estado() == com.tpverp.backend.document.DocumentStatus.ANULADO) return "CANCELLED";
        if (value.lifecycleStatus() != null) {
            return switch (value.lifecycleStatus()) {
                case CANCELLED, INVOICED, PARTIALLY_RETURNED, RETURNED -> value.lifecycleStatus().name();
                default -> value.estado().name();
            };
        }
        return value.estado().name();
    }

    private List<Map<String, Object>> documentRows(
            List<DocumentReportView> values,
            boolean invoice,
            boolean purchase) {
        return values.stream().map(value -> {
            var row = baseRow(value.fecha(), value.usuarioNombre(),
                    value.terminalOrigenNombre(), value.ocurridoEn());
            row.put(invoice ? "invoice" : "deliveryNote", value.numero());
            row.put("documentType", value.tipo().name());
            row.put("status", value.estado().name());
            row.put("comment", value.numeroExterno());
            row.put("pending", value.pendiente());
            row.put("base", value.base());
            row.put("tax", value.impuesto());
            row.put("discount", value.descuentoGlobal());
            row.put("memberBalance", value.memberBalance());
            row.put("dueDate", displayDate(value.fechaVencimiento()));
            row.put("productCount", value.lineas());
            row.put("warehouse", text(value.almacenNombre(), id(value.almacenId())));
            row.put("payment", payments(value.payments()));
            row.put("__payments", value.payments() == null ? List.of() : value.payments().stream()
                    .map(DocumentView.PaymentView::methodName).filter(method -> !blank(method)).toList());
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

    private Map<String, Object> outputRow(
            WarehouseOutputView value,
            Map<java.util.UUID, String> warehouseNames) {
        var row = baseRow(value.date(), "", "", null);
        row.put("output", text(value.number(), id(value.id())));
        row.put("warehouse", warehouseNames.getOrDefault(value.warehouseId(), id(value.warehouseId())));
        row.put("productCount", value.lines().stream().mapToInt(line -> line.quantity()).sum());
        row.put("comment", value.concept());
        row.put("reason", text(value.destination(), value.status().name()));
        row.put("total", value.lines().stream()
                .map(line -> amount(line.saleTotal()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return row;
    }

    private List<Map<String, Object>> inputRows(SalesReportExportRequest request,
            WarehouseInputDocumentType type, Authentication authentication) {
        var filters = request.filters();
        var from = filters == null ? null : date(filters.dateFrom());
        var to = filters == null ? null : date(filters.dateTo());
        var result = new ArrayList<Map<String, Object>>();
        String cursor = null;
        do {
            // The reader applies type, store and dates before fetching a bounded page of lines.
            var page = warehouseInputs.listPage(type, 200, cursor, from, to, authentication);
            result.addAll(filteredRows(request, page.items().stream().map(this::inputRow).toList()));
            if (result.size() > MAX_ROWS) {
                throw new IllegalArgumentException("El informe supera el limite de 50000 filas exportables");
            }
            if (!page.hasMore()) break;
            if (page.nextCursor() == null || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("No se pudo continuar la paginacion del informe de entradas");
            }
            cursor = page.nextCursor();
        } while (true);
        return result;
    }

    private Map<String, Object> inputRow(WarehouseInputReportView report) {
        var value = report.document();
        var row = baseRow(value.date(), "", "", null);
        var numberColumn = switch (value.documentType()) {
            case FACTURA_ENTRADA -> "invoice";
            case ALBARAN_ENTRADA -> "deliveryNote";
            case ENTRADA_ALMACEN -> "input";
        };
        row.put(numberColumn, value.number());
        row.put("warehouse", report.warehouseName());
        row.put("supplier", report.supplierCode());
        row.put("supplierName", report.supplierName());
        row.put("productCount", value.lines().stream()
                .map(line -> line.quantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        row.put("comment", text(value.concept(), value.externalNumber()));
        row.put("origin", text(value.origin(), value.status().name()));
        row.put("status", value.status().name());
        row.put("subtotal", value.subtotal());
        row.put("globalDiscount", value.globalDiscount());
        row.put("total", value.total());
        return row;
    }

    private Map<java.util.UUID, String> warehouseNames() {
        return warehouses.findAll().stream().collect(java.util.stream.Collectors.toMap(
                com.tpverp.backend.catalog.Warehouse::getId,
                com.tpverp.backend.catalog.Warehouse::getName));
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
                    && any(filters.users(), filters.user(), expected -> attributionMatches(row, "user", expected),
                            expected -> exact(row, "user", expected))
                    && partyMatches(row, "customer", "customerName", filters.customers(), filters.customer(),
                            List.of("customer", "customerName", "supplier", "supplierName"))
                    && partyMatches(row, "supplier", "supplierName", filters.suppliers(), filters.supplier(),
                            List.of("supplier", "supplierName"))
                    && any(filters.payments(), filters.payment(), expected -> paymentMatches(row, expected),
                            expected -> exact(row, "payment", expected) || paymentMatches(row, expected))
                    && any(filters.terminals(), filters.terminal(), expected -> attributionMatches(row, "terminal", expected),
                            expected -> exact(row, "terminal", expected))
                    && any(filters.statuses(), filters.status(), expected -> statusMatches(row.get("status"), expected),
                            expected -> statusMatches(row.get("status"), expected)
                                    || exact(row, "payment", expected) || paymentMatches(row, expected))
                    && any(filters.warehouses(), filters.warehouse(), expected -> exact(row, "warehouse", expected))
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
            var currencyStyle = workbook.createCellStyle();
            currencyStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
            var percentageStyle = workbook.createCellStyle();
            percentageStyle.setDataFormat(workbook.createDataFormat().getFormat("0.##%"));
            int headerRowIndex = writeStoreInformation(sheet, workbook, request);
            var header = sheet.createRow(headerRowIndex);
            for (int column = 0; column < request.columns().size(); column++) {
                var cell = header.createCell(column);
                cell.setCellValue(request.columns().get(column).label());
                cell.setCellStyle(headerStyle);
            }
            for (int index = 0; index < rows.size(); index++) {
                var excelRow = sheet.createRow(headerRowIndex + index + 1);
                var source = rows.get(index);
                for (int column = 0; column < request.columns().size(); column++) {
                    Object value = source.get(request.columns().get(column).key());
                    var cell = excelRow.createCell(column);
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                        if (Set.of("total", "subtotal", "pending", "base", "tax", "discount", "memberBalance")
                                .contains(request.columns().get(column).key())) {
                            cell.setCellStyle(currencyStyle);
                        } else if ("globalDiscount".equals(request.columns().get(column).key())) {
                            cell.setCellValue(new BigDecimal(number.toString()).movePointLeft(2).doubleValue());
                            cell.setCellStyle(percentageStyle);
                        }
                    } else {
                        cell.setCellValue(value == null ? "" : String.valueOf(value));
                    }
                }
            }
            sheet.createFreezePane(0, headerRowIndex + 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    headerRowIndex,
                    headerRowIndex + Math.max(0, rows.size()),
                    0,
                    request.columns().size() - 1));
            for (int column = 0; column < request.columns().size(); column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 12_000));
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("message.sales_report.excel_generation_failed", exception);
        }
    }

    private int writeStoreInformation(
            org.apache.poi.ss.usermodel.Sheet sheet,
            org.apache.poi.ss.usermodel.Workbook workbook,
            SalesReportExportRequest request) {
        if (!STORE_INFORMATION_REPORT_KEYS.contains(request.reportKey())) {
            return 0;
        }
        var labelStyle = workbook.createCellStyle();
        var labelFont = workbook.createFont();
        labelFont.setBold(true);
        labelStyle.setFont(labelFont);
        var company = organization.currentCompany();
        var store = organization.currentStore();
        var filters = request.filters();
        String period = filters == null
                ? "Todos"
                : period(filters.dateFrom(), filters.dateTo());
        List<List<String>> information = List.of(
                List.of("Empresa", company.getRazonSocial()),
                List.of("NIF", company.getTaxId()),
                List.of("Tienda", store.getCodigoTienda()),
                List.of("Nombre de la tienda", store.getNombreEfectivo()),
                List.of("Moneda", store.getMoneda()),
                List.of("Informe", reportName(request.reportKey())),
                List.of("Periodo", period));
        for (int index = 0; index < information.size(); index++) {
            var row = sheet.createRow(index);
            var label = row.createCell(0);
            label.setCellValue(information.get(index).get(0));
            label.setCellStyle(labelStyle);
            row.createCell(1).setCellValue(information.get(index).get(1));
        }
        return information.size() + 1;
    }

    private static String period(String from, String to) {
        String formattedFrom = displayDate(date(from));
        String formattedTo = displayDate(date(to));
        if (formattedFrom.isBlank() && formattedTo.isBlank()) return "Todos";
        if (formattedFrom.isBlank()) return "Hasta " + formattedTo;
        if (formattedTo.isBlank()) return "Desde " + formattedFrom;
        return formattedFrom + " - " + formattedTo;
    }

    private static String reportName(String reportKey) {
        return switch (reportKey) {
            case "salesReport.warehouseOutputs" -> "Salida de almacén";
            case "salesReport.inputInvoices" -> "Entrada de factura";
            case "salesReport.inputDeliveryNotes" -> "Entrada de albarán";
            case "salesReport.inputWarehouse" -> "Entrada de almacén";
            default -> reportKey;
        };
    }

    private void validateRequest(SalesReportExportRequest request) {
        if (!REPORT_KEYS.contains(request.reportKey())) {
            throw new IllegalArgumentException("message.sales_report.unsupported");
        }
        if (request.columns() == null || request.columns().isEmpty()
                || request.columns().stream().anyMatch(column -> !ALLOWED_COLUMNS.contains(column.key()))) {
            throw new IllegalArgumentException("message.sales_report.invalid_columns");
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

    private static boolean any(List<String> selected, String legacy, Predicate<String> matches) {
        return any(selected, legacy, matches, matches);
    }

    private static boolean any(List<String> selected, String legacy, Predicate<String> matches, Predicate<String> legacyMatches) {
        var values = selected == null ? List.<String>of() : selected.stream().filter(value -> !blank(value)).toList();
        return values.isEmpty() ? blank(legacy) || legacyMatches.test(legacy) : values.stream().anyMatch(matches);
    }

    private static boolean attributionMatches(Map<String, Object> row, String key, String expected) {
        String value = id(row.get(key));
        return (blank(value) ? "salesReport.value.unavailable" : value).equals(expected);
    }

    private static boolean partyMatches(Map<String, Object> row, String codeKey, String nameKey,
            List<String> selected, String legacy, List<String> legacyKeys) {
        if (selected == null || selected.stream().allMatch(SalesReportExcelExportService::blank)) {
            return contains(row, legacyKeys, legacy);
        }
        String code = id(row.get(codeKey));
        String value = blank(code) ? id(row.get(nameKey)) : code;
        return selected.stream().filter(expected -> !blank(expected)).anyMatch(value::equals);
    }

    private static boolean paymentMatches(Map<String, Object> row, String expected) {
        if (blank(expected)) return true;
        if (row.get("__payments") instanceof List<?> methods) {
            return methods.stream().anyMatch(method -> normalizePayment(String.valueOf(method)).equals(normalizePayment(expected)));
        }
        return normalizePayment(id(row.get("payment"))).equals(normalizePayment(expected));
    }

    private static String normalizePayment(String value) {
        return switch (normalize(value)) {
            case "cash", "efectivo", "salesreport.payment.cash" -> "EFECTIVO";
            case "card", "tarjeta", "salesreport.payment.card" -> "TARJETA";
            case "transfer", "transferencia", "salesreport.payment.transfer" -> "TRANSFERENCIA";
            case "voucher", "vale", "salesreport.payment.voucher" -> "VALE";
            case "pending", "pendiente", "salesreport.payment.pending" -> "PENDIENTE";
            case "discount", "descuento", "salesreport.payment.discount", "salesreport.column.discount" -> "DESCUENTO";
            case "member_balance", "saldo_miembro", "salesreport.payment.memberbalance" -> "SALDO_MIEMBRO";
            case "member_credit", "credito_devolucion", "salesreport.payment.returncredit" -> "CREDITO_DEVOLUCION";
            default -> value == null ? "" : value.trim();
        };
    }

    private static boolean statusMatches(Object value, String expected) {
        return normalizeStatus(value == null ? "" : String.valueOf(value)).equals(normalizeStatus(expected));
    }

    private static String normalizeStatus(String value) {
        return switch (normalize(value)) {
            case "salesreport.status.draft", "draft", "borrador" -> "BORRADOR";
            case "salesreport.status.confirmed", "confirmed", "confirmado", "confirmada" -> "CONFIRMADA";
            case "salesreport.status.cancelled", "salesreport.status.ticketcancelled", "cancelled", "anulado" -> "ANULADO";
            case "salesreport.status.pending", "pending", "pendiente" -> "PENDIENTE";
            case "salesreport.status.partial", "partial", "parcial" -> "PARCIAL";
            case "salesreport.status.paid", "paid", "pagado" -> "PAGADO";
            case "salesreport.status.invoiced", "invoiced", "facturado" -> "FACTURADO";
            case "salesreport.status.partiallyreturned", "partially_returned" -> "PARTIALLY_RETURNED";
            case "salesreport.status.returned", "returned" -> "RETURNED";
            default -> value;
        };
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
