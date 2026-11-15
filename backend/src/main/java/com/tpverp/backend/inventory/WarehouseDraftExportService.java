package com.tpverp.backend.inventory;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseDraftExportService {
    private static final int[] WIDTHS = {12, 24, 52, 18, 44};
    private final CurrentOrganization organization;

    public WarehouseDraftExportService(CurrentOrganization organization) {
        this.organization = organization;
    }

    @Transactional(readOnly = true)
    public byte[] export(WarehouseDraftExportController.Request request, boolean input, Authentication authentication) {
        if (request == null || request.lines() == null || request.lines().size() > 5000) {
            throw new IllegalArgumentException("Documento inválido");
        }
        if (input) {
            if (!PermissionChecks.hasPurchaseDocumentRead(authentication)) {
                throw new AccessDeniedException("Sin permiso para exportar este informe de entradas");
            }
            if (request.inputDocumentType() == null) {
                throw new IllegalArgumentException("Tipo de entrada obligatorio");
            }
            if (request.inputDocumentType() == WarehouseInputDocumentType.ENTRADA_ALMACEN
                    && !PermissionChecks.hasWarehouseManagement(authentication)) {
                throw new AccessDeniedException("Sin permiso para exportar entradas de almacen");
            }
        }
        var store = organization.currentStore();
        var locale = request.locale() == null ? store.getLocale() : request.locale();
        var metadata = new ArrayList<>(WarehouseExportLayout.organizationMetadata(
                store, organization.currentCompany(), locale, Instant.now()));
        add(metadata, locale, "Tipo", "Type", "类型", request.documentType());
        add(metadata, locale, "Documento", "Document", "单据", request.number());
        add(metadata, locale, "Estado", "Status", "状态", request.status());
        if (request.date() != null) add(metadata, locale, "Fecha", "Date", "日期", request.date().toString());
        add(metadata, locale, "Almacén", "Warehouse", "仓库", request.warehouse());
        var title = input ? switch (request.inputDocumentType()) {
            case ENTRADA_ALMACEN -> WarehouseExportLayout.tr(locale, "ENTRADA DE ALMACÉN", "WAREHOUSE INPUT", "仓库入库");
            case ALBARAN_ENTRADA -> WarehouseExportLayout.tr(locale, "ALBARÁN DE COMPRA", "PURCHASE DELIVERY NOTE", "采购送货单");
            case FACTURA_ENTRADA -> WarehouseExportLayout.tr(locale, "FACTURA DE COMPRA", "PURCHASE INVOICE", "采购发票");
        } : WarehouseExportLayout.tr(locale, "SALIDA DE ALMACÉN", "WAREHOUSE OUTPUT", "仓库出库");
        var headers = List.of(
                WarehouseExportLayout.tr(locale, "Fila", "Row", "行"),
                WarehouseExportLayout.tr(locale, "Código", "Code", "编码"),
                WarehouseExportLayout.tr(locale, "Producto", "Product", "商品"),
                WarehouseExportLayout.tr(locale, "Cantidad", "Quantity", "数量"),
                WarehouseExportLayout.tr(locale, "Estado de validación", "Validation status", "校验状态"));
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(input ? "Entrada" : "Salida");
            var layout = WarehouseExportLayout.workbook(workbook, sheet, title, metadata, headers, WIDTHS, locale);
            int rowNumber = layout.headerRow() + 1;
            int index = 0;
            for (var line : request.lines()) {
                var row = sheet.createRow(rowNumber++);
                row.createCell(0).setCellValue(line.row() == null ? index + 1 : line.row());
                row.createCell(1).setCellValue(Objects.toString(line.code(), ""));
                row.createCell(2).setCellValue(Objects.toString(line.name(), ""));
                if (line.quantity() != null) row.createCell(3).setCellValue(line.quantity().doubleValue());
                row.createCell(4).setCellValue(Objects.toString(line.validationStatus(), ""));
                WarehouseExportLayout.bodyRow(row, layout, index++);
                row.getCell(0).setCellStyle(layout.number((index - 1) % 2 == 1));
                if (line.quantity() != null) row.getCell(3).setCellStyle(layout.number((index - 1) % 2 == 1));
            }
            WarehouseExportLayout.autoFilter(sheet, layout.headerRow(), rowNumber - 1, headers.size());
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("No se pudo generar el Excel del documento", error);
        }
    }

    private static void add(List<WarehouseExportLayout.MetadataField> values, String locale,
                            String es, String en, String zh, String value) {
        if (value != null && !value.isBlank()) {
            values.add(new WarehouseExportLayout.MetadataField(WarehouseExportLayout.tr(locale, es, en, zh), value));
        }
    }
}
