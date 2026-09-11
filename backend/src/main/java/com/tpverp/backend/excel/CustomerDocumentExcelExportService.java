package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.CustomerDocumentReportQueryRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.party.Customer;
import com.tpverp.backend.party.CustomerRepository;
import com.tpverp.backend.security.application.PermissionChecks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerDocumentExcelExportService {

    static final int MAX_ROWS = 50_000;
    private static final int PAGE_SIZE = 500;
    private static final int HEADER_ROW = 4;
    private static final Set<String> COLUMNS = Set.of(
            "number", "date", "type", "status", "base", "tax", "total", "terminal", "user");
    private static final Set<String> RECEIVED_COLUMNS = Set.of(
            "number", "date", "type", "status", "base", "tax", "total", "terminal", "user", "store", "currency");
    private final CurrentOrganization organization;
    private final CustomerRepository customers;
    private final CustomerDocumentReportQueryRepository queries;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public CustomerDocumentExcelExportService(
            CurrentOrganization organization,
            CustomerRepository customers,
            CustomerDocumentReportQueryRepository queries,
            NamedParameterJdbcTemplate jdbc,
            AuditService audit) {
        this.organization = organization;
        this.customers = customers;
        this.queries = queries;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // One database snapshot keeps the exported pages consistent while other tills operate.
    // The only write is the existing successful-export audit event.
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public byte[] export(CustomerDocumentExportRequest request, Authentication authentication) {
        var types = allowedTypes(request.reportKey(), authentication);
        var filter = request.queryFilter();
        boolean filtered = hasFilters(filter);
        validateRequest(request, types, filtered);
        var storeId = organization.currentStore().getId();
        var customer = customers.findByIdAndCompanyId(request.customerId(), organization.currentCompany().getId())
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));

        var workbook = new SXSSFWorkbook(PAGE_SIZE);
        workbook.setCompressTempFiles(true);
        try (workbook; var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(request.labels().sheetName()));
            try {
                sheet.setDisplayGridlines(false);
                var styles = Styles.create(workbook);
                writeHeader(sheet, request.columns(), styles);
                writeCustomer(sheet, customer, request, filter, styles);
                var summary = filtered
                        ? writeFiltered(sheet, request, storeId, types, filter, styles)
                        : writeLoaded(sheet, request, storeId, types, styles);
                writeTotal(sheet, request, summary, styles);
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + summary.count(), 0, request.columns().size() - 1));
                sheet.createFreezePane(0, HEADER_ROW + 1);
                workbook.write(output);
                var result = output.toByteArray();
                audit.record("CUSTOMER_DOCUMENTS_EXPORTED", AuditResult.EXITO,
                        Map.of("reportKey", request.reportKey(), "customerId", request.customerId().toString(),
                                "rows", summary.count(), "filtered", filtered));
                return result;
            } finally {
                // POI 5.5 closes sheet writers before disposing them. Flush pending rows first,
                // also when selection validation aborts, so close() can delete its temporary files.
                sheet.flushRows();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudieron exportar los documentos del cliente", exception);
        }
    }

    private ExportSummary writeFiltered(Sheet sheet, CustomerDocumentExportRequest request,
            UUID storeId, Set<CommercialDocumentType> types, CustomerDocumentReportFilter filter, Styles styles) {
        var summary = new ExportSummary(0, BigDecimal.ZERO);
        String cursor = null;
        do {
            var page = queries.findPage(storeId, request.customerId(), types, filter, cursor, PAGE_SIZE);
            if (summary.count() + page.ids().size() > MAX_ROWS
                    || summary.count() + page.ids().size() == MAX_ROWS && page.hasMore()) {
                throw new ExportLimitExceededException();
            }
            summary = writeRows(sheet, request, loadRows(storeId, request.customerId(), types, page.ids()), summary, styles);
            if (!page.hasMore()) return summary;
            if (page.ids().isEmpty() || page.nextCursor() == null || page.nextCursor().equals(cursor)) {
                throw new IllegalStateException("La paginación de documentos no avanzó");
            }
            cursor = page.nextCursor();
        } while (true);
    }

    private ExportSummary writeLoaded(Sheet sheet, CustomerDocumentExportRequest request,
            UUID storeId, Set<CommercialDocumentType> types, Styles styles) {
        var summary = new ExportSummary(0, BigDecimal.ZERO);
        var ids = request.documentIds();
        for (int offset = 0; offset < ids.size(); offset += PAGE_SIZE) {
            var batch = ids.subList(offset, Math.min(offset + PAGE_SIZE, ids.size()));
            summary = writeRows(sheet, request, loadRows(storeId, request.customerId(), types, batch), summary, styles);
        }
        return summary;
    }

    private List<ExportRow> loadRows(UUID storeId, UUID customerId,
            Set<CommercialDocumentType> types, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        // Scalar projection avoids fetching document lines/payments or retaining large JPA graphs.
        var rows = jdbc.query("""
                select document.id, document.numero, document.fecha, document.tipo, document.estado,
                       document.base_total, document.impuesto_total, document.total,
                       terminal.nombre as terminal_name, actor.user_name as user_name
                from documento document
                left join terminal terminal
                  on terminal.id = document.terminal_origen_id and terminal.tienda_id = document.tienda_id
                left join usuario actor
                  on actor.id = coalesce(document.confirmado_por, document.creado_por)
                 and (actor.tienda_id is null or actor.tienda_id = document.tienda_id)
                where document.tienda_id = :storeId and document.cliente_id = :customerId
                  and document.tipo in (:types) and document.id in (:ids)
                """, new MapSqlParameterSource()
                        .addValue("storeId", storeId).addValue("customerId", customerId)
                        .addValue("types", types.stream().map(Enum::name).toList()).addValue("ids", ids),
                (result, rowNumber) -> new ExportRow(
                        result.getObject("id", UUID.class), result.getString("numero"),
                        result.getObject("fecha", LocalDate.class),
                        CommercialDocumentType.valueOf(result.getString("tipo")),
                        DocumentStatus.valueOf(result.getString("estado")),
                        result.getBigDecimal("base_total"), result.getBigDecimal("impuesto_total"),
                        result.getBigDecimal("total"), result.getString("terminal_name"), result.getString("user_name")));
        var byId = rows.stream().collect(Collectors.toMap(ExportRow::id, Function.identity()));
        if (byId.size() != ids.size() || !byId.keySet().containsAll(ids)) {
            throw new IllegalArgumentException("Los documentos seleccionados no están disponibles para este cliente");
        }
        return ids.stream().map(byId::get).toList();
    }

    public static Set<CommercialDocumentType> allowedTypes(String reportKey, Authentication authentication) {
        var permission = switch (reportKey) {
            case "tickets" -> "TICKETS_READ";
            case "invoices" -> "INVOICES_READ";
            case "delivery-notes" -> "DELIVERY_NOTES_READ";
            default -> throw new IllegalArgumentException("Tipo de historial de cliente no válido");
        };
        if (!PermissionChecks.hasSalesDocumentRead(authentication, permission)) {
            throw new AccessDeniedException("Sin permiso para consultar estos documentos");
        }
        return switch (reportKey) {
            case "tickets" -> EnumSet.of(CommercialDocumentType.TICKET);
            case "invoices" -> EnumSet.of(CommercialDocumentType.FACTURA_VENTA, CommercialDocumentType.RECTIFICATIVA_VENTA);
            default -> EnumSet.of(CommercialDocumentType.ALBARAN_VENTA);
        };
    }

    private static boolean hasFilters(CustomerDocumentReportFilter filter) {
        return filter.search() != null || filter.status() != null || filter.dateFrom() != null || filter.dateTo() != null;
    }

    private static void validateRequest(CustomerDocumentExportRequest request,
            Set<CommercialDocumentType> types, boolean filtered) {
        validatePresentation(request, types, COLUMNS, EnumSet.allOf(DocumentStatus.class));
        if (filtered ? request.documentIds() != null : request.documentIds() == null) {
            throw new IllegalArgumentException("La selección de documentos no corresponde a los filtros");
        }
        if (!filtered) {
            if (request.documentIds().size() > MAX_ROWS) throw new ExportLimitExceededException();
            if (request.documentIds().stream().anyMatch(java.util.Objects::isNull)
                    || new HashSet<>(request.documentIds()).size() != request.documentIds().size()) {
                throw new IllegalArgumentException("La selección de documentos contiene identificadores no válidos");
            }
        }
    }

    public static void validateReceivedPresentation(CustomerDocumentExportRequest request,
            Set<CommercialDocumentType> types) {
        validatePresentation(request, types, RECEIVED_COLUMNS, EnumSet.complementOf(EnumSet.of(DocumentStatus.BORRADOR)));
        if (request.columns().stream().noneMatch(column -> column.key().equals("currency"))) {
            throw new IllegalArgumentException("La exportación central debe identificar la moneda");
        }
    }

    private static void validatePresentation(CustomerDocumentExportRequest request,
            Set<CommercialDocumentType> types, Set<String> allowedColumns, Set<DocumentStatus> requiredStatuses) {
        if (request.customerId() == null || request.columns() == null || request.columns().isEmpty()
                || request.columns().size() > allowedColumns.size() || request.labels() == null) {
            throw new IllegalArgumentException("La configuración de exportación no es válida");
        }
        var seen = new HashSet<String>();
        for (var column : request.columns()) {
            if (column == null || column.key() == null || !allowedColumns.contains(column.key()) || !seen.add(column.key())
                    || !validLabel(column.label())) {
                throw new IllegalArgumentException("Columna de exportación no válida");
            }
        }
        if (!validLabel(request.labels().sheetName())
                || !validLabel(request.labels().customerCode()) || !validLabel(request.labels().customerTaxId())
                || !validLabel(request.labels().customerName()) || !validLabel(request.labels().grandTotal())
                || !validLabel(request.labels().filters().title()) || !validLabel(request.labels().filters().search())
                || !validLabel(request.labels().filters().status()) || !validLabel(request.labels().filters().dateFrom())
                || !validLabel(request.labels().filters().dateTo()) || !validLabel(request.labels().filters().none())
                || seen.contains("type") && (request.labels().types() == null
                    || types.stream().anyMatch(type -> !validLabel(request.labels().types().get(type))))
                || (seen.contains("status") || request.queryFilter().status() != null) && (request.labels().statuses() == null
                    || requiredStatuses.stream()
                        .anyMatch(status -> !validLabel(request.labels().statuses().get(status))))) {
            throw new IllegalArgumentException("Las etiquetas de exportación no son válidas");
        }
    }

    private static boolean validLabel(String value) {
        return value != null && !value.isBlank() && value.length() <= 100;
    }

    private static void writeHeader(Sheet sheet, List<CustomerDocumentExportRequest.Column> columns, Styles styles) {
        var header = sheet.createRow(HEADER_ROW);
        header.setHeightInPoints(25);
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
            var cell = header.createCell(index);
            cell.setCellValue(column.label());
            cell.setCellStyle(styles.header());
            int width = switch (column.key()) {
                case "number" -> 25;
                case "date" -> 14;
                case "type", "status" -> 20;
                case "terminal", "user" -> 24;
                default -> 17;
            };
            sheet.setColumnWidth(index, Math.min(40, Math.max(width, column.label().length() + 3)) * 256);
        }
    }

    private static void writeCustomer(Sheet sheet, Customer customer, CustomerDocumentExportRequest request,
            CustomerDocumentReportFilter filter, Styles styles) {
        writeCustomer(sheet, new String[]{customer.getClientId(), customer.getDocumentNumber(), customer.getFiscalName()},
                request, filter, styles);
    }

    private static void writeCustomer(Sheet sheet, String[] values, CustomerDocumentExportRequest request,
            CustomerDocumentReportFilter filter, Styles styles) {
        String[] labels = { request.labels().customerCode(), request.labels().customerTaxId(), request.labels().customerName() };
        for (int index = 0; index < labels.length; index++) {
            var row = sheet.createRow(index);
            row.setHeightInPoints(index == 2 ? 32 : 25);
            var label = row.createCell(0);
            label.setCellValue(labels[index]);
            label.setCellStyle(styles.customerLabel());
            var value = row.createCell(1);
            value.setCellValue(values[index] == null ? "" : values[index]);
            value.setCellStyle(styles.customerValue());
            if (request.columns().size() > 2) {
                sheet.addMergedRegion(new CellRangeAddress(index, index, 1, request.columns().size() - 1));
            }
        }
        var filterLabels = request.labels().filters();
        var criteria = new ArrayList<String>();
        if (filter.search() != null) criteria.add(filterLabels.search() + ": " + filter.search());
        if (filter.status() != null) criteria.add(filterLabels.status() + ": " + request.labels().statuses().get(filter.status()));
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if (filter.dateFrom() != null) criteria.add(filterLabels.dateFrom() + ": " + filter.dateFrom().format(dateFormat));
        if (filter.dateTo() != null) criteria.add(filterLabels.dateTo() + ": " + filter.dateTo().format(dateFormat));
        var filterRow = sheet.createRow(3);
        filterRow.setHeightInPoints(32);
        var filterCell = filterRow.createCell(0);
        filterCell.setCellValue(filterLabels.title() + ": " + (criteria.isEmpty() ? filterLabels.none() : String.join("; ", criteria)));
        filterCell.setCellStyle(styles.customerValue());
        sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, Math.max(1, request.columns().size() - 1)));
    }

    private static ExportSummary writeRows(Sheet sheet, CustomerDocumentExportRequest request,
            List<ExportRow> rows, ExportSummary summary, Styles styles) {
        int count = summary.count();
        var total = summary.total();
        for (var row : rows) {
            var excelRow = sheet.createRow(HEADER_ROW + ++count);
            total = total.add(row.total());
            excelRow.setHeightInPoints(20);
            for (int index = 0; index < request.columns().size(); index++) {
                var key = request.columns().get(index).key();
                Object value = switch (key) {
                    case "number" -> row.number();
                    case "date" -> row.date();
                    case "type" -> request.labels().types().get(row.type());
                    case "status" -> request.labels().statuses().get(row.status());
                    case "base" -> row.base();
                    case "tax" -> row.tax();
                    case "total" -> row.total();
                    case "terminal" -> nameOrPlaceholder(row.terminal());
                    case "user" -> nameOrPlaceholder(row.user());
                    case "store" -> nameOrPlaceholder(row.store());
                    case "currency" -> row.currency();
                    default -> throw new IllegalArgumentException("Columna de exportación no válida");
                };
                writeValue(excelRow.createCell(index), value, styles);
            }
        }
        return new ExportSummary(count, total);
    }

    private static void writeTotal(Sheet sheet, CustomerDocumentExportRequest request, ExportSummary summary, Styles styles) {
        int totalColumn = -1;
        for (int index = 0; index < request.columns().size(); index++) {
            if (request.columns().get(index).key().equals("total")) totalColumn = index;
        }
        var row = sheet.createRow(HEADER_ROW + summary.count() + 1);
        row.setHeightInPoints(30);
        for (int index = 0; index < Math.max(2, request.columns().size()); index++) {
            row.createCell(index).setCellStyle(styles.totalLabel());
        }
        var label = row.getCell(totalColumn == 0 ? 1 : 0);
        label.setCellValue(request.labels().grandTotal());
        var amount = row.getCell(totalColumn < 0 ? 1 : totalColumn);
        if (totalColumn >= 0 && summary.count() > 0) {
            var letter = CellReference.convertNumToColString(totalColumn);
            amount.setCellFormula("SUM(" + letter + (HEADER_ROW + 2) + ":" + letter
                    + (HEADER_ROW + summary.count() + 1) + ")");
        }
        // Cache the exact decimal sum; SXSSF has already flushed earlier pages.
        // Excel can still recalculate this SUM when the exported rows are edited.
        amount.setCellValue(summary.total().doubleValue());
        amount.setCellStyle(styles.totalAmount());
    }

    private record ExportSummary(int count, BigDecimal total) {
    }

    private static void writeValue(Cell cell, Object value, Styles styles) {
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
            cell.setCellStyle(styles.amount());
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date.atStartOfDay());
            cell.setCellStyle(styles.date());
        } else {
            // Always write text as a string, including values beginning with '=' or '+'.
            cell.setCellValue(value == null ? "" : value.toString());
            cell.setCellStyle(styles.body());
        }
    }

    private static String nameOrPlaceholder(String name) {
        return name == null || name.isEmpty() ? "—" : name;
    }

    public record ExportRow(UUID id, String number, LocalDate date, CommercialDocumentType type,
            DocumentStatus status, BigDecimal base, BigDecimal tax, BigDecimal total, String terminal, String user,
            String store, String currency) {
        ExportRow(UUID id, String number, LocalDate date, CommercialDocumentType type,
                DocumentStatus status, BigDecimal base, BigDecimal tax, BigDecimal total, String terminal, String user) {
            this(id, number, date, type, status, base, tax, total, terminal, user, null, null);
        }
    }

    /** Presentation only: received rows never trigger local document queries or an audit/database write. */
    public byte[] renderReceived(CustomerDocumentExportRequest request, String customerCode,
            String customerTaxId, String customerName, List<ExportRow> rows) {
        if (rows.size() > MAX_ROWS) throw new ExportLimitExceededException();
        var types = rows.stream().map(ExportRow::type).collect(Collectors.toSet());
        validateReceivedPresentation(request, types);
        var workbook = new SXSSFWorkbook(PAGE_SIZE);
        workbook.setCompressTempFiles(true);
        try (workbook; var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(request.labels().sheetName()));
            try {
                sheet.setDisplayGridlines(false);
                var styles = Styles.create(workbook);
                // Currency is explicit in each row and total; do not apply the legacy EUR-only format.
                styles.amount().setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
                styles.totalAmount().setDataFormat(styles.amount().getDataFormat());
                writeHeader(sheet, request.columns(), styles);
                writeCustomer(sheet, new String[]{customerCode, customerTaxId, customerName}, request, request.queryFilter(), styles);
                var totals = new java.util.TreeMap<String, BigDecimal>();
                int count = 0;
                for (var row : rows) {
                    if (row.currency() == null || !row.currency().matches("[A-Z]{3}")) {
                        throw new IllegalArgumentException("Moneda documental no válida");
                    }
                    // Reuse row layout with exact decimal cells in this received-data path.
                    writeRows(sheet, request, List.of(row), new ExportSummary(count++, BigDecimal.ZERO), styles);
                    var excelRow = sheet.getRow(HEADER_ROW + count);
                    for (int index = 0; index < request.columns().size(); index++) {
                        BigDecimal decimal = switch (request.columns().get(index).key()) {
                            case "base" -> row.base(); case "tax" -> row.tax(); case "total" -> row.total(); default -> null;
                        };
                        if (decimal != null) exactNumber(excelRow.getCell(index), decimal);
                    }
                    totals.merge(row.currency(), row.total(), BigDecimal::add);
                }
                int totalColumn = java.util.stream.IntStream.range(0, request.columns().size())
                        .filter(index -> request.columns().get(index).key().equals("total")).findFirst().orElse(-1);
                int amountColumn = totalColumn < 0 ? 1 : totalColumn;
                int currencyColumn = java.util.stream.IntStream.range(0, request.columns().size())
                        .filter(index -> request.columns().get(index).key().equals("currency")).findFirst().orElseThrow();
                int totalRow = HEADER_ROW + count;
                for (var entry : totals.entrySet()) {
                    var row = sheet.createRow(++totalRow);
                    row.setHeightInPoints(30);
                    for (int index = 0; index < Math.max(2, request.columns().size()); index++) {
                        row.createCell(index).setCellStyle(styles.totalLabel());
                    }
                    row.getCell(amountColumn == 0 ? 1 : 0).setCellValue(request.labels().grandTotal() + " (" + entry.getKey() + ")");
                    var amount = row.getCell(amountColumn);
                    // Text fallback must not be silently omitted by an Excel SUM/SUMIFS formula.
                    boolean numeric = rows.stream().filter(value -> value.currency().equals(entry.getKey()))
                            .allMatch(value -> exactlyRepresentable(value.total()));
                    if (numeric && exactlyRepresentable(entry.getValue()) && count > 0 && totalColumn >= 0) {
                        String amounts = CellReference.convertNumToColString(totalColumn);
                        String currencies = CellReference.convertNumToColString(currencyColumn);
                        amount.setCellFormula("SUMIFS(" + amounts + "6:" + amounts + (HEADER_ROW + count + 1)
                                + "," + currencies + "6:" + currencies + (HEADER_ROW + count + 1) + ",\"" + entry.getKey() + "\")");
                    }
                    exactNumber(amount, entry.getValue());
                    amount.setCellStyle(styles.totalAmount());
                }
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + count, 0, request.columns().size() - 1));
                sheet.createFreezePane(0, HEADER_ROW + 1);
                workbook.write(output);
                return output.toByteArray();
            } finally { sheet.flushRows(); }
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudieron exportar los documentos del cliente", exception);
        }
    }

    private static boolean exactlyRepresentable(BigDecimal value) {
        return value.stripTrailingZeros().precision() <= 15 && Double.isFinite(value.doubleValue())
                && BigDecimal.valueOf(value.doubleValue()).compareTo(value) == 0;
    }

    private static void exactNumber(Cell cell, BigDecimal value) {
        if (exactlyRepresentable(value)) cell.setCellValue(value.doubleValue());
        else cell.setCellValue(value.toPlainString());
    }

    static final class ExportLimitExceededException extends IllegalArgumentException {
        ExportLimitExceededException() {
            super("customer_documents_export_limit_exceeded");
        }
    }

    private record Styles(CellStyle header, CellStyle body, CellStyle date, CellStyle amount,
            CellStyle customerLabel, CellStyle customerValue, CellStyle totalLabel, CellStyle totalAmount) {
        static Styles create(Workbook workbook) {
            var body = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setFontName("Arial");
            font.setFontHeightInPoints((short) 10);
            body.setFont(font);
            body.setVerticalAlignment(VerticalAlignment.CENTER);
            body.setBorderBottom(BorderStyle.HAIR);
            body.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            body.setAlignment(HorizontalAlignment.LEFT);

            var header = workbook.createCellStyle();
            header.cloneStyleFrom(body);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            var headerFont = workbook.createFont();
            headerFont.setFontName("Arial");
            headerFont.setFontHeightInPoints((short) 10);
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.BLACK.getIndex());
            header.setFont(headerFont);

            var date = workbook.createCellStyle();
            date.cloneStyleFrom(body);
            date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            var amount = workbook.createCellStyle();
            amount.cloneStyleFrom(body);
            amount.setAlignment(HorizontalAlignment.RIGHT);
            amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-x-euro2]"));
            var customerValue = workbook.createCellStyle();
            customerValue.cloneStyleFrom(body);
            customerValue.setBorderBottom(BorderStyle.NONE);
            customerValue.setWrapText(true);
            var customerLabel = workbook.createCellStyle();
            customerLabel.cloneStyleFrom(customerValue);
            customerLabel.setFont(headerFont);
            var totalLabel = workbook.createCellStyle();
            totalLabel.cloneStyleFrom(header);
            totalLabel.setWrapText(true);
            totalLabel.setBorderTop(BorderStyle.THIN);
            totalLabel.setTopBorderColor(IndexedColors.GREY_50_PERCENT.getIndex());
            var totalAmount = workbook.createCellStyle();
            totalAmount.cloneStyleFrom(totalLabel);
            totalAmount.setAlignment(HorizontalAlignment.RIGHT);
            totalAmount.setDataFormat(amount.getDataFormat());
            return new Styles(header, body, date, amount, customerLabel, customerValue, totalLabel, totalAmount);
        }
    }
}
