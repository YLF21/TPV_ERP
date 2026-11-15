package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.CommercialDocumentType;
import com.tpverp.backend.document.CustomerDocumentReportFilter;
import com.tpverp.backend.document.CustomerDocumentReportQueryRepository;
import com.tpverp.backend.document.DocumentStatus;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.Store;
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
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
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
    private static final int HEADER_ROW = 8;
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
        var store = organization.currentStore();
        var company = organization.currentCompany();
        var storeId = store.getId();
        var customer = customers.findByIdAndCompanyId(request.customerId(), company.getId())
                .orElseThrow(() -> new IllegalArgumentException("Cliente no encontrado"));

        var workbook = new SXSSFWorkbook(PAGE_SIZE);
        workbook.setCompressTempFiles(true);
        try (workbook; var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(WorkbookUtil.createSafeSheetName(request.labels().sheetName()));
            try {
                sheet.setDisplayGridlines(false);
                var styles = Styles.create(workbook);
                setColumnWidths(sheet, request.columns());
                writePresentation(sheet, request, styles, company, store);
                writeHeader(sheet, request.columns(), styles);
                writeCustomer(sheet, customer, request, filter, styles);
                var summary = filtered
                        ? writeFiltered(sheet, request, storeId, types, filter, styles)
                        : writeLoaded(sheet, request, storeId, types, styles);
                writeTotal(sheet, request, summary, styles);
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + summary.count(), 0, request.columns().size() - 1));
                sheet.createFreezePane(0, HEADER_ROW + 1);
                configurePrint(sheet, request.columns().size(), presentationLocale(request));
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
        header.setHeightInPoints(31);
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
            var cell = header.createCell(index);
            cell.setCellValue(column.label());
            cell.setCellStyle(styles.header());
        }
    }

    private static void setColumnWidths(Sheet sheet, List<CustomerDocumentExportRequest.Column> columns) {
        for (int index = 0; index < columns.size(); index++) {
            var column = columns.get(index);
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

    private static void writePresentation(Sheet sheet, CustomerDocumentExportRequest request, Styles styles,
            Company company, Store store) {
        writeTitle(sheet, request, styles);
        String locale = presentationLocale(request);
        if (store == null) {
            writeMetadata(sheet, 1, tr(locale, "Ámbito", "Scope", "范围"),
                    tr(locale, "Todas las tiendas", "All stores", "所有门店"), request.columns().size(), styles);
            sheet.createRow(2).setHeightInPoints(9);
        } else {
            writeMetadata(sheet, 1, tr(locale, "Empresa", "Company", "公司"),
                    company == null ? "" : company.getRazonSocial() + " · " + company.getTaxId(),
                    request.columns().size(), styles);
            writeMetadata(sheet, 2, tr(locale, "Tienda", "Store", "门店"),
                    store.getCodigoTienda() + " · " + store.getNombreEfectivo(), request.columns().size(), styles);
        }
        sheet.createRow(7).setHeightInPoints(9);
    }

    private static void writeTitle(Sheet sheet, CustomerDocumentExportRequest request, Styles styles) {
        var row = sheet.createRow(0);
        for (int index = 0; index < request.columns().size(); index++) {
            row.createCell(index).setCellStyle(styles.title());
        }
        row.getCell(0).setCellValue(request.labels().sheetName());
        if (request.columns().size() > 1) {
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, request.columns().size() - 1));
        }
        setReadableHeight(row, 36, wrappedLines(sheet, request.labels().sheetName(), 0,
                request.columns().size() - 1, 18), 24);
    }

    private static void writeMetadata(Sheet sheet, int rowNumber, String label, String value,
            int columns, Styles styles) {
        var row = sheet.createRow(rowNumber);
        var name = row.createCell(0);
        String displayLabel = columns == 1 ? label + ": " + value : label;
        name.setCellValue(displayLabel);
        name.setCellStyle(styles.customerLabel());
        if (columns > 1) {
            var content = row.createCell(1);
            content.setCellValue(value);
            content.setCellStyle(styles.customerValue());
            if (columns > 2) sheet.addMergedRegion(new CellRangeAddress(rowNumber, rowNumber, 1, columns - 1));
        }
        int labelLines = wrappedLines(sheet, displayLabel, 0, 0, 10);
        int valueLines = columns == 1 ? 0 : wrappedLines(sheet, value, 1, columns - 1, 10);
        setReadableHeight(row, 27, Math.max(labelLines, valueLines), 15);
    }

    private static int wrappedLines(Sheet sheet, String value, int firstColumn, int lastColumn, int fontSize) {
        int characters = 0;
        for (int column = firstColumn; column <= lastColumn; column++) {
            characters += Math.max(1, sheet.getColumnWidth(column) / 256);
        }
        characters = Math.max(1, (characters - 3) * 8 / fontSize);
        int lines = 0;
        for (String segment : (value == null ? "" : value).split("\\R", -1)) {
            int units = segment.codePoints().map(codePoint -> {
                var script = Character.UnicodeScript.of(codePoint);
                return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                        || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                        ? 2 : 1;
            }).sum();
            lines += Math.max(1, (units + characters - 1) / characters);
        }
        return lines;
    }

    private static void setReadableHeight(org.apache.poi.ss.usermodel.Row row, int minimum, int lines, int lineHeight) {
        row.setHeightInPoints(Math.min(409, Math.max(minimum, lines * lineHeight + 8)));
    }

    private static void configurePrint(Sheet sheet, int columns, String locale) {
        sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));
        sheet.setPrintGridlines(false);
        sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.getPrintSetup().setLandscape(columns > 5);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
        sheet.setFitToPage(true);
        sheet.getFooter().setRight(tr(locale, "Página &P de &N", "Page &P of &N", "第 &P / &N 页"));
    }

    private static String presentationLocale(CustomerDocumentExportRequest request) {
        String label = request.labels().customerCode();
        return label.contains("客户") ? "zh" : label.toLowerCase(java.util.Locale.ROOT).contains("customer") ? "en" : "es";
    }

    private static String tr(String locale, String es, String en, String zh) {
        String language = locale == null ? "es" : locale.toLowerCase(java.util.Locale.ROOT);
        return language.startsWith("zh") ? zh : language.startsWith("en") ? en : es;
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
            int rowNumber = index + 3;
            var row = sheet.createRow(rowNumber);
            var label = row.createCell(0);
            label.setCellValue(labels[index]);
            label.setCellStyle(styles.customerLabel());
            var value = row.createCell(1);
            value.setCellValue(values[index] == null ? "" : values[index]);
            value.setCellStyle(styles.customerValue());
            if (request.columns().size() > 2) {
                sheet.addMergedRegion(new CellRangeAddress(rowNumber, rowNumber, 1, request.columns().size() - 1));
            }
            int labelLines = wrappedLines(sheet, labels[index], 0, 0, 10);
            int valueLines = wrappedLines(sheet, values[index], 1, Math.max(1, request.columns().size() - 1), 10);
            setReadableHeight(row, index == 2 ? 32 : 25, Math.max(labelLines, valueLines), 15);
        }
        var filterLabels = request.labels().filters();
        var criteria = new ArrayList<String>();
        if (filter.search() != null) criteria.add(filterLabels.search() + ": " + filter.search());
        if (filter.status() != null) criteria.add(filterLabels.status() + ": " + request.labels().statuses().get(filter.status()));
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        if (filter.dateFrom() != null) criteria.add(filterLabels.dateFrom() + ": " + filter.dateFrom().format(dateFormat));
        if (filter.dateTo() != null) criteria.add(filterLabels.dateTo() + ": " + filter.dateTo().format(dateFormat));
        var filterRow = sheet.createRow(6);
        var filterCell = filterRow.createCell(0);
        String filterText = filterLabels.title() + ": " + (criteria.isEmpty() ? filterLabels.none() : String.join("; ", criteria));
        filterCell.setCellValue(filterText);
        filterCell.setCellStyle(styles.customerValue());
        sheet.addMergedRegion(new CellRangeAddress(6, 6, 0, Math.max(1, request.columns().size() - 1)));
        setReadableHeight(filterRow, 32, wrappedLines(sheet, filterText, 0,
                Math.max(1, request.columns().size() - 1), 10), 15);
    }

    private static ExportSummary writeRows(Sheet sheet, CustomerDocumentExportRequest request,
            List<ExportRow> rows, ExportSummary summary, Styles styles) {
        int count = summary.count();
        var total = summary.total();
        for (var row : rows) {
            var excelRow = sheet.createRow(HEADER_ROW + ++count);
            total = total.add(row.total());
            excelRow.setHeightInPoints(25);
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
                writeValue(excelRow.createCell(index), value, styles, count % 2 == 0);
            }
            fitBodyRow(excelRow);
        }
        return new ExportSummary(count, total);
    }

    private static void fitBodyRow(org.apache.poi.ss.usermodel.Row row) {
        int lines = 1;
        for (var cell : row) {
            if (cell.getCellType() != org.apache.poi.ss.usermodel.CellType.STRING) continue;
            int width = Math.max(1, row.getSheet().getColumnWidth(cell.getColumnIndex()) / 256 - 2);
            int cellLines = 0;
            for (String segment : cell.getStringCellValue().split("\\R", -1)) {
                cellLines += Math.max(1, (segment.length() + width - 1) / width);
            }
            lines = Math.max(lines, cellLines);
        }
        row.setHeightInPoints(Math.min(409, Math.max(25, lines * 15 + 8)));
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

    private static void writeValue(Cell cell, Object value, Styles styles, boolean stripe) {
        if (value instanceof BigDecimal decimal) {
            cell.setCellValue(decimal.doubleValue());
            cell.setCellStyle(stripe ? styles.amountStripe() : styles.amount());
        } else if (value instanceof LocalDate date) {
            cell.setCellValue(date.atStartOfDay());
            cell.setCellStyle(stripe ? styles.dateStripe() : styles.date());
        } else {
            // Always write text as a string, including values beginning with '=' or '+'.
            cell.setCellValue(value == null ? "" : value.toString());
            cell.setCellStyle(stripe ? styles.bodyStripe() : styles.body());
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
                styles.amountStripe().setDataFormat(styles.amount().getDataFormat());
                setColumnWidths(sheet, request.columns());
                writePresentation(sheet, request, styles, null, null);
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
                    fitBodyRow(excelRow);
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
                        int firstRow = HEADER_ROW + 2;
                        int lastRow = HEADER_ROW + count + 1;
                        amount.setCellFormula("SUMIFS(" + amounts + firstRow + ":" + amounts + lastRow
                                + "," + currencies + firstRow + ":" + currencies + lastRow + ",\"" + entry.getKey() + "\")");
                    }
                    exactNumber(amount, entry.getValue());
                    amount.setCellStyle(styles.totalAmount());
                }
                sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, HEADER_ROW + count, 0, request.columns().size() - 1));
                sheet.createFreezePane(0, HEADER_ROW + 1);
                configurePrint(sheet, request.columns().size(), presentationLocale(request));
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

    private record Styles(CellStyle title, CellStyle header, CellStyle body, CellStyle bodyStripe,
            CellStyle date, CellStyle dateStripe, CellStyle amount, CellStyle amountStripe,
            CellStyle customerLabel, CellStyle customerValue, CellStyle totalLabel, CellStyle totalAmount) {
        private static final XSSFColor NAVY = color(10, 47, 86);
        private static final XSSFColor PALE = color(234, 241, 247);
        private static final XSSFColor STRIPE = color(246, 249, 252);
        private static final XSSFColor WHITE = color(255, 255, 255);

        static Styles create(SXSSFWorkbook workbook) {
            var book = workbook.getXSSFWorkbook();
            book.getFontAt(0).setFontName("Calibri");
            var title = style(book, NAVY, WHITE, 18, true, null);
            var header = style(book, NAVY, WHITE, 10, true, null);
            var body = style(book, WHITE, NAVY, 10, false, null);
            var bodyStripe = style(book, STRIPE, NAVY, 10, false, null);
            var date = style(book, WHITE, NAVY, 10, false, "dd/mm/yyyy");
            var dateStripe = style(book, STRIPE, NAVY, 10, false, "dd/mm/yyyy");
            var amount = style(book, WHITE, NAVY, 10, false, "#,##0.00 [$€-x-euro2]");
            var amountStripe = style(book, STRIPE, NAVY, 10, false, "#,##0.00 [$€-x-euro2]");
            var customerLabel = style(book, PALE, NAVY, 10, true, null);
            var customerValue = style(book, PALE, NAVY, 10, false, null);
            var totalLabel = style(book, NAVY, WHITE, 13, true, null);
            var totalAmount = style(book, NAVY, WHITE, 13, true, "#,##0.00 [$€-x-euro2]");
            return new Styles(title, header, body, bodyStripe, date, dateStripe, amount, amountStripe,
                    customerLabel, customerValue, totalLabel, totalAmount);
        }

        private static XSSFCellStyle style(org.apache.poi.xssf.usermodel.XSSFWorkbook book,
                XSSFColor background, XSSFColor foreground, int size, boolean bold, String format) {
            var style = book.createCellStyle();
            style.setFillForegroundColor(background);
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            XSSFFont font = book.createFont();
            font.setFontName("Calibri");
            font.setFontHeightInPoints((short) size);
            font.setBold(bold);
            font.setColor(foreground);
            style.setFont(font);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            style.setAlignment(format == null ? HorizontalAlignment.LEFT : HorizontalAlignment.RIGHT);
            if (format != null) style.setDataFormat(book.createDataFormat().getFormat(format));
            return style;
        }

        private static XSSFColor color(int red, int green, int blue) {
            return new XSSFColor(new byte[]{(byte) red, (byte) green, (byte) blue}, null);
        }
    }
}
