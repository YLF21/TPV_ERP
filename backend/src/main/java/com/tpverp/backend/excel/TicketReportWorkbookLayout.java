package com.tpverp.backend.excel;

import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Presentation of filtered, permission-checked sales document rows. */
final class TicketReportWorkbookLayout {
    private static final int HEADER_ROW = 8;
    private static final float MAX_EXCEL_ROW_HEIGHT = 409f;
    private static final Set<String> MONEY = Set.of(
            "total", "subtotal", "pending", "base", "tax", "discount", "memberBalance");
    private static final XSSFColor NAVY = rgb(10, 47, 86);
    private static final XSSFColor PALE = rgb(234, 241, 247);
    private static final XSSFColor WHITE = rgb(255, 255, 255);
    private static final XSSFColor STRIPE = rgb(246, 249, 252);

    private TicketReportWorkbookLayout() { }

    static boolean supports(String reportKey) {
        return Set.of("salesReport.tickets", "salesReport.invoices", "salesReport.deliveryNotes",
                "salesReport.inputInvoices", "salesReport.inputDeliveryNotes",
                "salesReport.inputWarehouse", "salesReport.warehouseOutputs")
                .contains(reportKey);
    }

    private static String documentName(String reportKey) {
        return switch (reportKey) {
            case "salesReport.invoices" -> "Facturas";
            case "salesReport.deliveryNotes" -> "Albaranes";
            case "salesReport.inputInvoices" -> "Facturas de compra";
            case "salesReport.inputDeliveryNotes" -> "Albaranes de compra";
            case "salesReport.inputWarehouse" -> "Entradas de almacén";
            case "salesReport.warehouseOutputs" -> "Salidas de almacén";
            default -> "Tickets";
        };
    }

    static byte[] write(SalesReportExportRequest request, List<Map<String, Object>> rows,
            CurrentOrganization organization) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.getFontAt(0).setFontName("Calibri");
            var sheet = workbook.createSheet(documentName(request.reportKey()));
            var columns = request.columns();
            int count = columns.size();
            var styles = styles(workbook);
            for (int column = 0; column < count; column++) {
                var key = columns.get(column).key();
                int width = switch (key) {
                    case "comment", "customerName", "supplierName" -> 36;
                    case "payment", "user", "terminal", "warehouse" -> 23;
                    case "ticket", "invoice", "deliveryNote", "input", "output", "customer", "supplier", "invoiced" -> 19;
                    default -> 16;
                };
                sheet.setColumnWidth(column, (count == 1 ? Math.max(width, 36) : width) * 256);
            }
            heading(sheet, request, organization, styles, count);
            var header = sheet.createRow(HEADER_ROW);
            header.setHeightInPoints(30);
            for (int column = 0; column < count; column++) {
                var cell = header.createCell(column);
                cell.setCellValue(columns.get(column).label());
                cell.setCellStyle(MONEY.contains(columns.get(column).key())
                        ? styles.moneyHeader() : styles.header());
            }
            var sums = new BigDecimal[count];
            java.util.Arrays.fill(sums, BigDecimal.ZERO);
            BigDecimal effectiveTotal = BigDecimal.ZERO;
            for (int index = 0; index < rows.size(); index++) {
                var source = rows.get(index);
                var row = sheet.createRow(HEADER_ROW + index + 1);
                int maxLines = 1;
                for (int column = 0; column < count; column++) {
                    String key = columns.get(column).key();
                    Object value = source.get(key);
                    var cell = row.createCell(column);
                    boolean money = MONEY.contains(key);
                    cell.setCellStyle(money
                            ? (index % 2 == 0 ? styles.money() : styles.moneyStripe())
                            : (index % 2 == 0 ? styles.body() : styles.bodyStripe()));
                    if (value instanceof Number number) {
                        if ("globalDiscount".equals(key)) {
                            var percentage = new BigDecimal(number.toString());
                            cell.setCellValue(percentage.movePointLeft(2).doubleValue());
                            cell.setCellStyle(percentage.stripTrailingZeros().scale() <= 0
                                    ? (index % 2 == 0 ? styles.wholePercent() : styles.wholePercentStripe())
                                    : (index % 2 == 0 ? styles.percent() : styles.percentStripe()));
                        } else {
                            cell.setCellValue(number.doubleValue());
                        }
                        if (money && !"total".equals(key)) {
                            sums[column] = sums[column].add(new BigDecimal(number.toString()));
                        }
                    } else {
                        String text = value == null ? "" : String.valueOf(value);
                        cell.setCellValue(text);
                        maxLines = Math.max(maxLines, lines(text, sheet.getColumnWidth(column) / 256 - 3));
                    }
                }
                if (source instanceof SalesReportExcelExportService.TicketExportRow ticket) {
                    effectiveTotal = effectiveTotal.add(ticket.effectiveTotal());
                } else if (source.get("total") instanceof Number number) {
                    effectiveTotal = effectiveTotal.add(new BigDecimal(number.toString()));
                }
                row.setHeightInPoints(rowHeight(maxLines));
            }
            int totalRowIndex = HEADER_ROW + rows.size() + 1;
            var totalRow = sheet.createRow(totalRowIndex);
            totalRow.setHeightInPoints(34);
            int labelColumn = -1;
            for (int column = 0; column < count; column++) {
                if (labelColumn < 0 && !MONEY.contains(columns.get(column).key())) labelColumn = column;
                totalRow.createCell(column).setCellStyle(styles.totalLabel());
            }
            if (labelColumn < 0) labelColumn = count;
            totalRow.getCell(labelColumn, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                    .setCellValue("TOTAL");
            totalRow.getCell(labelColumn).setCellStyle(styles.totalLabel());
            for (int column = 0; column < count; column++) {
                String key = columns.get(column).key();
                if (!MONEY.contains(key)) continue;
                var amountCell = totalRow.getCell(column);
                amountCell.setCellValue("total".equals(key)
                        ? effectiveTotal.doubleValue() : sums[column].doubleValue());
                amountCell.setCellStyle(styles.totalMoney());
            }
            if (labelColumn == count) sheet.setColumnWidth(count, 19 * 256);
            sheet.createFreezePane(0, HEADER_ROW + 1);
            sheet.setRepeatingRows(new CellRangeAddress(0, HEADER_ROW, -1, -1));
            sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW,
                    HEADER_ROW + Math.max(0, rows.size()), 0, count - 1));
            sheet.setDisplayGridlines(false);
            sheet.setFitToPage(true);
            var print = sheet.getPrintSetup();
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            int lastPrintColumn = Math.max(count - 1, labelColumn);
            double columnWidthPoints = 0;
            for (int column = 0; column <= lastPrintColumn; column++) {
                columnWidthPoints += sheet.getColumnWidthInPixels(column) * 72d / 96d;
            }
            double portraitWidthPoints = 210d / 25.4d * 72d
                    - (sheet.getMargin(PageMargin.LEFT) + sheet.getMargin(PageMargin.RIGHT)) * 72d;
            print.setLandscape(columnWidthPoints > portraitWidthPoints);
            print.setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.getFooter().setCenter("Página &P de &N");
            workbook.setPrintArea(0, 0, lastPrintColumn, 0, totalRowIndex);
            workbook.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("message.sales_report.excel_generation_failed", exception);
        }
    }

    private static void heading(org.apache.poi.ss.usermodel.Sheet sheet,
            SalesReportExportRequest request, CurrentOrganization organization, Styles styles, int count) {
        var title = sheet.createRow(0);
        title.setHeightInPoints(count == 1 ? 45 : 35);
        for (int column = 0; column < count; column++) title.createCell(column).setCellStyle(styles.title());
        title.getCell(0).setCellValue("INFORME DE " + documentName(request.reportKey()).toUpperCase(java.util.Locale.ROOT));
        if (count > 1) sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, count - 1));
        var store = organization.currentStore();
        var company = organization.currentCompany();
        Map<String, String> address = store.getDireccion();
        String addressText = address == null ? "" : Stream.of(
                "linea1", "linea2", "codigoPostal", "ciudad", "provincia", "pais")
                .map(address::get).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
        var filters = request.filters();
        String period = filters == null ? "Todos" : period(filters.dateFrom(), filters.dateTo());
        String issued = ZonedDateTime.now(ZoneId.of(store.getTimezone()))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        String[][] metadata = {
                {"Empresa", safe(company.getRazonSocial())}, {"NIF", safe(company.getTaxId())},
                {"Tienda", safe(store.getCodigoTienda()) + " - " + safe(store.getNombreEfectivo())},
                {"Dirección", addressText}, {"Periodo", period}, {"Emitido", issued}
        };
        int valueWidth = 0;
        for (int column = 1; column < count; column++) valueWidth += sheet.getColumnWidth(column) / 256;
        for (int index = 0; index < metadata.length; index++) {
            var row = sheet.createRow(index + 1);
            if (count == 1) {
                String value = metadata[index][0] + ": " + metadata[index][1];
                var cell = row.createCell(0);
                cell.setCellValue(value);
                cell.setCellStyle(styles.metadataLabel());
                row.setHeightInPoints(rowHeight(lines(value, sheet.getColumnWidth(0) / 256 - 2)));
            } else {
                row.createCell(0).setCellValue(metadata[index][0]);
                row.getCell(0).setCellStyle(styles.metadataLabel());
                for (int column = 1; column < count; column++) {
                    row.createCell(column).setCellStyle(styles.metadataValue());
                }
                row.getCell(1).setCellValue(metadata[index][1]);
                if (count > 2) sheet.addMergedRegion(new CellRangeAddress(index + 1, index + 1, 1, count - 1));
                row.setHeightInPoints(rowHeight(lines(metadata[index][1], valueWidth - 3)));
            }
        }
        sheet.createRow(7).setHeightInPoints(10);
    }

    private static String period(String from, String to) {
        var formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        String first = from == null || from.isBlank() ? "" : java.time.LocalDate.parse(from).format(formatter);
        String last = to == null || to.isBlank() ? "" : java.time.LocalDate.parse(to).format(formatter);
        if (first.isBlank() && last.isBlank()) return "Todos";
        if (first.isBlank()) return "Hasta " + last;
        if (last.isBlank()) return "Desde " + first;
        return first + " - " + last;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static int lines(String value, int width) {
        if (value == null || value.isEmpty()) return 1;
        int usable = Math.max(8, width);
        int lines = 0;
        for (String explicitLine : value.split("\\R", -1)) {
            lines += Math.max(1, (int) Math.ceil((double) explicitLine.length() / usable));
        }
        return lines;
    }

    private static float rowHeight(int lines) {
        // Excel limits row heights to 409 pt; the full value remains in the cell above that limit.
        return Math.min(MAX_EXCEL_ROW_HEIGHT, Math.max(24f, lines * 15f + 8f));
    }

    private static Styles styles(XSSFWorkbook workbook) {
        var navyFont = workbook.createFont();
        navyFont.setFontName("Calibri");
        navyFont.setColor(NAVY);
        navyFont.setFontHeightInPoints((short) 10);
        var bold = workbook.createFont();
        bold.setFontName("Calibri");
        bold.setColor(NAVY);
        bold.setFontHeightInPoints((short) 10);
        bold.setBold(true);
        var whiteBold = workbook.createFont();
        whiteBold.setFontName("Calibri");
        whiteBold.setColor(WHITE);
        whiteBold.setBold(true);
        whiteBold.setFontHeightInPoints((short) 10);
        var titleFont = workbook.createFont();
        titleFont.setFontName("Calibri");
        titleFont.setColor(WHITE);
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 18);
        var totalFont = workbook.createFont();
        totalFont.setFontName("Calibri");
        totalFont.setColor(NAVY);
        totalFont.setBold(true);
        totalFont.setFontHeightInPoints((short) 14);
        short moneyFormat = workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]");
        short percentFormat = workbook.createDataFormat().getFormat("0.##%");
        short wholePercentFormat = workbook.createDataFormat().getFormat("0%");
        var title = style(workbook, NAVY, titleFont, HorizontalAlignment.LEFT, (short) -1);
        var header = style(workbook, NAVY, whiteBold, HorizontalAlignment.LEFT, (short) -1);
        var moneyHeader = style(workbook, NAVY, whiteBold, HorizontalAlignment.RIGHT, (short) -1);
        var metaLabel = style(workbook, PALE, bold, HorizontalAlignment.LEFT, (short) -1);
        var metaValue = style(workbook, PALE, navyFont, HorizontalAlignment.LEFT, (short) -1);
        var body = style(workbook, WHITE, navyFont, HorizontalAlignment.LEFT, (short) -1);
        var bodyStripe = style(workbook, STRIPE, navyFont, HorizontalAlignment.LEFT, (short) -1);
        var money = style(workbook, WHITE, navyFont, HorizontalAlignment.RIGHT, moneyFormat);
        var moneyStripe = style(workbook, STRIPE, navyFont, HorizontalAlignment.RIGHT, moneyFormat);
        var percent = style(workbook, WHITE, navyFont, HorizontalAlignment.RIGHT, percentFormat);
        var percentStripe = style(workbook, STRIPE, navyFont, HorizontalAlignment.RIGHT, percentFormat);
        var wholePercent = style(workbook, WHITE, navyFont, HorizontalAlignment.RIGHT, wholePercentFormat);
        var wholePercentStripe = style(workbook, STRIPE, navyFont, HorizontalAlignment.RIGHT, wholePercentFormat);
        var totalLabel = style(workbook, PALE, totalFont, HorizontalAlignment.LEFT, (short) -1);
        var totalMoney = style(workbook, PALE, totalFont, HorizontalAlignment.RIGHT, moneyFormat);
        totalLabel.setBorderTop(BorderStyle.MEDIUM);
        totalMoney.setBorderTop(BorderStyle.MEDIUM);
        totalLabel.setTopBorderColor(NAVY);
        totalMoney.setTopBorderColor(NAVY);
        return new Styles(title, header, moneyHeader, metaLabel, metaValue, body, bodyStripe,
                money, moneyStripe, percent, percentStripe, wholePercent, wholePercentStripe,
                totalLabel, totalMoney);
    }

    private static XSSFCellStyle style(XSSFWorkbook workbook, XSSFColor background,
            org.apache.poi.xssf.usermodel.XSSFFont font, HorizontalAlignment alignment, short format) {
        var style = workbook.createCellStyle();
        style.setFillForegroundColor(background);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(font);
        style.setAlignment(alignment);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        if (format >= 0) style.setDataFormat(format);
        return style;
    }

    private static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[] {(byte) r, (byte) g, (byte) b});
    }

    private record Styles(CellStyle title, CellStyle header, CellStyle moneyHeader, CellStyle metadataLabel,
            CellStyle metadataValue, CellStyle body, CellStyle bodyStripe, CellStyle money,
            CellStyle moneyStripe, CellStyle percent, CellStyle percentStripe,
            CellStyle wholePercent, CellStyle wholePercentStripe,
            XSSFCellStyle totalLabel, XSSFCellStyle totalMoney) { }
}
