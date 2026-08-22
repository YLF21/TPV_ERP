package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseOutput;
import com.tpverp.backend.inventory.WarehouseOutputRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseDocumentExcelExportService {

    private final WarehouseInputRepository inputs;
    private final WarehouseOutputRepository outputs;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;

    public WarehouseDocumentExcelExportService(
            WarehouseInputRepository inputs,
            WarehouseOutputRepository outputs,
            ProductRepository products,
            WarehouseRepository warehouses,
            CurrentOrganization organization) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.products = products;
        this.warehouses = warehouses;
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public byte[] exportInput(UUID documentId) {
        var store = organization.currentStore();
        var input = inputs.findById(documentId)
                .filter(value -> store.getId().equals(value.getStoreId()))
                .orElseThrow(() -> new IllegalArgumentException("Entrada de almacén no encontrada"));
        return writeInput(input, store, organization.currentCompany());
    }

    @Transactional(readOnly = true)
    public byte[] exportOutput(UUID documentId) {
        var store = organization.currentStore();
        var output = outputs.findById(documentId)
                .filter(value -> store.getId().equals(value.getStoreId()))
                .orElseThrow(() -> new IllegalArgumentException("Salida de almacén no encontrada"));
        return writeOutput(output, store, organization.currentCompany());
    }

    private byte[] writeInput(WarehouseInput input, Store store, Company company) {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Entrada almacén");
        var money = moneyStyle(workbook);
        var metadataLabel = metadataLabelStyle(workbook);
        var metadataValue = metadataValueStyle(workbook);
        var tableHeader = tableHeaderStyle(workbook);
            int row = metadata(sheet, company, store, "Entrada de almacén", input.getNumber(),
                    input.getDate().toString(), input.getStatus().name(), input.getWarehouseId());
        styleMetadata(sheet, metadataLabel, metadataValue);
            int headerRowIndex = row;
            var header = sheet.createRow(row++);
            write(header, "Código", "Nombre", "Cantidad", "Precio unitario", "Total");
            style(header, tableHeader, 5);
            var productMap = products(input.getLines().stream().map(line -> line.getProductId()).toList(), store.getId());
            for (var line : input.getLines()) {
                var product = productMap.get(line.getProductId());
                var data = sheet.createRow(row++);
                text(data, 0, product == null ? line.getProductId().toString() : product.getCode());
                text(data, 1, product == null ? line.getProductId().toString() : product.getName());
                data.createCell(2).setCellValue(line.getQuantity().doubleValue());
                money(data, 3, line.getPurchaseUnitPrice(), money);
                money(data, 4, line.getPurchaseTotal(), money);
            }
            row++;
            var total = sheet.createRow(row);
            moneyPair(total, "Total de compra", input.getLines().stream()
                    .map(line -> line.getPurchaseTotal()).reduce(BigDecimal.ZERO, BigDecimal::add), money);
            styleTotal(total, workbook);
            finish(sheet, 5, headerRowIndex);
            return bytes(workbook);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar la entrada de almacén", exception);
        }
    }

    private byte[] writeOutput(WarehouseOutput output, Store store, Company company) {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Salida almacén");
        var money = moneyStyle(workbook);
        var metadataLabel = metadataLabelStyle(workbook);
        var metadataValue = metadataValueStyle(workbook);
        var tableHeader = tableHeaderStyle(workbook);
            int row = metadata(sheet, company, store, "Salida de almacén", output.getNumber(),
                    output.getDate().toString(), output.getStatus().name(), output.getWarehouseId());
        styleMetadata(sheet, metadataLabel, metadataValue);
            int headerRowIndex = row;
            var header = sheet.createRow(row++);
            write(header, "Código", "Nombre", "Cantidad", "Precio unitario", "Total");
            style(header, tableHeader, 5);
            var productMap = products(output.getLines().stream().map(line -> line.getProductId()).toList(), store.getId());
            for (var line : output.getLines()) {
                var product = productMap.get(line.getProductId());
                var data = sheet.createRow(row++);
                text(data, 0, product == null ? line.getProductId().toString() : product.getCode());
                text(data, 1, product == null ? line.getProductId().toString() : product.getName());
                data.createCell(2).setCellValue(line.getQuantity());
                money(data, 3, line.getSaleUnitPrice(), money);
                money(data, 4, line.getSaleTotal(), money);
            }
            row++;
            var total = sheet.createRow(row);
            moneyPair(total, "Total de venta", output.getLines().stream()
                    .map(line -> line.getSaleTotal()).reduce(BigDecimal.ZERO, BigDecimal::add), money);
            styleTotal(total, workbook);
            finish(sheet, 5, headerRowIndex);
            return bytes(workbook);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar la salida de almacén", exception);
        }
    }

    private int metadata(org.apache.poi.ss.usermodel.Sheet sheet, Company company, Store store, String type,
            String number, String date, String status, UUID warehouseId) {
        int row = 0;
        pair(sheet.createRow(row++), "Empresa", company.getRazonSocial());
        pair(sheet.createRow(row++), "NIF", company.getTaxId());
        pair(sheet.createRow(row++), "Tienda", store.getCodigoTienda());
        pair(sheet.createRow(row++), "Nombre de la tienda", store.getNombreEfectivo());
        pair(sheet.createRow(row++), "Moneda", store.getMoneda());
        var warehouse = warehouses.findById(warehouseId).map(value -> value.getName()).orElse(warehouseId.toString());
        pair(sheet.getRow(0), 3, "Documento", type);
        pair(sheet.getRow(1), 3, "Número", number);
        pair(sheet.getRow(2), 3, "Fecha", date);
        pair(sheet.getRow(3), 3, "Estado", status);
        pair(sheet.getRow(4), 3, "Almacén", warehouse);
        return row + 1;
    }

    private Map<UUID, Product> products(java.util.List<UUID> ids, UUID storeId) {
        return products.findAllByStoreIdAndIdIn(storeId, ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
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
        applyBorder(style);
        return style;
    }

    private static CellStyle metadataValueStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style);
        return style;
    }

    private static CellStyle tableHeaderStyle(Workbook workbook) {
        var font = workbook.createFont();
        font.setBold(true);
        var style = workbook.createCellStyle();
        style.setFont(font);
        applyBorder(style);
        return style;
    }

    private static void applyBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
    }

    private static void styleTotal(Row row, Workbook workbook) {
        row.getCell(0).setCellStyle(tableHeaderStyle(workbook));
        var valueStyle = tableHeaderStyle(workbook);
        valueStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00 [$€-es-ES]"));
        row.getCell(1).setCellStyle(valueStyle);
    }

    private static void styleMetadata(org.apache.poi.ss.usermodel.Sheet sheet,
                                      CellStyle labelStyle,
                                      CellStyle valueStyle) {
        for (int rowIndex = 0; rowIndex < 5; rowIndex++) {
            var row = sheet.getRow(rowIndex);
            row.getCell(0).setCellStyle(labelStyle);
            row.getCell(1).setCellStyle(valueStyle);
            row.getCell(3).setCellStyle(labelStyle);
            row.getCell(4).setCellStyle(valueStyle);
        }
    }

    private static void style(Row row, CellStyle style, int columns) {
        for (int column = 0; column < columns; column++) row.getCell(column).setCellStyle(style);
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static void moneyPair(Row row, String label, BigDecimal value, CellStyle style) {
        text(row, 0, label);
        money(row, 1, value, style);
    }

    private static void pair(Row row, String label, String value) {
        pair(row, 0, label, value);
    }

    private static void pair(Row row, int column, String label, String value) {
        text(row, column, label);
        text(row, column + 1, value);
    }

    private static void text(Row row, int column, String value) {
        row.createCell(column).setCellValue(value == null ? "" : value);
    }

    private static void write(Row row, String... values) {
        for (int index = 0; index < values.length; index++) text(row, index, values[index]);
    }

    private static void finish(org.apache.poi.ss.usermodel.Sheet sheet, int columns, int headerRowIndex) {
        sheet.createFreezePane(0, headerRowIndex + 1);
        for (int index = 0; index < columns; index++) sheet.autoSizeColumn(index);
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
