package com.tpverp.backend.excel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PageMargin;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

/** Formatting for the current-stock view; row data remains in the SXSSF window. */
final class StockExportLayout {
    static final int HEADER_ROW = 8;
    static final Set<String> TOTAL_QUANTITIES = Set.of("localStock", "totalStock", "stock");
    private static final Set<String> TOP_SALES_TOTALS = Set.of("soldUnits", "currentStock", "amount");
    private static final int PORTRAIT_WIDTH = 555;
    private static final int LANDSCAPE_WIDTH = 802;
    private static final XSSFColor NAVY = rgb(10, 47, 86);
    private static final XSSFColor PALE = rgb(234, 241, 247);
    private static final XSSFColor WHITE = rgb(255, 255, 255);
    private static final XSSFColor STRIPE = rgb(246, 249, 252);

    private StockExportLayout() { }

    static boolean supports(StockExcelExportService.ExportRequest request) {
        return request.view() == null || Set.of("CURRENT_STOCK", "TOP_SALES", "OFFERS",
                "MEMBER_PRICE", "NO_DISCOUNT", "PROMOTIONS").contains(request.view());
    }

    static List<List<StockExcelExportService.ExportColumn>> groups(
            StockExcelExportService.ExportRequest request) {
        List<StockExcelExportService.ExportColumn> selected = request.columns();
        var groups = new ArrayList<List<StockExcelExportService.ExportColumn>>();
        var group = new ArrayList<StockExcelExportService.ExportColumn>();
        int used = 0;
        for (var column : selected) {
            int next = width(column.key());
            if (used + next > LANDSCAPE_WIDTH && !group.isEmpty()) {
                groups.add(List.copyOf(group));
                group = new ArrayList<>();
                used = 0;
            }
            group.add(column);
            used += next;
        }
        if (!group.isEmpty()) groups.add(List.copyOf(group));
        return List.copyOf(groups);
    }

    static int width(String key) {
        return switch (key) {
            case "name", "supplier", "promotion", "promotionValidity" -> 145;
            case "family", "subfamily", "promotionType", "promotionStatus" -> 105;
            case "code", "barcode" -> 88;
            case "localStock", "totalStock", "stock", "soldUnits", "currentStock" -> 78;
            default -> 82;
        };
    }

    static boolean portrait(List<StockExcelExportService.ExportColumn> columns) {
        return columns.stream().mapToInt(column -> width(column.key())).sum() <= PORTRAIT_WIDTH;
    }

    static Set<String> totalKeys(StockExcelExportService.ExportRequest request) {
        return "TOP_SALES".equals(request.view()) ? TOP_SALES_TOTALS : TOTAL_QUANTITIES;
    }

    static String title(StockExcelExportService.ExportRequest request) {
        return switch (request.view() == null ? "CURRENT_STOCK" : request.view()) {
            case "TOP_SALES" -> translate(request.language(), "INFORME DE TOP VENTAS", "TOP SALES REPORT", "热销商品报表");
            case "OFFERS" -> translate(request.language(), "INFORME DE OFERTAS", "OFFERS REPORT", "优惠商品报表");
            case "MEMBER_PRICE" -> translate(request.language(), "INFORME DE PRECIO DE SOCIO", "MEMBER PRICE REPORT", "会员价报表");
            case "NO_DISCOUNT" -> translate(request.language(), "INFORME DE PRODUCTOS SIN DESCUENTO", "NON-DISCOUNTABLE PRODUCTS", "不可折扣商品报表");
            case "PROMOTIONS" -> translate(request.language(), "INFORME DE PROMOCIONES", "PROMOTIONS REPORT", "促销商品报表");
            default -> translate(request.language(), "INFORME DE STOCK ACTUAL", "CURRENT STOCK REPORT", "当前库存报表");
        };
    }

    static String sheetName(StockExcelExportService.ExportRequest request) {
        return switch (request.view() == null ? "CURRENT_STOCK" : request.view()) {
            case "TOP_SALES" -> "Top ventas";
            case "OFFERS" -> "Ofertas";
            case "MEMBER_PRICE" -> "Precio socio";
            case "NO_DISCOUNT" -> "Sin descuento";
            case "PROMOTIONS" -> translate(request.language(), "Promociones", "Promotions", "促销商品");
            default -> "Stock";
        };
    }

    record Metadata(String company, String taxId, String store, String address, String warehouse, String timezone,
            Instant issuedAt) {
        static Metadata empty(Instant instant) {
            return new Metadata("", "", "", "", null, ZoneId.systemDefault().getId(), instant);
        }

        LocalDate localDate() { return issuedAt.atZone(ZoneId.of(timezone)).toLocalDate(); }

        String issued() {
            return issuedAt.atZone(ZoneId.of(timezone))
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
        }
    }

    record Warehouse(UUID id, String name) { }

    record Styles(CellStyle title, CellStyle header, CellStyle metaLabel, CellStyle metaValue,
            CellStyle body, CellStyle stripe, CellStyle quantity, CellStyle quantityStripe,
            CellStyle quantityWhole, CellStyle quantityWholeStripe,
            CellStyle money, CellStyle moneyStripe, CellStyle date, CellStyle dateStripe,
            CellStyle total, CellStyle totalQuantity, CellStyle totalQuantityWhole, CellStyle totalMoney) {
        Styles(SXSSFWorkbook workbook) {
            this(style(workbook, NAVY, font(workbook, WHITE, 18, true), HorizontalAlignment.LEFT, null),
                    style(workbook, NAVY, font(workbook, WHITE, 10, true), HorizontalAlignment.LEFT, null),
                    style(workbook, PALE, font(workbook, NAVY, 10, true), HorizontalAlignment.LEFT, null),
                    style(workbook, PALE, font(workbook, NAVY, 10, false), HorizontalAlignment.LEFT, null),
                    style(workbook, WHITE, font(workbook, NAVY, 10, false), HorizontalAlignment.LEFT, null),
                    style(workbook, STRIPE, font(workbook, NAVY, 10, false), HorizontalAlignment.LEFT, null),
                    style(workbook, WHITE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0.###"),
                    style(workbook, STRIPE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0.###"),
                    style(workbook, WHITE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0"),
                    style(workbook, STRIPE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0"),
                    style(workbook, WHITE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0.00# \"€\""),
                    style(workbook, STRIPE, font(workbook, NAVY, 10, false), HorizontalAlignment.RIGHT, "#,##0.00# \"€\""),
                    style(workbook, WHITE, font(workbook, NAVY, 10, false), HorizontalAlignment.LEFT, "dd/mm/yyyy"),
                    style(workbook, STRIPE, font(workbook, NAVY, 10, false), HorizontalAlignment.LEFT, "dd/mm/yyyy"),
                    totalStyle(workbook, HorizontalAlignment.LEFT, null),
                    totalStyle(workbook, HorizontalAlignment.RIGHT, "#,##0.###"),
                    totalStyle(workbook, HorizontalAlignment.RIGHT, "#,##0"),
                    totalStyle(workbook, HorizontalAlignment.RIGHT, "#,##0.00# \"€\""));
            workbook.getFontAt(0).setFontName("Calibri");
        }
    }

    static void heading(Sheet sheet, StockExcelExportService.ExportRequest request,
            List<StockExcelExportService.ExportColumn> columns, Metadata metadata, Styles styles,
            String promotionName) {
        int count = columns.size();
        columnWidths(sheet, columns);
        Row title = sheet.createRow(0);
        String heading = title(request);
        title.setHeightInPoints(count == 1
                ? Math.min(409, Math.max(36,
                        lineCount(heading, sheet.getColumnWidth(0) / 512) * 25 + 8)) : 36);
        for (int c = 0; c < count; c++) title.createCell(c).setCellStyle(styles.title());
        title.getCell(0).setCellValue(heading);
        if (count > 1) sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, count - 1));
        String[][] fields = {
                {translate(request.language(), "Empresa", "Company", "公司"), metadata.company()},
                {translate(request.language(), "NIF", "Tax ID", "税号"), metadata.taxId()},
                {translate(request.language(), "Tienda", "Store", "门店"), metadata.store()},
                {translate(request.language(), "Dirección", "Address", "地址"), metadata.address()},
                {translate(request.language(), "Almacén", "Warehouse", "仓库"),
                        metadata.warehouse() == null
                                ? translate(request.language(), "Todos", "All", "全部") : metadata.warehouse()},
                {translate(request.language(), "Emitido", "Issued", "生成时间"), metadata.issued()}
        };
        for (int i = 0; i < fields.length; i++) {
            Row row = sheet.createRow(i + 1);
                row.setHeightInPoints(27);
            if (count == 1) {
                var cell = row.createCell(0);
                cell.setCellValue(fields[i][0] + ": " + fields[i][1]);
                cell.setCellStyle(styles.metaLabel());
                row.setHeightInPoints(rowHeight(fields[i][0] + ": " + fields[i][1],
                        sheet.getColumnWidth(0) / 256 - 2));
            } else {
                row.createCell(0).setCellValue(fields[i][0]);
                row.getCell(0).setCellStyle(styles.metaLabel());
                for (int c = 1; c < count; c++) row.createCell(c).setCellStyle(styles.metaValue());
                row.getCell(1).setCellValue(fields[i][1]);
                if (count > 2) sheet.addMergedRegion(new CellRangeAddress(i + 1, i + 1, 1, count - 1));
                int width = 0;
                for (int c = 1; c < count; c++) width += sheet.getColumnWidth(c) / 256;
                row.setHeightInPoints(rowHeight(fields[i][1], width - 3));
            }
        }
        Row period = sheet.createRow(7);
        if ("TOP_SALES".equals(request.view()) || "PROMOTIONS".equals(request.view())) {
            String value = "TOP_SALES".equals(request.view())
                    ? translate(request.language(), "Período", "Period", "期间") + ": "
                            + request.dateFrom().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) + " - "
                            + request.dateTo().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                    : "SELECTED".equals(request.promotionScope())
                            ? translate(request.language(), "Promoción", "Promotion", "促销") + ": "
                                    + (promotionName == null ? "" : promotionName)
                            : translate(request.language(), "Todas las promociones activas", "All active promotions", "所有有效促销");
            int contextWidth = 0;
            for (int c = 0; c < count; c++) contextWidth += sheet.getColumnWidth(c) / 256;
            period.setHeightInPoints(rowHeight(value, contextWidth - 2));
            period.createCell(0).setCellValue(value);
            period.getCell(0).setCellStyle(styles.metaLabel());
            if (count > 1) sheet.addMergedRegion(new CellRangeAddress(7, 7, 0, count - 1));
        } else period.setHeightInPoints(10);
        Row header = sheet.createRow(HEADER_ROW);
        header.setHeightInPoints(30);
        for (int c = 0; c < count; c++) {
            var cell = header.createCell(c);
            cell.setCellValue(columns.get(c).label());
            cell.setCellStyle(styles.header());
        }
        sheet.createFreezePane(0, HEADER_ROW + 1);
    }

    static void body(Row row, List<StockExcelExportService.ExportColumn> columns, Styles styles,
            int rowNumber) {
        boolean stripe = rowNumber % 2 == 0;
        int lines = 1;
        for (int c = 0; c < columns.size(); c++) {
            var cell = row.getCell(c);
            String key = columns.get(c).key();
            CellStyle style = switch (key) {
                case "localStock", "totalStock", "stock", "stockMin", "stockMax", "packageQuantity",
                        "soldUnits", "currentStock", "ranking" ->
                        cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                                && cell.getNumericCellValue() == Math.rint(cell.getNumericCellValue())
                                ? (stripe ? styles.quantityWholeStripe() : styles.quantityWhole())
                                : (stripe ? styles.quantityStripe() : styles.quantity());
                case "purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice", "amount" ->
                        stripe ? styles.moneyStripe() : styles.money();
                case "offerFrom", "offerUntil" -> stripe ? styles.dateStripe() : styles.date();
                default -> stripe ? styles.stripe() : styles.body();
            };
            cell.setCellStyle(style);
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                int width = switch (key) {
                    case "name", "supplier", "promotion" -> 33;
                    case "family", "subfamily" -> 21;
                    default -> 14;
                };
                String text = cell.getStringCellValue();
                lines = Math.max(lines, lineCount(text, width));
            }
        }
        row.setHeightInPoints(Math.min(409, Math.max(28, lines * 15 + 8)));
    }

    static void totals(Sheet sheet, StockExcelExportService.ExportRequest request,
            List<StockExcelExportService.ExportColumn> columns,
            Styles styles, Map<String, BigDecimal> sums, long productCount) {
        int index = HEADER_ROW + Math.toIntExact(productCount) + 1;
        Row row = sheet.createRow(index);
        row.setHeightInPoints(34);
        int labelColumn = -1;
        for (int c = 0; c < columns.size(); c++) {
            row.createCell(c).setCellStyle(styles.total());
            if (labelColumn < 0 && !totalKeys(request).contains(columns.get(c).key())) labelColumn = c;
        }
        if (labelColumn >= 0) {
            row.getCell(labelColumn).setCellValue("TOTAL");
        }
        for (int c = 0; c < columns.size(); c++) {
            String key = columns.get(c).key();
            if (!totalKeys(request).contains(key)) continue;
            BigDecimal sum = sums.getOrDefault(key, BigDecimal.ZERO);
            row.getCell(c).setCellValue(sum.doubleValue());
            if ("amount".equals(key)) row.getCell(c).setCellStyle(styles.totalMoney());
            else row.getCell(c).setCellStyle(sum.stripTrailingZeros().scale() <= 0
                    ? styles.totalQuantityWhole() : styles.totalQuantity());
        }
        Row count = sheet.createRow(index + 1);
        count.setHeightInPoints(28);
        for (int c = 0; c < columns.size(); c++) count.createCell(c).setCellStyle(styles.metaLabel());
        count.getCell(0).setCellValue(("TOP_SALES".equals(request.view())
                ? translate(request.language(), "Registros: ", "Rows: ", "记录数: ")
                : translate(request.language(), "Productos: ", "Products: ", "商品数: ")) + productCount);
        if (columns.size() > 1) sheet.addMergedRegion(new CellRangeAddress(index + 1, index + 1, 0, columns.size() - 1));
    }

    static void printSetup(Sheet sheet, List<StockExcelExportService.ExportColumn> columns,
            int lastRow, String language) {
        int count = columns.size();
        sheet.setAutoFilter(new CellRangeAddress(HEADER_ROW, Math.max(HEADER_ROW, lastRow - 2), 0, count - 1));
        columnWidths(sheet, columns);
        sheet.setDisplayGridlines(false);
        sheet.setFitToPage(true);
        var print = sheet.getPrintSetup();
        print.setFitWidth((short) 1);
        print.setFitHeight((short) 0);
        double widthPoints = 0;
        for (int c = 0; c < count; c++) widthPoints += sheet.getColumnWidthInPixels(c) * 72d / 96d;
        double portraitPoints = 210d / 25.4d * 72d
                - (sheet.getMargin(PageMargin.LEFT) + sheet.getMargin(PageMargin.RIGHT)) * 72d;
        print.setLandscape(!portrait(columns));
        print.setPaperSize(PrintSetup.A4_PAPERSIZE);
        sheet.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));
        sheet.getFooter().setCenter(translate(language, "Página", "Page", "页") + " &P "
                + translate(language, "de", "of", "共") + " &N");
    }

    private static void columnWidths(Sheet sheet, List<StockExcelExportService.ExportColumn> columns) {
        int count = columns.size();
        for (int c = 0; c < count; c++) {
            String key = columns.get(c).key();
            int characters = Math.max(14, Math.round(width(key) * 96f / 72f / 7f));
            sheet.setColumnWidth(c, (count == 1 ? Math.max(characters, 36) : characters) * 256);
        }
    }

    private static float rowHeight(String text, int width) {
        return Math.min(409, Math.max(27, lineCount(text, width) * 15 + 8));
    }

    private static int lineCount(String text, int width) {
        int usable = Math.max(8, width);
        int lines = 0;
        for (String part : text.split("\\R", -1)) {
            lines += Math.max(1, (part.codePointCount(0, part.length()) + usable - 1) / usable);
        }
        return lines;
    }

    static String translate(String language, String es, String en, String zh) {
        return "zh".equals(language) ? zh : "en".equals(language) ? en : es;
    }

    private static XSSFCellStyle totalStyle(SXSSFWorkbook workbook,
            HorizontalAlignment alignment, String format) {
        XSSFCellStyle style = style(workbook, PALE, font(workbook, NAVY, 14, true), alignment, format);
        style.setBorderTop(BorderStyle.MEDIUM);
        style.setTopBorderColor(NAVY);
        return style;
    }

    private static XSSFCellStyle style(SXSSFWorkbook workbook, XSSFColor fill,
            org.apache.poi.xssf.usermodel.XSSFFont font, HorizontalAlignment alignment, String format) {
        var style = (XSSFCellStyle) workbook.createCellStyle();
        style.setFillForegroundColor(fill);
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFont(font);
        style.setAlignment(alignment);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(true);
        if (format != null) style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static org.apache.poi.xssf.usermodel.XSSFFont font(SXSSFWorkbook workbook,
            XSSFColor color, int size, boolean bold) {
        var font = (org.apache.poi.xssf.usermodel.XSSFFont) workbook.createFont();
        font.setFontName("Calibri");
        font.setColor(color);
        font.setFontHeightInPoints((short) size);
        font.setBold(bold);
        return font;
    }

    private static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[] {(byte) r, (byte) g, (byte) b});
    }
}
