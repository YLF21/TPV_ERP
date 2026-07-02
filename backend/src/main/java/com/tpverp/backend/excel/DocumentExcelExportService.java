package com.tpverp.backend.excel;

import com.tpverp.backend.document.CommercialDocument;
import com.tpverp.backend.document.CommercialDocumentRepository;
import com.tpverp.backend.document.DocumentLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentExcelExportService {

    private final CommercialDocumentRepository documents;

    public DocumentExcelExportService(CommercialDocumentRepository documents) {
        this.documents = documents;
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
            for (var document : values) {
                writeDocument(workbook, document);
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

    private static void writeDocument(Workbook workbook, CommercialDocument document) {
        var sheet = workbook.createSheet(sheetName(document));
        int row = 0;
        row = pair(sheet.createRow(row++), "Tipo", document.getTipo().name());
        row = pair(sheet.createRow(row++), "Numero", document.getNumero());
        row = pair(sheet.createRow(row++), "Fecha", document.getFecha().toString());
        row++;
        write(sheet.createRow(row++), "Codigo", "Nombre", "Cantidad", "Precio",
                "Dto %", "Impuesto %", "Base", "Impuesto", "Total");
        for (var line : document.getLineas()) {
            writeLine(sheet.createRow(row++), line);
        }
        row++;
        pair(sheet.createRow(row++), "Base", document.getBaseTotal().toPlainString());
        pair(sheet.createRow(row++), "Impuesto", document.getImpuestoTotal().toPlainString());
        pair(sheet.createRow(row), "Total", document.getTotal().toPlainString());
        for (int index = 0; index < 9; index++) {
            sheet.autoSizeColumn(index);
        }
    }

    private static int pair(Row row, String key, String value) {
        row.createCell(0).setCellValue(key);
        row.createCell(1).setCellValue(value == null ? "" : value);
        return row.getRowNum() + 1;
    }

    private static void writeLine(Row row, DocumentLine line) {
        write(row,
                line.getCodigo(),
                line.getNombre(),
                String.valueOf(line.getCantidad()),
                line.getPrecioUnitario().toPlainString(),
                line.getDescuento().toPlainString(),
                line.getPorcentajeImpuesto().toPlainString(),
                line.getBase().toPlainString(),
                line.getImpuesto().toPlainString(),
                line.getTotal().toPlainString());
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
