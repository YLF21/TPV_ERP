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
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
            var styles = styles(workbook);
            int row = 0;
            row = title(sheet, row, "RESUMEN DE VENTAS DIARIAS", styles);
            row = pair(sheet, row, "Empresa", summary.companyName(), styles);
            row = pair(sheet, row, "ID tienda", summary.storeCode(), styles);
            row = pair(sheet, row, "Fecha consulta", DATE.format(summary.date()), styles);
            row++;
            row = summaryBlock(sheet, row, "TOTAL TIENDA", summary.netSalesTotal(),
                    summary.paymentMethods(), summary.counts(), styles);
            for (var user : summary.users()) {
                row++;
                row = summaryBlock(sheet, row, user.userName(), user.netSalesTotal(),
                        user.paymentMethods(), user.counts(), styles);
            }
            if (summary.operations() != null) {
                row++;
                row = commercialBlock(sheet, row, summary.operations(), styles);
            }
            sheet.setColumnWidth(0, 28 * 256);
            sheet.setColumnWidth(1, 42 * 256);
            sheet.setColumnWidth(2, 18 * 256);
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
            var header = sheet.createRow(0);
            String[] columns = grouping == SalesActivityPrintGrouping.DAY
                    ? new String[] {"Fecha", "Nº tickets", "Nº facturas", "Ventas totales"}
                    : new String[] {"Fecha", "Hora", "Número de ticket", "Número de factura",
                    "Usuario", "Método de pago", "Estado", "Total"};
            for (int column = 0; column < columns.length; column++) {
                var cell = header.createCell(column);
                cell.setCellValue(columns[column]);
                cell.setCellStyle(styles.header());
            }
            int rowIndex = 1;
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
            totals.createCell(0).setCellValue("TOTALES");
            totals.getCell(0).setCellStyle(styles.header());
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
            total.setCellStyle(styles.totalMoney());
            for (int column = 0; column < columns.length; column++) {
                sheet.autoSizeColumn(column);
                sheet.setColumnWidth(column, Math.min(sheet.getColumnWidth(column) + 512, 60 * 256));
            }
            sheet.createFreezePane(0, 1);
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
        var header = workbook.createCellStyle();
        header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        var headerFont = workbook.createFont();
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerFont.setBold(true);
        header.setFont(headerFont);
        header.setAlignment(HorizontalAlignment.LEFT);
        var label = workbook.createCellStyle();
        var labelFont = workbook.createFont();
        labelFont.setBold(true);
        label.setFont(labelFont);
        var money = workbook.createCellStyle();
        money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
        var totalMoney = workbook.createCellStyle();
        totalMoney.cloneStyleFrom(money);
        totalMoney.setTopBorderColor(IndexedColors.DARK_BLUE.getIndex());
        totalMoney.setBorderTop(BorderStyle.MEDIUM);
        var totalFont = workbook.createFont();
        totalFont.setBold(true);
        totalMoney.setFont(totalFont);
        return new Styles(header, label, money, totalMoney);
    }

    private record Styles(
            CellStyle header,
            CellStyle label,
            CellStyle money,
            CellStyle totalMoney) {
    }
}
