package com.tpverp.backend.inventory;

import com.tpverp.backend.catalog.Warehouse;
import com.tpverp.backend.catalog.WarehouseRepository;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class WarehouseTransferListExportService {
    private static final int MAX_ROWS = 50_000;
    private static final float[] WIDTHS = {108, 86, 118, 118, 172, 74, 44, 64};
    private final WarehouseTransferDocumentService transfers;
    private final WarehouseRepository warehouses;
    private final CurrentOrganization organization;

    public WarehouseTransferListExportService(WarehouseTransferDocumentService transfers,
            WarehouseRepository warehouses, CurrentOrganization organization) {
        this.transfers = transfers; this.warehouses = warehouses; this.organization = organization;
    }

    // Every batch belongs to the same database snapshot, even if new documents are created during the download.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public byte[] export(String format, WarehouseTransferListExportController.Filters filters) {
        if (!Set.of("pdf", "xlsx").contains(format)) throw new IllegalArgumentException("Formato no válido");
        var store = organization.currentStore();
        var copy = new Copy(filters.locale());
        var names = warehouses.findByStoreIdOrderByNombre(store.getId()).stream()
                .collect(Collectors.toMap(Warehouse::getId, Warehouse::getName));
        var dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of(store.getTimezone()));
        var rows = new ArrayList<List<String>>();
        for (int page = 0; ; page++) {
            var batch = transfers.listFiltered(page, 100,
                    filters.statuses() == null ? WarehouseTransferDocumentService.single(filters.status()) : filters.statuses(),
                    filters.sourceWarehouseIds() == null ? WarehouseTransferDocumentService.single(filters.sourceWarehouseId()) : filters.sourceWarehouseIds(),
                    filters.targetWarehouseIds() == null ? WarehouseTransferDocumentService.single(filters.targetWarehouseId()) : filters.targetWarehouseIds(),
                    filters.from(), filters.before(), filters.search());
            for (var item : batch.items()) {
                if (rows.size() >= MAX_ROWS) throw new IllegalArgumentException("El informe supera 50000 documentos");
                rows.add(List.of(item.number() == null ? copy.status(WarehouseTransferDocument.Status.DRAFT) : item.number(),
                        dateFormat.format(item.createdAt()), names.getOrDefault(item.sourceWarehouseId(), ""),
                        names.getOrDefault(item.targetWarehouseId(), ""), Objects.toString(item.notes(), ""),
                        copy.status(item.status()), Long.toString(item.lineCount()), item.totalUnits().toPlainString()));
            }
            if (!batch.hasMore()) break;
            if (batch.items().isEmpty()) throw new IllegalStateException("No se pudo completar el informe");
        }
        try {
            var metadata = WarehouseExportLayout.organizationMetadata(store, organization.currentCompany(),
                    copy.locale(), java.time.Instant.now());
            return format.equals("xlsx") ? excel(copy, metadata, rows) : pdf(copy, metadata, rows);
        } catch (IOException exception) { throw new IllegalStateException("No se pudo exportar el listado de traspasos", exception); }
    }

    private byte[] excel(Copy copy, List<WarehouseExportLayout.MetadataField> metadata, List<List<String>> rows) throws IOException {
        try (var book = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(copy.title());
            var layout = WarehouseExportLayout.workbook(book, sheet, copy.title(), metadata,
                    copy.headers(), new int[] {23, 21, 25, 25, 47, 18, 14, 18}, copy.locale());
            for (int r = 0; r < rows.size(); r++) {
                var row = sheet.createRow(layout.headerRow() + r + 1);
                for (int c = 0; c < 8; c++) {
                    var cell = row.createCell(c);
                    if (c >= 6) { cell.setCellValue(Double.parseDouble(rows.get(r).get(c))); cell.setCellStyle(layout.number(r % 2 == 1)); }
                    else { cell.setCellValue(rows.get(r).get(c)); cell.setCellStyle(layout.text(r % 2 == 1)); }
                }
                WarehouseExportLayout.bodyRow(row, layout, r);
            }
            WarehouseExportLayout.autoFilter(sheet, layout.headerRow(), layout.headerRow() + rows.size(), 8);
            book.write(output); return output.toByteArray();
        }
    }

    private byte[] pdf(Copy copy, List<WarehouseExportLayout.MetadataField> metadata, List<List<String>> rows) throws IOException {
        var numbers = java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag(copy.locale() == null ? "es" : copy.locale()));
        numbers.setMaximumFractionDigits(3);
        var formatted = rows.stream().map(row -> {
            var values = new ArrayList<String>();
            for (int c = 0; c < 8; c++) values.add(c >= 6 && !row.get(c).isBlank()
                    ? numbers.format(new java.math.BigDecimal(row.get(c))) : row.get(c));
            return new WarehouseExportLayout.PdfRow(values);
        }).toList();
        return WarehouseExportLayout.pdf(new WarehouseExportLayout.PdfSpec(copy.title(), copy.locale(),
                metadata, copy.headers(), WIDTHS, formatted));
    }

    private record Copy(String locale) {
        String choose(String es, String en, String zh) { return locale != null && locale.startsWith("zh") ? zh
                : locale != null && locale.startsWith("en") ? en : es; }
        String title() { return choose("Traspasos de almacén", "Warehouse transfers", "仓库调拨"); }
        List<String> headers() { return List.of(choose("Número", "Number", "编号"), choose("Fecha", "Date", "日期"),
                choose("Origen", "Source", "来源仓库"), choose("Destino", "Destination", "目标仓库"),
                choose("Notas", "Notes", "备注"), choose("Estado", "Status", "状态"),
                choose("Líneas", "Lines", "行数"), choose("Unidades", "Units", "数量")); }
        String status(WarehouseTransferDocument.Status status) { return switch (status) {
            case DRAFT -> choose("Borrador", "Draft", "草稿");
            case CONFIRMED -> choose("Confirmado", "Confirmed", "已确认");
            case CANCELLED -> choose("Cancelado", "Cancelled", "已取消");
        }; }
    }
}
