package com.tpverp.backend.excel;

import com.tpverp.backend.audit.AuditResult;
import com.tpverp.backend.audit.AuditService;
import com.tpverp.backend.document.SalesActivityDocumentRowView;
import com.tpverp.backend.document.SalesActivityPrintGrouping;
import com.tpverp.backend.document.SalesActivityReportService;
import com.tpverp.backend.document.SalesDailySummaryView;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.springframework.stereotype.Service;

@Service
public class SalesActivityExcelExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final SalesActivityReportService reports;
    private final CurrentOrganization organization;
    private final AuditService audit;

    public SalesActivityExcelExportService(
            SalesActivityReportService reports,
            CurrentOrganization organization,
            AuditService audit) {
        this.reports = reports;
        this.organization = organization;
        this.audit = audit;
    }

    public byte[] daily(LocalDate date) {
        return daily(reports.daily(date));
    }

    public byte[] daily(SalesDailySummaryView summary) {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Resumen diario");
            var styles = dailyStyles(workbook);
            int row = 0;
            row = dailyBand(sheet, row, "VENTAS DIARIAS", styles.title());
            row = dailyMeta(sheet, row, "Empresa", summary.companyName(), styles);
            row = dailyMeta(sheet, row, "Tienda", summary.storeCode(), styles);
            row = dailyMeta(sheet, row, "Fecha", summary.date(), styles);
            row++;
            row = dailyColumns(sheet, row, styles);
            row = dailyBand(sheet, row, "TOTAL TIENDA", styles.section());
            row = dailySummaryBlock(sheet, row, summary.netSalesTotal(),
                    summary.paymentMethods(), summary.counts(), styles);
            for (var user : summary.users()) {
                row++;
                row = dailyBand(sheet, row, user.userName(), styles.section());
                row = dailySummaryBlock(sheet, row, user.netSalesTotal(),
                        user.paymentMethods(), user.counts(), styles);
            }
            if (summary.operations() != null) {
                row++;
                row = dailyCommercialBlock(sheet, row, summary.operations(), styles);
            }
            for (int column = 0; column < 4; column++) sheet.setColumnWidth(column, 14 * 256);
            sheet.setColumnWidth(4, 19 * 256);
            sheet.setColumnWidth(5, 22 * 256);
            sheet.setDisplayGridlines(false);
            sheet.setPrintGridlines(false);
            sheet.createFreezePane(0, 6);
            sheet.setRepeatingRows(new CellRangeAddress(0, 5, -1, -1));
            sheet.setFitToPage(true);
            var print = sheet.getPrintSetup();
            print.setPaperSize(PrintSetup.A4_PAPERSIZE);
            print.setLandscape(false);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, 5, 0, row - 1);
            workbook.write(output);
            audit.record("DAILY_SALES_EXPORTED", AuditResult.EXITO,
                    Map.of("date", summary.date().toString(), "format", "XLSX"));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el Excel de ventas diarias", exception);
        }
    }

    public byte[] documents(LocalDate from, LocalDate to) {
        return documents(from, to, SalesActivityPrintGrouping.DOCUMENT);
    }

    private static int dailySummaryBlock(org.apache.poi.ss.usermodel.Sheet sheet, int row,
            BigDecimal total, java.util.List<SalesDailySummaryView.PaymentTotalView> methods,
            SalesDailySummaryView.ActivityCountsView counts, DailyStyles styles) {
        for (var method : methods) {
            row = dailyValue(sheet, row, paymentLabel(method.method().name()),
                    method.operationCount(), method.amount(), false, styles);
        }
        row = dailyValue(sheet, row, "Total ventas netas", null, total, true, styles);
        var salesTotalRow = sheet.getRow(row - 1);
        salesTotalRow.setHeightInPoints(32);
        for (int column = 0; column < 4; column++) {
            salesTotalRow.getCell(column).setCellStyle(styles.salesTotalLabel());
        }
        salesTotalRow.getCell(5).setCellStyle(styles.salesTotalMoney());
        row = dailySubsection(sheet, row, "OPERACIONES", styles);
        row = dailyValue(sheet, row, "Ventas", counts.sales(), null, false, styles);
        row = dailyValue(sheet, row, "Devoluciones", counts.returns(), null, false, styles);
        row = dailyValue(sheet, row, "Anulados", counts.cancelled(), null, false, styles);
        return dailyValue(sheet, row, "Pendientes", counts.pending(), null, false, styles);
    }

    private static int dailyCommercialBlock(org.apache.poi.ss.usermodel.Sheet sheet, int row,
            com.tpverp.backend.document.DailyOperationsSupplement report, DailyStyles styles) {
        row = dailyBand(sheet, row, "COBROS, DEVOLUCIONES Y CAJA", styles.section());
        row = dailyValue(sheet, row, "Cobros actuales", null, report.collectedCurrent(), false, styles);
        row = dailyValue(sheet, row, "Nuevo pendiente", null, report.newPending(), false, styles);
        row = dailySubsection(sheet, row, "COBROS DE DEUDA ANTERIOR", styles);
        row = dailyBreakdown(sheet, row, report.pendingCollectionsByPaymentMethod(), styles);
        row = dailyValue(sheet, row, "Total cobros de deuda anterior", null,
                report.priorDebtCollected(), true, styles);
        row = dailySubsection(sheet, row, "DEVOLUCIONES", styles);
        row = dailyBreakdown(sheet, row, report.refundsByPaymentMethod(), styles);
        row = dailyValue(sheet, row, "Total devoluciones", null,
                report.refundsByPaymentMethod().total(), true, styles);
        row = dailySubsection(sheet, row, "CAJA", styles);
        row = dailyValue(sheet, row, "Entrada real de caja", null, report.cashInflow(), true, styles);
        if (report.openingCashFund() != null) {
            row = dailyValue(sheet, row, "Fondo inicial", null, report.openingCashFund(), false, styles);
        }
        row = dailyValue(sheet, row, "Entradas", null, report.cashEntries(), false, styles);
        row = dailyValue(sheet, row, "Retiradas", null, report.cashWithdrawals(), false, styles);
        if (report.expectedCash() != null) {
            row = dailyValue(sheet, row, "Efectivo esperado", null, report.expectedCash(), true, styles);
        }
        return row;
    }

    private static int dailyBreakdown(org.apache.poi.ss.usermodel.Sheet sheet, int row,
            com.tpverp.backend.document.DailyPaymentBreakdownView breakdown, DailyStyles styles) {
        row = dailyOptionalValue(sheet, row, "Efectivo", breakdown.cash(), styles);
        row = dailyOptionalValue(sheet, row, "Tarjeta", breakdown.card(), styles);
        row = dailyOptionalValue(sheet, row, "Transferencia", breakdown.transfer(), styles);
        row = dailyOptionalValue(sheet, row, "Vale", breakdown.voucher(), styles);
        row = dailyOptionalValue(sheet, row, "Pendiente", breakdown.pending(), styles);
        return dailyOptionalValue(sheet, row, "Otros", breakdown.other(), styles);
    }

    private static int dailyOptionalValue(org.apache.poi.ss.usermodel.Sheet sheet, int row,
            String label, BigDecimal amount, DailyStyles styles) {
        return amount == null || amount.signum() == 0 ? row
                : dailyValue(sheet, row, label, null, amount, false, styles);
    }

    private static int dailyBand(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex,
            String label, CellStyle style) {
        var row = sheet.createRow(rowIndex);
        row.setHeightInPoints("VENTAS DIARIAS".equals(label) ? 36
                : label.length() > 54 ? 44 : 32);
        for (int column = 0; column < 6; column++) {
            row.createCell(column).setCellStyle(style);
        }
        row.getCell(0).setCellValue(label);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 5));
        return rowIndex + 1;
    }

    private static int dailyMeta(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex,
            String label, Object value, DailyStyles styles) {
        var row = sheet.createRow(rowIndex);
        row.setHeightInPoints(value instanceof String text && text.length() > 70 ? 38 : 24);
        for (int column = 0; column < 6; column++) {
            row.createCell(column).setCellStyle(column == 0 ? styles.metaLabel()
                    : value instanceof LocalDate ? styles.metaDate() : styles.metaValue());
        }
        row.getCell(0).setCellValue(label);
        if (value instanceof LocalDate date) row.getCell(1).setCellValue(date);
        else row.getCell(1).setCellValue(value == null ? "" : String.valueOf(value));
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 1, 5));
        return rowIndex + 1;
    }

    private static int dailyColumns(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex,
            DailyStyles styles) {
        var row = sheet.createRow(rowIndex);
        row.setHeightInPoints(25);
        for (int column = 0; column < 6; column++) row.createCell(column).setCellStyle(styles.columns());
        row.getCell(0).setCellValue("Concepto");
        row.getCell(4).setCellValue("Operaciones");
        row.getCell(5).setCellValue("Importe");
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 3));
        return rowIndex + 1;
    }

    private static int dailySubsection(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex,
            String label, DailyStyles styles) {
        return dailyBand(sheet, rowIndex, label, styles.subsection());
    }

    private static int dailyValue(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex,
            String label, Long count, BigDecimal amount, boolean total, DailyStyles styles) {
        var row = sheet.createRow(rowIndex);
        row.setHeightInPoints(label.length() > 48 ? 32 : 23);
        boolean alternate = rowIndex % 2 == 0;
        CellStyle labelStyle = total ? styles.totalLabel()
                : alternate ? styles.altLabel() : styles.valueLabel();
        CellStyle countStyle = total ? styles.totalCount()
                : alternate ? styles.altCount() : styles.valueCount();
        CellStyle moneyStyle = total ? styles.totalMoney()
                : alternate ? styles.altMoney() : styles.valueMoney();
        for (int column = 0; column < 4; column++) row.createCell(column).setCellStyle(labelStyle);
        row.getCell(0).setCellValue(label);
        sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, 0, 3));
        row.createCell(4).setCellStyle(countStyle);
        if (count != null) row.getCell(4).setCellValue(count.doubleValue());
        row.createCell(5).setCellStyle(moneyStyle);
        if (amount != null) row.getCell(5).setCellValue(amount.doubleValue());
        return rowIndex + 1;
    }

    private static DailyStyles dailyStyles(XSSFWorkbook workbook) {
        var navy = new XSSFColor(new byte[] {10, 47, 86});
        var teal = new XSSFColor(new byte[] {17, 124, (byte) 131});
        var pale = new XSSFColor(new byte[] {(byte) 234, (byte) 241, (byte) 247});
        var white = new XSSFColor(new byte[] {(byte) 255, (byte) 255, (byte) 255});
        var text = new XSSFColor(new byte[] {35, 52, 68});
        short moneyFormat = workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]");
        short countFormat = workbook.createDataFormat().getFormat("0");
        var title = dailyStyle(workbook, navy, white, true, (short) 18, HorizontalAlignment.LEFT, (short) -1);
        var section = dailyStyle(workbook, navy, white, true, (short) 12, HorizontalAlignment.LEFT, (short) -1);
        var subsection = dailyStyle(workbook, teal, white, true, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var columns = dailyStyle(workbook, pale, navy, true, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var metaLabel = dailyStyle(workbook, pale, navy, true, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var metaValue = dailyStyle(workbook, pale, text, false, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var metaDate = dailyStyle(workbook, pale, text, false, (short) 10,
                HorizontalAlignment.LEFT, workbook.createDataFormat().getFormat("dd/MM/yyyy"));
        var valueLabel = dailyStyle(workbook, white, text, false, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var altLabel = dailyStyle(workbook, pale, text, false, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var valueCount = dailyStyle(workbook, white, text, false, (short) 10, HorizontalAlignment.RIGHT, countFormat);
        var altCount = dailyStyle(workbook, pale, text, false, (short) 10, HorizontalAlignment.RIGHT, countFormat);
        var valueMoney = dailyStyle(workbook, white, text, false, (short) 10, HorizontalAlignment.RIGHT, moneyFormat);
        var altMoney = dailyStyle(workbook, pale, text, false, (short) 10, HorizontalAlignment.RIGHT, moneyFormat);
        var totalLabel = dailyStyle(workbook, pale, navy, true, (short) 10, HorizontalAlignment.LEFT, (short) -1);
        var totalCount = dailyStyle(workbook, pale, navy, true, (short) 10, HorizontalAlignment.RIGHT, countFormat);
        var totalMoney = dailyStyle(workbook, pale, navy, true, (short) 10, HorizontalAlignment.RIGHT, moneyFormat);
        var salesTotalLabel = dailyStyle(workbook, pale, navy, true, (short) 14, HorizontalAlignment.LEFT, (short) -1);
        var salesTotalMoney = dailyStyle(workbook, pale, navy, true, (short) 14, HorizontalAlignment.RIGHT, moneyFormat);
        for (var style : new XSSFCellStyle[] {totalLabel, totalCount, totalMoney, salesTotalLabel, salesTotalMoney}) {
            style.setBorderTop(BorderStyle.THIN);
            style.setTopBorderColor(navy);
        }
        return new DailyStyles(title, section, subsection, columns, metaLabel, metaValue, metaDate,
                valueLabel, altLabel, valueCount, altCount, valueMoney, altMoney,
                totalLabel, totalCount, totalMoney, salesTotalLabel, salesTotalMoney);
    }

    private static XSSFCellStyle dailyStyle(XSSFWorkbook workbook, XSSFColor background,
            XSSFColor foreground, boolean bold, short fontSize, HorizontalAlignment alignment,
            short format) {
        var style = workbook.createCellStyle();
        style.setFillForegroundColor(background);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(alignment);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        style.setWrapText(true);
        if (format >= 0) style.setDataFormat(format);
        var font = workbook.createFont();
        font.setColor(foreground);
        font.setBold(bold);
        font.setFontHeightInPoints(fontSize);
        style.setFont(font);
        return style;
    }

    private record DailyStyles(CellStyle title, CellStyle section, CellStyle subsection,
            CellStyle columns, CellStyle metaLabel, CellStyle metaValue, CellStyle metaDate,
            CellStyle valueLabel, CellStyle altLabel, CellStyle valueCount,
            CellStyle altCount, CellStyle valueMoney, CellStyle altMoney,
            CellStyle totalLabel, CellStyle totalCount, CellStyle totalMoney,
            CellStyle salesTotalLabel, CellStyle salesTotalMoney) {
    }

    public byte[] documents(
            LocalDate from, LocalDate to, SalesActivityPrintGrouping grouping) {
        var values = grouping == SalesActivityPrintGrouping.DAY
                ? null : reports.allDocuments(from, to);
        var dailyValues = grouping == SalesActivityPrintGrouping.DAY
                ? reports.allDailyDocuments(from, to) : null;
        var store = organization.currentStore();
        var zone = ZoneId.of(store.getTimezone());
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Documentos de ventas");
            var styles = styles(workbook);
            int headerRow = 7;
            var header = sheet.createRow(headerRow);
            String[] columns = grouping == SalesActivityPrintGrouping.DAY
                    ? new String[] {"Fecha", "Nº tickets", "Nº facturas", "Ventas totales"}
                    : new String[] {"Fecha", "Hora", "Número de ticket", "Número de factura",
                    "Usuario", "Método de pago", "Estado", "Total"};
            for (int column = 0; column < columns.length; column++) {
                var cell = header.createCell(column);
                cell.setCellValue(columns[column]);
                cell.setCellStyle(styles.header());
            }
            int rowIndex = headerRow + 1;
            if (grouping == SalesActivityPrintGrouping.DAY) {
                for (var value : dailyValues) {
                    var row = sheet.createRow(rowIndex++);
                    row.createCell(0).setCellValue(DATE.format(value.date()));
                    row.createCell(1).setCellValue(value.ticketCount());
                    row.createCell(2).setCellValue(value.invoiceCount());
                    var total = row.createCell(3);
                    total.setCellValue(value.total().doubleValue());
                    total.setCellStyle(styles.money());
                }
            } else for (var value : values) {
                var row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(DATE.format(value.date()));
                row.createCell(1).setCellValue(value.occurredAt() == null ? ""
                        : TIME.format(value.occurredAt().atZone(zone)));
                row.createCell(2).setCellValue(value.ticketNumber());
                row.createCell(3).setCellValue(value.invoiceNumber());
                row.createCell(4).setCellValue(value.userName());
                row.createCell(5).setCellValue(String.join(", ", value.paymentMethods()));
                row.createCell(6).setCellValue(displayStatus(value));
                var total = row.createCell(7);
                total.setCellValue(value.total().doubleValue());
                total.setCellStyle(styles.money());
            }
            var totals = sheet.createRow(rowIndex);
            totals.setHeightInPoints(32);
            totals.createCell(0).setCellValue("TOTALES");
            int ticketColumn = grouping == SalesActivityPrintGrouping.DAY ? 1 : 2;
            int invoiceColumn = grouping == SalesActivityPrintGrouping.DAY ? 2 : 3;
            int totalColumn = grouping == SalesActivityPrintGrouping.DAY ? 3 : 7;
            totals.createCell(ticketColumn).setCellValue(grouping == SalesActivityPrintGrouping.DAY
                    ? dailyValues.stream().mapToLong(value -> value.ticketCount()).sum()
                    : values.stream().filter(value -> !value.ticketNumber().isBlank()).count());
            totals.createCell(invoiceColumn).setCellValue(grouping == SalesActivityPrintGrouping.DAY
                    ? dailyValues.stream().mapToLong(value -> value.invoiceCount()).sum()
                    : values.stream().filter(value -> !value.invoiceNumber().isBlank()).count());
            var total = totals.createCell(totalColumn);
            total.setCellValue((grouping == SalesActivityPrintGrouping.DAY
                    ? dailyValues.stream().map(com.tpverp.backend.document.SalesActivityDailyRowView::total)
                    : values.stream().map(SalesActivityDocumentRowView::total))
                    .reduce(BigDecimal.ZERO, BigDecimal::add).doubleValue());
            for (int column = 0; column < columns.length; column++) {
                totals.getCell(column, org.apache.poi.ss.usermodel.Row.MissingCellPolicy.CREATE_NULL_AS_BLANK)
                        .setCellStyle(column == totalColumn ? styles.totalMoney()
                                : column == 0 ? styles.totalLabel() : styles.totalCount());
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 60 * 256));
            }
            documentsHeading(sheet, store, from, to, columns.length, styles);
            sheet.createFreezePane(0, headerRow + 1);
            sheet.setRepeatingRows(new CellRangeAddress(0, headerRow, -1, -1));
            sheet.setDisplayGridlines(false);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            workbook.setPrintArea(workbook.getSheetIndex(sheet), 0, columns.length - 1, 0, rowIndex);
            workbook.write(output);
            audit.record("SALES_DOCUMENTS_EXPORTED", AuditResult.EXITO,
                    Map.of("dateFrom", from.toString(), "dateTo", to.toString(),
                    "rows", grouping == SalesActivityPrintGrouping.DAY
                            ? dailyValues.size() : values.size(),
                    "grouping", grouping.name(), "format", "XLSX"));
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo generar el Excel de documentos de ventas", exception);
        }
    }

    private void documentsHeading(org.apache.poi.ss.usermodel.Sheet sheet,
            com.tpverp.backend.organization.Store store, LocalDate from, LocalDate to,
            int columnCount, Styles styles) {
        var title = sheet.createRow(0);
        title.setHeightInPoints(34);
        for (int column = 0; column < columnCount; column++) {
            title.createCell(column).setCellStyle(styles.title());
        }
        title.getCell(0).setCellValue("DOCUMENTOS DE VENTAS");
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, columnCount - 1));
        var address = store.getDireccion();
        String addressText = java.util.stream.Stream.of("linea1", "linea2", "codigoPostal",
                        "ciudad", "provincia", "pais")
                .map(address::get).filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
        String period = DATE.format(from) + (from.equals(to) ? "" : " - " + DATE.format(to));
        var company = organization.currentCompany();
        String[][] metadata = {
                {"Empresa", company.getRazonSocial()},
                {"NIF", company.getTaxId()},
                {"Tienda", store.getCodigoTienda() + " - " + store.getNombreEfectivo()},
                {"Dirección", addressText},
                {"Periodo", period}
        };
        double valueCharacters = java.util.stream.IntStream.range(1, columnCount)
                .mapToDouble(column -> sheet.getColumnWidth(column) / 256.0).sum();
        for (int index = 0; index < metadata.length; index++) {
            var row = sheet.createRow(index + 1);
            row.createCell(0).setCellValue(metadata[index][0]);
            row.getCell(0).setCellStyle(styles.metadataLabel());
            for (int column = 1; column < columnCount; column++) {
                row.createCell(column).setCellStyle(styles.metadataValue());
            }
            row.getCell(1).setCellValue(metadata[index][1]);
            sheet.addMergedRegion(new CellRangeAddress(index + 1, index + 1, 1, columnCount - 1));
            int lines = (int) Math.ceil(metadata[index][1].length() / Math.max(1, valueCharacters - 4));
            row.setHeightInPoints(Math.max(24, lines * 15 + 8));
        }
        sheet.createRow(6).setHeightInPoints(10);
    }

    private static int commercialBlock(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int rowIndex,
            com.tpverp.backend.document.DailyOperationsSupplement report,
            Styles styles) {
        rowIndex = title(sheet, rowIndex, "COBROS, DEVOLUCIONES Y CAJA", styles);
        rowIndex = pair(sheet, rowIndex, "Cobros actuales", report.collectedCurrent(), styles);
        rowIndex = pair(sheet, rowIndex, "Nuevo pendiente", report.newPending(), styles);
        rowIndex = breakdownBlock(sheet, rowIndex, "Cobros de deuda anterior",
                report.pendingCollectionsByPaymentMethod(), styles);
        rowIndex = pair(sheet, rowIndex, "Cobros de deuda anterior", report.priorDebtCollected(), styles);
        rowIndex = breakdownBlock(sheet, rowIndex, "Devoluciones", report.refundsByPaymentMethod(), styles);
        rowIndex = pair(sheet, rowIndex, "Devoluciones", report.refundsByPaymentMethod().total(), styles);
        rowIndex = pair(sheet, rowIndex, "Entrada real de caja", report.cashInflow(), styles);
        if (report.openingCashFund() != null) {
            rowIndex = pair(sheet, rowIndex, "Fondo inicial", report.openingCashFund(), styles);
        }
        rowIndex = pair(sheet, rowIndex, "Entradas", report.cashEntries(), styles);
        rowIndex = pair(sheet, rowIndex, "Retiradas", report.cashWithdrawals(), styles);
        if (report.expectedCash() != null) {
            rowIndex = pair(sheet, rowIndex, "Efectivo esperado", report.expectedCash(), styles);
        }
        return rowIndex;
    }

    private static int breakdownBlock(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int rowIndex,
            String title,
            com.tpverp.backend.document.DailyPaymentBreakdownView breakdown,
            Styles styles) {
        rowIndex = title(sheet, rowIndex, title, styles);
        rowIndex = optionalPair(sheet, rowIndex, "Efectivo", breakdown.cash(), styles);
        rowIndex = optionalPair(sheet, rowIndex, "Tarjeta", breakdown.card(), styles);
        rowIndex = optionalPair(sheet, rowIndex, "Transferencia", breakdown.transfer(), styles);
        rowIndex = optionalPair(sheet, rowIndex, "Vale", breakdown.voucher(), styles);
        rowIndex = optionalPair(sheet, rowIndex, "Pendiente", breakdown.pending(), styles);
        return optionalPair(sheet, rowIndex, "Otros", breakdown.other(), styles);
    }

    private static int optionalPair(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int rowIndex,
            String label,
            BigDecimal value,
            Styles styles) {
        return value == null || value.signum() == 0
                ? rowIndex : pair(sheet, rowIndex, label, value, styles);
    }

    private static int summaryBlock(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int rowIndex,
            String title,
            BigDecimal total,
            java.util.List<SalesDailySummaryView.PaymentTotalView> methods,
            SalesDailySummaryView.ActivityCountsView counts,
            Styles styles) {
        rowIndex = title(sheet, rowIndex, title, styles);
        for (var method : methods) {
            rowIndex = pair(sheet, rowIndex, paymentLabel(method.method().name())
                    + ": (" + method.operationCount() + ")", method.amount(), styles);
        }
        rowIndex = pair(sheet, rowIndex, "Total", total, styles);
        rowIndex = pair(sheet, rowIndex, "Ventas", counts.sales(), styles);
        rowIndex = pair(sheet, rowIndex, "Devoluciones", counts.returns(), styles);
        rowIndex = pair(sheet, rowIndex, "Anulados", counts.cancelled(), styles);
        return pair(sheet, rowIndex, "Pendientes", counts.pending(), styles);
    }

    private static int title(
            org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex, String value, Styles styles) {
        var row = sheet.createRow(rowIndex++);
        var cell = row.createCell(0);
        cell.setCellValue(value);
        cell.setCellStyle(styles.header());
        return rowIndex;
    }

    private static int pair(
            org.apache.poi.ss.usermodel.Sheet sheet,
            int rowIndex,
            String label,
            Object value,
            Styles styles) {
        var row = sheet.createRow(rowIndex++);
        row.createCell(0).setCellValue(label);
        row.getCell(0).setCellStyle(styles.label());
        var cell = row.createCell(1);
        if (value instanceof BigDecimal amount) {
            cell.setCellValue(amount.doubleValue());
            cell.setCellStyle(styles.money());
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else {
            cell.setCellValue(String.valueOf(value));
        }
        return rowIndex;
    }

    private static String displayStatus(SalesActivityDocumentRowView value) {
        return switch (value.kind()) {
            case SALE -> value.status().name();
            case RETURN -> "DEVOLUCION";
            case CANCELLED -> "ANULADO";
        };
    }

    private static String paymentLabel(String value) {
        return switch (value) {
            case "PENDIENTE" -> "Pendiente de cobro";
            case "OTROS" -> "Otros";
            default -> value.substring(0, 1) + value.substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static Styles styles(XSSFWorkbook workbook) {
        workbook.getFontAt(0).setFontName("Calibri");
        var header = workbook.createCellStyle();
        header.setFillForegroundColor(new XSSFColor(new byte[] {0, 0, (byte) 128}));
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var headerFont = workbook.createFont();
        headerFont.setFontName("Calibri");
        headerFont.setColor(new XSSFColor(new byte[] {(byte) 255, (byte) 255, (byte) 255}));
        headerFont.setBold(true);
        header.setFont(headerFont);
        header.setAlignment(HorizontalAlignment.LEFT);
        var label = workbook.createCellStyle();
        var labelFont = workbook.createFont();
        labelFont.setFontName("Calibri");
        labelFont.setBold(true);
        label.setFont(labelFont);
        var money = workbook.createCellStyle();
        money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
        var totalMoney = workbook.createCellStyle();
        totalMoney.cloneStyleFrom(money);
        totalMoney.setTopBorderColor(IndexedColors.DARK_BLUE.getIndex());
        totalMoney.setBorderTop(BorderStyle.MEDIUM);
        var totalFont = workbook.createFont();
        totalFont.setFontName("Calibri");
        totalFont.setBold(true);
        totalFont.setFontHeightInPoints((short) 14);
        totalFont.setColor(new XSSFColor(new byte[] {10, 47, 86}));
        totalMoney.setFont(totalFont);
        totalMoney.setFillForegroundColor(new XSSFColor(new byte[] {(byte) 234, (byte) 241, (byte) 247}));
        totalMoney.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        totalMoney.setAlignment(HorizontalAlignment.RIGHT);
        totalMoney.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        var totalLabel = workbook.createCellStyle();
        totalLabel.cloneStyleFrom(totalMoney);
        totalLabel.setDataFormat((short) 0);
        totalLabel.setAlignment(HorizontalAlignment.LEFT);
        var totalCount = workbook.createCellStyle();
        totalCount.cloneStyleFrom(totalMoney);
        totalCount.setDataFormat(workbook.createDataFormat().getFormat("0"));
        var navy = new XSSFColor(new byte[] {10, 47, 86});
        var pale = new XSSFColor(new byte[] {(byte) 234, (byte) 241, (byte) 247});
        var white = new XSSFColor(new byte[] {(byte) 255, (byte) 255, (byte) 255});
        var title = dailyStyle(workbook, navy, white, true, (short) 18,
                HorizontalAlignment.LEFT, (short) -1);
        var metadataLabel = dailyStyle(workbook, pale, navy, true, (short) 10,
                HorizontalAlignment.LEFT, (short) -1);
        var metadataValue = dailyStyle(workbook, pale, navy, false, (short) 10,
                HorizontalAlignment.LEFT, (short) -1);
        return new Styles(header, label, money, totalMoney, totalLabel, totalCount,
                title, metadataLabel, metadataValue);
    }

    private record Styles(
            CellStyle header,
            CellStyle label,
            CellStyle money,
            CellStyle totalMoney,
            CellStyle totalLabel,
            CellStyle totalCount,
            CellStyle title,
            CellStyle metadataLabel,
            CellStyle metadataValue) {
    }
}
