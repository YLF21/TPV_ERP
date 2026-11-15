package com.tpverp.backend.excel;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.FontSelector;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.xml.sax.InputSource;

/** Streams one workbook sheet into vertically aligned A4 landscape column continuations. */
final class StockPdfRenderer {
    private static final float PAGE_LEFT = 20;
    private static final float TABLE_WIDTH = PageSize.A4.getHeight() - 2 * PAGE_LEFT;
    private static final float LATER_TABLE_TOP = PageSize.A4.getWidth() - 22;
    private static final float TABLE_BOTTOM = 44;
    private static final float ROW_GAP = 0.5f;
    private static final Set<String> RIGHT_ALIGNED = Set.of(
            "purchasePrice", "salePrice", "memberPrice", "wholesalePrice", "offerPrice",
            "stockMin", "stockMax", "packageQuantity", "tax", "ranking", "amount");

    private StockPdfRenderer() { }

    static void render(Path workbook, Path output,
            StockExcelExportService.ExportRequest request,
            StockExportLayout.Metadata metadata, int productCount) throws IOException {
        var document = new Document(PageSize.A4.rotate(), PAGE_LEFT, PAGE_LEFT, 22, 42);
        try (var stream = Files.newOutputStream(output);
                var pkg = OPCPackage.open(workbook.toFile(), PackageAccess.READ)) {
            var writer = PdfWriter.getInstance(document, stream);
            var fonts = new Fonts();
            writer.setPageEvent(new PageNumbers(fonts, request.language()));
            document.open();
            addBanner(document, fonts, StockExportLayout.title(request));
            var details = new PdfPTable(2);
            details.setWidthPercentage(100);
            details.setWidths(new float[] {2, 1});
            details.setSpacingBefore(8);
            details.setSpacingAfter(10);
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "EMPRESA", "COMPANY", "公司"), metadata.company());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "EMITIDO", "ISSUED", "生成时间"), metadata.issued());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "NIF", "TAX ID", "税号"), metadata.taxId());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "TIENDA", "STORE", "门店"), metadata.store());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "DIRECCIÓN", "ADDRESS", "地址"), metadata.address());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "ALMACÉN", "WAREHOUSE", "仓库"), metadata.warehouse() == null
                            ? StockExportLayout.translate(request.language(), "Todos", "All", "全部")
                            : metadata.warehouse());
            addDetail(details, fonts, StockExportLayout.translate(request.language(),
                    "TOP_SALES".equals(request.view()) ? "REGISTROS" : "PRODUCTOS",
                    "TOP_SALES".equals(request.view()) ? "ROWS" : "PRODUCTS",
                    "TOP_SALES".equals(request.view()) ? "记录数" : "商品数"),
                    Integer.toString(productCount));
            addDetail(details, fonts, "TOP_SALES".equals(request.view())
                    ? StockExportLayout.translate(request.language(), "PERÍODO", "PERIOD", "期间") : "",
                    "TOP_SALES".equals(request.view())
                            ? request.dateFrom().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                                    + " - " + request.dateTo().format(
                                            java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                            : "");
            document.add(details);

            var grid = new Grid(document, writer, request, fonts,
                    Math.min(writer.getVerticalPosition(true) - 2, LATER_TABLE_TOP));
            var reader = new XSSFReader(pkg);
            reader.setUseReadOnlySharedStringsTable(true);
            var sheets = reader.getSheetsData();
            if (!sheets.hasNext()) throw new IOException("stock_pdf_export_sheet_missing");
            try (var sheet = sheets.next()) {
                var handler = new XSSFSheetXMLHandler(reader.getStylesTable(),
                        reader.getSharedStringsTable(), new Rows(grid, request.columns(), productCount),
                        new DataFormatter(Locale.forLanguageTag(request.language())), false);
                var parser = XMLHelper.newXMLReader();
                parser.setContentHandler(handler);
                parser.parse(new InputSource(sheet));
            }
            grid.finish(productCount);
            document.close();
        } catch (Exception failure) {
            if (document.isOpen()) document.close();
            if (failure instanceof IOException io) throw io;
            throw new IOException("stock_pdf_export_failed", failure);
        }
    }

    private static void addBanner(Document document, Fonts fonts, String text) throws DocumentException {
        var banner = new PdfPTable(1);
        banner.setWidthPercentage(100);
        var title = new PdfPCell(fonts.phrase(text, true, 16, true));
        title.setBackgroundColor(new java.awt.Color(10, 47, 86));
        title.setPadding(12);
        title.setBorder(Rectangle.NO_BORDER);
        banner.addCell(title);
        document.add(banner);
    }

    private static void addDetail(PdfPTable table, Fonts fonts, String label, String value) {
        var cell = new PdfPCell(fonts.phrase(label + "  " + (value == null ? "" : value), false, 8));
        cell.setPadding(5);
        cell.setBorder(Rectangle.NO_BORDER);
        table.addCell(cell);
    }

    private record BandRow(String[] values, boolean total, boolean empty, float height, int stripe) { }

    private static final class Grid {
        private final Document document;
        private final PdfWriter writer;
        private final StockExcelExportService.ExportRequest request;
        private final Fonts fonts;
        private final List<List<Integer>> groups = new ArrayList<>();
        private final List<BandRow> band = new ArrayList<>();
        private final float firstTop;
        private final float headerHeight;
        private float usedHeight;
        private int bandNumber;
        private int dataRows;
        private boolean emptyAdded;

        private Grid(Document document, PdfWriter writer,
                StockExcelExportService.ExportRequest request, Fonts fonts, float firstTop)
                throws DocumentException {
            this.document = document;
            this.writer = writer;
            this.request = request;
            this.fonts = fonts;
            this.firstTop = firstTop;
            int used = 0;
            List<Integer> group = new ArrayList<>();
            for (int i = 0; i < request.columns().size(); i++) {
                int next = StockExportLayout.width(request.columns().get(i).key());
                if (!group.isEmpty() && used + next > TABLE_WIDTH) {
                    groups.add(List.copyOf(group));
                    group = new ArrayList<>();
                    used = 0;
                }
                group.add(i);
                used += next;
            }
            if (!group.isEmpty()) groups.add(List.copyOf(group));
            if (groups.isEmpty()) throw new DocumentException("stock_pdf_export_columns_missing");
            float max = 0;
            for (var slice : groups) {
                var table = table(slice);
                for (int index : slice) table.addCell(cell(
                        request.columns().get(index).label(), true, false, 0,
                        request.columns().get(index).key()));
                max = Math.max(max, table.getRowHeight(0));
            }
            headerHeight = max;
        }

        private PdfPTable table(List<Integer> slice) throws DocumentException {
            var result = new PdfPTable(slice.size());
            var widths = new float[slice.size()];
            for (int i = 0; i < slice.size(); i++) {
                widths[i] = StockExportLayout.width(request.columns().get(slice.get(i)).key());
            }
            result.setWidths(widths);
            result.setTotalWidth(TABLE_WIDTH);
            result.setLockedWidth(true);
            result.setSplitRows(false);
            return result;
        }

        private PdfPCell cell(String text, boolean header, boolean total, int stripe, String key) {
            var cell = new PdfPCell(fonts.phrase(text, header || total, total ? 14 : 8, header));
            cell.setPadding(8);
            cell.setVerticalAlignment(header ? Element.ALIGN_MIDDLE : Element.ALIGN_TOP);
            if (StockExportLayout.totalKeys(request).contains(key) || RIGHT_ALIGNED.contains(key)) {
                cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            }
            if (header) {
                cell.setBackgroundColor(new java.awt.Color(10, 47, 86));
                cell.setBorderColor(new java.awt.Color(10, 47, 86));
            } else if (total) {
                cell.setBackgroundColor(new java.awt.Color(234, 241, 247));
                cell.setBorderColor(new java.awt.Color(10, 47, 86));
            } else {
                cell.setBackgroundColor(stripe % 2 == 0
                        ? java.awt.Color.WHITE : new java.awt.Color(246, 249, 252));
                cell.setBorderColor(new java.awt.Color(223, 229, 236));
            }
            return cell;
        }

        private float rowHeight(String[] values, boolean total, boolean empty, int stripe)
                throws DocumentException {
            float max = 0;
            for (var slice : groups) {
                var table = table(slice);
                if (empty) {
                    var cell = cell(StockExportLayout.translate(request.language(),
                            "Sin datos", "No data", "无数据"), false, false, stripe, "");
                    cell.setColspan(slice.size());
                    table.addCell(cell);
                } else {
                    for (int index : slice) {
                        table.addCell(cell(values[index], false, total, stripe,
                                request.columns().get(index).key()));
                    }
                }
                max = Math.max(max, table.getRowHeight(0));
            }
            return max;
        }

        private float top() { return bandNumber == 0 ? firstTop : LATER_TABLE_TOP; }

        private float capacity() { return top() - TABLE_BOTTOM - headerHeight; }

        private void append(String[] values, boolean total) throws DocumentException {
            if (!total) dataRows++;
            int stripe = dataRows - (total ? 0 : 1);
            String[] remaining = values;
            while (true) {
                float height = rowHeight(remaining, total, false, stripe);
                if (height + usedHeight <= capacity() + ROW_GAP) {
                    band.add(new BandRow(remaining, total, false, height, stripe));
                    usedHeight += height;
                    return;
                }
                if (!band.isEmpty()) {
                    flush();
                    continue;
                }
                // A single cell can be taller than a page. Split each value at its
                // largest fitting Unicode code-point prefix, preserving every character.
                var head = new String[remaining.length];
                var tail = new String[remaining.length];
                boolean continued = false;
                for (int i = 0; i < remaining.length; i++) {
                    String value = remaining[i];
                    int points = value.codePointCount(0, value.length());
                    int low = 0;
                    int high = points;
                    while (low < high) {
                        int mid = (low + high + 1) >>> 1;
                        int end = value.offsetByCodePoints(0, mid);
                        var trial = new String[remaining.length];
                        Arrays.fill(trial, "");
                        trial[i] = value.substring(0, end);
                        if (rowHeight(trial, total, false, stripe) <= capacity() + ROW_GAP) low = mid;
                        else high = mid - 1;
                    }
                    if (low == 0 && points > 0) throw new DocumentException("stock_pdf_row_cannot_fit");
                    int end = value.offsetByCodePoints(0, low);
                    head[i] = value.substring(0, end);
                    tail[i] = value.substring(end);
                    continued |= !tail[i].isEmpty();
                }
                float partHeight = rowHeight(head, total, false, stripe);
                band.add(new BandRow(head, total, false, partHeight, stripe));
                usedHeight += partHeight;
                if (!continued) return;
                flush();
                remaining = tail;
            }
        }

        private void addEmpty() throws DocumentException {
            float height = rowHeight(null, false, true, 0);
            if (height + usedHeight > capacity() && !band.isEmpty()) flush();
            band.add(new BandRow(null, false, true, height, 0));
            usedHeight += height;
            emptyAdded = true;
        }

        private void flush() throws DocumentException {
            if (band.isEmpty()) return;
            for (int sliceNumber = 0; sliceNumber < groups.size(); sliceNumber++) {
                if (bandNumber != 0 || sliceNumber != 0) document.newPage();
                var slice = groups.get(sliceNumber);
                var table = table(slice);
                for (int index : slice) {
                    var header = cell(request.columns().get(index).label(), true, false, 0,
                            request.columns().get(index).key());
                    header.setFixedHeight(headerHeight);
                    table.addCell(header);
                }
                for (var row : band) {
                    if (row.empty()) {
                        var empty = cell(StockExportLayout.translate(request.language(),
                                "Sin datos", "No data", "无数据"), false, false, 0, "");
                        empty.setColspan(slice.size());
                        empty.setFixedHeight(row.height());
                        table.addCell(empty);
                    } else {
                        for (int index : slice) {
                            var cell = cell(row.values()[index], false, row.total(), row.stripe(),
                                    request.columns().get(index).key());
                            cell.setFixedHeight(row.height());
                            table.addCell(cell);
                        }
                    }
                }
                table.writeSelectedRows(0, -1, PAGE_LEFT, top(), writer.getDirectContent());
                String gridPage = StockExportLayout.translate(request.language(),
                        "Bloque ", "Row band ", "行组 ") + (bandNumber + 1)
                        + "  ·  " + StockExportLayout.translate(request.language(),
                                "Columnas ", "Columns ", "列 ") + (sliceNumber + 1)
                        + "/" + groups.size();
                ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_LEFT,
                        fonts.phrase(gridPage, false, 8), PAGE_LEFT, document.bottom() - 20, 0);
            }
            band.clear();
            usedHeight = 0;
            bandNumber++;
        }

        private void finish(int productCount) throws DocumentException {
            if (productCount == 0 && !emptyAdded) addEmpty();
            flush();
        }
    }

    private static final class Rows implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Grid grid;
        private final List<StockExcelExportService.ExportColumn> columns;
        private final int productCount;
        private String[] values;

        private Rows(Grid grid, List<StockExcelExportService.ExportColumn> columns, int productCount) {
            this.grid = grid;
            this.columns = columns;
            this.productCount = productCount;
        }

        @Override
        public void startRow(int rowNum) { values = new String[columns.size()]; }

        @Override
        public void cell(String reference, String formattedValue, Comment comment) {
            int index = new CellReference(reference).getCol();
            if (index < values.length) values[index] = formattedValue;
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum <= StockExportLayout.HEADER_ROW
                    || rowNum > StockExportLayout.HEADER_ROW + productCount + 1) return;
            boolean total = rowNum == StockExportLayout.HEADER_ROW + productCount + 1;
            for (int i = 0; i < values.length; i++) if (values[i] == null) values[i] = "";
            try {
                if (total && productCount == 0) grid.addEmpty();
                grid.append(values, total);
            } catch (DocumentException failure) {
                throw new IllegalStateException("stock_pdf_export_failed", failure);
            }
        }
    }

    private static final class PageNumbers extends PdfPageEventHelper {
        private final Fonts fonts;
        private final String language;
        private PdfTemplate pages;

        private PageNumbers(Fonts fonts, String language) {
            this.fonts = fonts;
            this.language = language;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            pages = writer.getDirectContent().createTemplate(28, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            float y = document.bottom() - 20;
            float x = document.right() - 16;
            String before = StockExportLayout.translate(language, "Página ", "Page ", "第 ")
                    + writer.getPageNumber()
                    + StockExportLayout.translate(language, " de ", " of ", " / 共 ");
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_RIGHT,
                    fonts.phrase(before, false, 8), x, y, 0);
            writer.getDirectContent().addTemplate(pages, document.right() - 12, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            pages.beginText();
            pages.setFontAndSize(fonts.western, 8);
            pages.setTextMatrix(0, 0);
            pages.showText(Integer.toString(writer.getPageNumber() - 1));
            pages.endText();
        }
    }

    private static final class Fonts {
        private final BaseFont western;
        private final BaseFont chinese;

        private Fonts() throws DocumentException, IOException {
            western = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
            chinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", false);
        }

        private Phrase phrase(String text, boolean bold, int size) {
            return phrase(text, bold, size, false);
        }

        private Phrase phrase(String text, boolean bold, int size, boolean white) {
            var selector = new FontSelector();
            int style = bold ? Font.BOLD : Font.NORMAL;
            var color = white ? java.awt.Color.WHITE : new java.awt.Color(10, 47, 86);
            selector.addFont(new Font(western, size, style, color));
            selector.addFont(new Font(chinese, size, style, color));
            return selector.process(text);
        }
    }
}
