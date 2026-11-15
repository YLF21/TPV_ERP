package com.tpverp.backend.excel;

import com.tpverp.backend.catalog.Product;
import com.tpverp.backend.catalog.ProductRepository;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.inventory.WarehouseExportLayout;
import com.tpverp.backend.inventory.WarehouseExportLayout.MetadataField;
import com.tpverp.backend.inventory.WarehouseExportLayout.WorkbookLayout;
import com.tpverp.backend.inventory.WarehouseInput;
import com.tpverp.backend.inventory.WarehouseInputRepository;
import com.tpverp.backend.inventory.WarehouseInputDocumentType;
import com.tpverp.backend.inventory.WarehouseOutput;
import com.tpverp.backend.inventory.WarehouseOutputRepository;
import com.tpverp.backend.organization.Company;
import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.organization.Store;
import com.tpverp.backend.security.application.PermissionChecks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
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
    public byte[] exportInput(UUID documentId, Authentication authentication) {
        if (!PermissionChecks.hasPurchaseDocumentRead(authentication)) {
            throw new AccessDeniedException("Sin permiso para exportar este informe de entradas");
        }
        var store = organization.currentStore();
        var input = inputs.findByIdAndStoreId(documentId, store.getId())
                .orElseThrow(() -> new IllegalArgumentException("Entrada de almacén no encontrada"));
        if (input.getDocumentType() == WarehouseInputDocumentType.ENTRADA_ALMACEN
                && !PermissionChecks.hasWarehouseManagement(authentication)) {
            throw new AccessDeniedException("Sin permiso para exportar entradas de almacen");
        }
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
            var title = switch (input.getDocumentType()) {
                case FACTURA_ENTRADA -> "Entrada de factura";
                case ALBARAN_ENTRADA -> "Entrada de albarán";
                case ENTRADA_ALMACEN -> "Entrada de almacén";
            };
            var sheet = workbook.createSheet(title);
            var styles = layout(workbook, sheet, title, company, store, input.getNumber(),
                    input.getDate().toString(), input.getStatus().name(), input.getWarehouseId());
            int row = styles.headerRow() + 1;
            var productMap = products(input.getLines().stream().map(line -> line.getProductId()).toList(), store.getId());
            for (var line : input.getLines()) {
                var product = productMap.get(line.getProductId());
                var data = sheet.createRow(row++);
                writeLine(data, styles, row - styles.headerRow() - 2,
                        product == null ? line.getProductId().toString() : product.getCode(),
                        product == null ? line.getProductId().toString() : product.getName(),
                        line.getQuantity().doubleValue(), line.getPurchaseUnitPrice(), line.getPurchaseTotal());
            }
            WarehouseExportLayout.autoFilter(sheet, styles.headerRow(), row - 1, 5);
            row++;
            summary(sheet.createRow(row++), "Subtotal", input.getSubtotal(), styles.metaLabel(), styles.money(false));
            var percentageStyle = workbook.createCellStyle();
            percentageStyle.cloneStyleFrom(styles.number(false));
            // POI retains a decimal separator for optional decimals in whole percentages.
            percentageStyle.setDataFormat(workbook.createDataFormat().getFormat(
                    input.getGlobalDiscount().stripTrailingZeros().scale() <= 0 ? "0%" : "0.##%"));
            summary(sheet.createRow(row++), "Descuento total del documento %",
                    input.getGlobalDiscount().movePointLeft(2), styles.metaLabel(), percentageStyle);
            summary(sheet.createRow(row), "Total de compra", input.getTotal(), styles.totalLabel(), styles.totalMoney());
            return bytes(workbook);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar la entrada de almacén", exception);
        }
    }

    private byte[] writeOutput(WarehouseOutput output, Store store, Company company) {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Salida almacén");
            var styles = layout(workbook, sheet, "Salida de almacén", company, store, output.getNumber(),
                    output.getDate().toString(), output.getStatus().name(), output.getWarehouseId());
            int row = styles.headerRow() + 1;
            var productMap = products(output.getLines().stream().map(line -> line.getProductId()).toList(), store.getId());
            for (var line : output.getLines()) {
                var product = productMap.get(line.getProductId());
                var data = sheet.createRow(row++);
                writeLine(data, styles, row - styles.headerRow() - 2,
                        product == null ? line.getProductId().toString() : product.getCode(),
                        product == null ? line.getProductId().toString() : product.getName(),
                        line.getQuantity(), line.getSaleUnitPrice(), line.getSaleTotal());
            }
            WarehouseExportLayout.autoFilter(sheet, styles.headerRow(), row - 1, 5);
            summary(sheet.createRow(row + 1), "Total de venta", output.getLines().stream()
                    .map(line -> line.getSaleTotal()).reduce(BigDecimal.ZERO, BigDecimal::add),
                    styles.totalLabel(), styles.totalMoney());
            return bytes(workbook);
        } catch (IOException exception) {
            throw new IllegalStateException("No se pudo exportar la salida de almacén", exception);
        }
    }

    private WorkbookLayout layout(XSSFWorkbook workbook, org.apache.poi.ss.usermodel.Sheet sheet,
            String title, Company company, Store store, String number, String date, String status, UUID warehouseId) {
        var metadata = new ArrayList<>(WarehouseExportLayout.organizationMetadata(store, company, "es", Instant.now()));
        metadata.add(new MetadataField("Código de tienda", store.getCodigoTienda()));
        metadata.add(new MetadataField("Moneda", store.getMoneda()));
        metadata.add(new MetadataField("Número", number));
        metadata.add(new MetadataField("Fecha", date));
        metadata.add(new MetadataField("Estado", status));
        metadata.add(new MetadataField("Almacén", warehouses.findById(warehouseId)
                .map(value -> value.getName()).orElse(warehouseId.toString())));
        return WarehouseExportLayout.workbook(workbook, sheet, title.toUpperCase(Locale.ROOT), metadata,
                List.of("Código", "Nombre", "Cantidad", "Precio unitario", "Total"), new int[]{24, 52, 14, 20, 20},
                "es", store.getMoneda());
    }

    private void writeLine(Row row, WorkbookLayout styles, int index, String code, String name,
            double quantity, BigDecimal price, BigDecimal total) {
        boolean stripe = index % 2 == 1;
        row.createCell(0).setCellValue(code == null ? "" : code);
        row.createCell(1).setCellValue(name == null ? "" : name);
        row.createCell(2).setCellValue(quantity);
        row.getCell(2).setCellStyle(styles.number(stripe));
        money(row, 3, price, styles.money3(stripe));
        money(row, 4, total, styles.money(stripe));
        WarehouseExportLayout.bodyRow(row, styles, index);
    }

    private static void summary(Row row, String label, BigDecimal value, CellStyle labelStyle, CellStyle valueStyle) {
        for (int c = 0; c < 4; c++) row.createCell(c).setCellStyle(labelStyle);
        row.getCell(0).setCellValue(label);
        row.getSheet().addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 3));
        money(row, 4, value, valueStyle);
        row.setHeightInPoints(30);
    }

    private Map<UUID, Product> products(List<UUID> ids, UUID storeId) {
        return products.findAllByStoreIdAndIdIn(storeId, ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        var cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static byte[] bytes(Workbook workbook) throws IOException {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return output.toByteArray();
    }
}
