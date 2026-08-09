package com.tpverp.backend.excel;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentLine;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentExcelExportService {

    private final CommercialDocumentRepository documents;
    private final CurrentOrganization organization;

    public DocumentExcelExportService(
            CommercialDocumentRepository documents,
            CurrentOrganization organization) {
        this.documents = documents;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public byte[] export(UUID documentId) {
        return writeWorkbook(List.of(document(documentId)));
    }

    @Transactional(readOnly = true)
    public byte[] export(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new IllegalArgumentException("documentos es obligatorio");
        }
        return writeWorkbook(documents.findAllById(documentIds));
    }

    private byte[] writeWorkbook(List<CommercialDocument> values) {
        try (var workbook = new XSSFWorkbook()) {
            var store = organization.currentStore();
            var company = organization.currentCompany();
            for (var document : values) {
                writeDocument(workbook, document, store, company);
            }
            var output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar Excel", exception);
        }
    }

    private CommercialDocument document(UUID documentId) {
        return documents.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Documento no encontrado"));
    }

    private static void writeDocument(
            Workbook workbook,
            CommercialDocument document,
            Store store,
            Company company) {
        var sheet = workbook.createSheet(sheetName(document));
        var money = moneyStyle(workbook);
        var metadataLabel = metadataLabelStyle(workbook);
        var metadataValue = metadataValueStyle(workbook);
        var tableHeader = tableHeaderStyle(workbook);
        var summaryLabel = summaryLabelStyle(workbook);
        var summaryValue = summaryValueStyle(workbook);
        var totalValue = totalValueStyle(workbook);
        int row = 0;
        pair(sheet.createRow(row++), "Empresa", company.getRazonSocial());
        pair(sheet.createRow(row++), "NIF", company.getTaxId());
        pair(sheet.createRow(row++), "Tienda", store.getCodigoTienda());
        pair(sheet.createRow(row++), "Nombre de la tienda", store.getNombreEfectivo());
        pair(sheet.createRow(row++), "Moneda", document.getMoneda());
        pair(sheet.getRow(0), 3, "Tipo", document.getTipo().name());
        pair(sheet.getRow(1), 3, "Número", document.getNumero());
        pair(sheet.getRow(2), 3, "Fecha", document.getFecha().toString());
        pair(sheet.getRow(3), 3, "Estado", document.getEstado().name());
        styleMetadata(sheet, metadataLabel, metadataValue);
        row++;
        int headerRowIndex = row;
        var header = sheet.createRow(row++);
        write(header, "Código", "Nombre", "Cantidad", "Precio",
                "Dto %", "Impuesto %", "Base", "Impuesto", "Total");
        style(header, tableHeader, 9);
        for (var line : document.getLineas()) {
            writeLine(sheet.createRow(row++), line, money);
        }
        row++;
        var base = sheet.createRow(row++);
        moneyPair(base, "Base", document.getBaseTotal(), money);
        base.getCell(0).setCellStyle(summaryLabel);
        base.getCell(1).setCellStyle(summaryValue);
        var tax = sheet.createRow(row++);
        moneyPair(tax, "Impuesto", document.getImpuestoTotal(), money);
        tax.getCell(0).setCellStyle(summaryLabel);
        tax.getCell(1).setCellStyle(summaryValue);
        var total = sheet.createRow(row);
        moneyPair(total, "Total", document.getTotal(), money);
        total.getCell(0).setCellStyle(tableHeader);
        total.getCell(1).setCellStyle(totalValue);
        sheet.createFreezePane(0, headerRowIndex + 1);
        for (int index = 0; index < 9; index++) {
            sheet.autoSizeColumn(index);
        }
    }

    private static int pair(Row row, String key, String value) {
        pair(row, 0, key, value);
        return row.getRowNum() + 1;
    }

    private static void pair(Row row, int column, String key, String value) {
        row.createCell(column).setCellValue(key);
        row.createCell(column + 1).setCellValue(value == null ? "" : value);
    }

    private static CellStyle moneyStyle(Workbook workbook) {
        var style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
        return style;
    }

    private static CellStyle metadataLabelStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style, BorderStyle.THIN, IndexedColors.GREY_40_PERCENT);
        return style;
    }

    private static CellStyle metadataValueStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style, BorderStyle.THIN, IndexedColors.GREY_40_PERCENT);
        return style;
    }

    private static CellStyle tableHeaderStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style, BorderStyle.THIN, IndexedColors.GREY_40_PERCENT);
        return style;
    }

    private static CellStyle summaryLabelStyle(Workbook workbook) {
        return metadataLabelStyle(workbook);
    }

    private static CellStyle summaryValueStyle(Workbook workbook) {
        var style = metadataValueStyle(workbook);
        style.setDataFormat(moneyStyle(workbook).getDataFormat());
        return style;
    }

    private static CellStyle totalValueStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style, BorderStyle.THIN, IndexedColors.GREY_40_PERCENT);
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
        return style;
    }

    private static void applyBorder(CellStyle style, BorderStyle border, IndexedColors color) {
        style.setBorderTop(border);
        style.setBorderBottom(border);
        style.setBorderLeft(border);
        style.setBorderRight(border);
        style.setTopBorderColor(color.getIndex());
        style.setBottomBorderColor(color.getIndex());
        style.setLeftBorderColor(color.getIndex());
        style.setRightBorderColor(color.getIndex());
    }

    private static void styleMetadata(org.apache.poi.ss.usermodel.Sheet sheet,
                                      CellStyle labelStyle,
                                      CellStyle valueStyle) {
        for (int rowIndex = 0; rowIndex < 5; rowIndex++) {
            var row = sheet.getRow(rowIndex);
            row.getCell(0).setCellStyle(labelStyle);
            row.getCell(1).setCellStyle(valueStyle);
            if (rowIndex < 4) {
                row.getCell(3).setCellStyle(labelStyle);
                row.getCell(4).setCellStyle(valueStyle);
            }
        }
    }

    private static void style(Row row, CellStyle style, int columns) {
        for (int column = 0; column < columns; column++) row.getCell(column).setCellStyle(style);
    }

    private static void writeLine(Row row, DocumentLine line, CellStyle moneyStyle) {
        row.createCell(0).setCellValue(line.getCodigo());
        row.createCell(1).setCellValue(line.getNombre());
        row.createCell(2).setCellValue(line.getCantidad().doubleValue());
        money(row, 3, line.getPrecioUnitario(), moneyStyle);
        row.createCell(4).setCellValue(line.getDescuento().doubleValue());
        row.createCell(5).setCellValue(line.getPorcentajeImpuesto().doubleValue());
        money(row, 6, line.getBase(), moneyStyle);
        money(row, 7, line.getImpuesto(), moneyStyle);
        money(row, 8, line.getTotal(), moneyStyle);
    }

    private static void moneyPair(Row row, String label, java.math.BigDecimal value, CellStyle style) {
        row.createCell(0).setCellValue(label);
        money(row, 1, value, style);
    }

    private static void money(Row row, int column, java.math.BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void write(Row row, String... values) {
        for (int index = 0; index < values.length; index++) {
            row.createCell(index).setCellValue(values[index] == null ? "" : values[index]);
        }
    }

    private static String sheetName(CommercialDocument document) {
        var value = document.getNumero() == null ? document.getTipo().name() : document.getNumero();
        return value.replaceAll("[\\\\/?*\\[\\]:]", "-")
                .substring(0, Math.min(31, value.length()));
    }
}
