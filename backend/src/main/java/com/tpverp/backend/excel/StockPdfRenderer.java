package com.tpverp.backend.excel;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.FontSelector;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.xml.sax.InputSource;

/** Reads the streaming export workbook one row at a time into paginated PDF tables. */
final class StockPdfRenderer {

    private static final int ROWS_PER_TABLE = 100;

    private StockPdfRenderer() {
    }

    static void render(Path workbook, Path output,
            StockExcelExportService.ExportRequest request) throws IOException {
        var page = request.columns().size() > 20 ? PageSize.A1.rotate()
                : request.columns().size() > 12 ? PageSize.A2.rotate() : PageSize.A3.rotate();
        var document = new Document(page, 24, 24, 28, 24);
        try (var stream = Files.newOutputStream(output);
                var pkg = OPCPackage.open(workbook.toFile(), PackageAccess.READ)) {
            PdfWriter.getInstance(document, stream);
            try {
                var fonts = new Fonts();
                document.open();
                document.add(new Paragraph(fonts.phrase(title(request), true)));
                document.add(new Paragraph(" "));

                var reader = new XSSFReader(pkg);
                reader.setUseReadOnlySharedStringsTable(true);
                var sheets = reader.getSheetsData();
                if (!sheets.hasNext()) throw new IOException("stock_pdf_export_sheet_missing");
                var content = new TableRows(document, request.columns(), fonts, request.language());
                try (var sheet = sheets.next()) {
                    var handler = new XSSFSheetXMLHandler(reader.getStylesTable(),
                            reader.getSharedStringsTable(), content,
                            new DataFormatter(Locale.forLanguageTag(request.language())), false);
                    var parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(handler);
                    parser.parse(new InputSource(sheet));
                }
                content.finish();
            } finally {
                document.close();
            }
        } catch (Exception failure) {
            if (failure instanceof IOException io) throw io;
            throw new IOException("stock_pdf_export_failed", failure);
        }
    }

    private static String title(StockExcelExportService.ExportRequest request) {
        return switch (request.view() == null ? "CURRENT_STOCK" : request.view()) {
            case "TOP_SALES" -> translate(request.language(), "Más vendidos", "Top sales", "热销商品");
            case "OFFERS" -> translate(request.language(), "Productos con oferta", "Products on offer", "优惠商品");
            case "MEMBER_PRICE" -> translate(request.language(), "Precio de miembro", "Member prices", "会员价商品");
            case "NO_DISCOUNT" -> translate(request.language(), "Productos sin descuento", "Non-discountable products", "不可折扣商品");
            default -> translate(request.language(), "Stock actual", "Current stock", "当前库存");
        };
    }

    private static String translate(String language, String es, String en, String zh) {
        return "zh".equals(language) ? zh : "en".equals(language) ? en : es;
    }

    private static final class Fonts {
        private final BaseFont western;
        private final BaseFont chinese;

        private Fonts() throws DocumentException, IOException {
            western = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, false);
            chinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", false);
        }

        private Phrase phrase(String text, boolean bold) {
            var selector = new FontSelector();
            int style = bold ? Font.BOLD : Font.NORMAL;
            selector.addFont(new Font(western, bold ? 10 : 8, style));
            selector.addFont(new Font(chinese, bold ? 10 : 8, style));
            return selector.process(text);
        }
    }

    private static final class TableRows implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Document document;
        private final List<StockExcelExportService.ExportColumn> columns;
        private final Fonts fonts;
        private final String language;
        private PdfPTable table;
        private String[] values;
        private int inTable;
        private int rows;

        private TableRows(Document document,
                List<StockExcelExportService.ExportColumn> columns,
                Fonts fonts, String language) throws DocumentException {
            this.document = document;
            this.columns = columns;
            this.fonts = fonts;
            this.language = language;
            table = newTable();
        }

        private PdfPTable newTable() throws DocumentException {
            var next = new PdfPTable(columns.size());
            next.setWidthPercentage(100);
            next.setHeaderRows(1);
            for (var column : columns) next.addCell(cell(column.label(), true));
            return next;
        }

        private PdfPCell cell(String text, boolean header) {
            var cell = new PdfPCell(fonts.phrase(text, header));
            cell.setPadding(4);
            if (header) cell.setBackgroundColor(new java.awt.Color(229, 235, 242));
            return cell;
        }

        @Override
        public void startRow(int rowNum) {
            values = new String[columns.size()];
        }

        @Override
        public void cell(String reference, String formattedValue,
                org.apache.poi.xssf.usermodel.XSSFComment comment) {
            int index = new CellReference(reference).getCol();
            if (index < values.length) values[index] = formattedValue;
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) return;
            try {
                for (String value : values) table.addCell(cell(value == null ? "" : value, false));
                rows++;
                if (++inTable == ROWS_PER_TABLE) flush();
            } catch (DocumentException failure) {
                throw new IllegalStateException("stock_pdf_export_failed", failure);
            }
        }

        private void flush() throws DocumentException {
            document.add(table);
            table = newTable();
            inTable = 0;
        }

        private void finish() throws DocumentException {
            if (rows == 0) {
                var empty = new PdfPCell(fonts.phrase(
                        translate(language, "Sin datos", "No data", "无数据"), false));
                empty.setColspan(columns.size());
                empty.setPadding(4);
                table.addCell(empty);
            }
            if (inTable > 0 || rows == 0) document.add(table);
        }
    }
}
