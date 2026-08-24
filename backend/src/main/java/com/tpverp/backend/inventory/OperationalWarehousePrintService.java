package com.tpverp.backend.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.template.DocumentTemplateFormat;
import com.tpverp.backend.document.template.DocumentTemplateType;
import com.tpverp.backend.document.template.OperationalDocumentJasperRenderer;
import com.tpverp.backend.document.template.RenderedDocumentView;
import com.tpverp.backend.organization.CurrentOrganization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds persisted warehouse/history views and sends them through Jasper. */
@Service
public class OperationalWarehousePrintService {

    private static final Set<String> HISTORY_COLUMNS = Set.of(
            "occurredAt", "document", "status", "customer", "quantity", "unitPrice", "discount", "total", "user", "store", "warehouse");

    private final ObjectMapper mapper;
    private final CurrentOrganization organization;
    private final WarehouseInputService inputs;
    private final WarehouseOutputService outputs;
    private final StockSalesHistoryService history;
    private final OperationalDocumentJasperRenderer renderer;

    public OperationalWarehousePrintService(
            ObjectMapper mapper,
            CurrentOrganization organization,
            WarehouseInputService inputs,
            WarehouseOutputService outputs,
            StockSalesHistoryService history,
            OperationalDocumentJasperRenderer renderer) {
        this.mapper = mapper;
        this.organization = organization;
        this.inputs = inputs;
        this.outputs = outputs;
        this.history = history;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public RenderedDocumentView input(UUID id) {
        var view = inputs.view(id);
        var type = switch (view.documentType()) {
            case ALBARAN_ENTRADA -> DocumentTemplateType.ALBARAN_ENTRADA;
            case FACTURA_ENTRADA -> DocumentTemplateType.FACTURA_ENTRADA;
            case ENTRADA_ALMACEN -> DocumentTemplateType.ENTRADA_ALMACEN;
        };
        var data = base(view.number(), view.date(), view.status().name(),
                "Almacén: " + view.warehouseId() + " · Proveedor/origen: " + firstNonBlank(view.origin(), view.supplierId()),
                view.concept(), view.status() == WarehouseInputStatus.BORRADOR);
        var lines = (ArrayNode) data.withArray("lines");
        view.lines().forEach(line -> addLine(lines, line.productCode(), line.productName(), line.quantity(), line.purchaseTotal()));
        return renderer.render(type, DocumentTemplateFormat.A4, data, safeFileName(type, view.number()));
    }

    @Transactional(readOnly = true)
    public RenderedDocumentView output(UUID id) {
        var view = outputs.view(id);
        var data = base(view.number(), view.date(), view.status().name(),
                "Almacén: " + view.warehouseId() + " · Destino: " + firstNonBlank(view.destination(), null),
                view.concept(), view.status() == WarehouseOutputStatus.BORRADOR);
        var lines = (ArrayNode) data.withArray("lines");
        view.lines().forEach(line -> addLine(lines, line.productCode(), line.productName(),
                BigDecimal.valueOf(line.quantity()), line.saleTotal()));
        return renderer.render(DocumentTemplateType.SALIDA_ALMACEN, DocumentTemplateFormat.A4,
                data, safeFileName(DocumentTemplateType.SALIDA_ALMACEN, view.number()));
    }

    @Transactional(readOnly = true)
    public RenderedDocumentView salesHistory(UUID productId, HistoryPrintCommand command) {
        var columns = command == null || command.columns() == null || command.columns().isEmpty()
                ? List.of("occurredAt", "document", "customer", "quantity", "unitPrice", "total")
                : command.columns();
        if (columns.stream().anyMatch(column -> !HISTORY_COLUMNS.contains(column))) {
            throw new IllegalArgumentException("sales_history_column_not_allowed");
        }
        var rows = history.history(productId, command == null ? null : command.from(), command == null ? null : command.to());
        if (command != null && command.status() != null && !command.status().isBlank()) {
            rows = rows.stream().filter(row -> row.status() != null && command.status().equals(row.status().name())).toList();
        }
        var sortBy = command == null || command.sortBy() == null ? "occurredAt" : command.sortBy();
        if (!HISTORY_COLUMNS.contains(sortBy)) {
            throw new IllegalArgumentException("sales_history_sort_column_not_allowed");
        }
        var sortedRows = rows.stream().sorted(historyComparator(sortBy,
                command == null ? null : command.sortDirection())).toList();
        var data = base("", LocalDate.now(), "CONFIRMADO", "Producto: " + productId,
                "Columnas: " + String.join(", ", columns) + " · Orden: "
                        + sortBy, false);
        var lines = (ArrayNode) data.withArray("lines");
        sortedRows.forEach(row -> {
            var line = lines.addObject();
            line.put("date", row.occurredAt() == null ? "" : row.occurredAt().toString());
            line.put("occurredAt", row.occurredAt() == null ? "" : row.occurredAt().toString());
            line.put("document", value(row.documentNumber()));
            line.put("status", row.status() == null ? "" : row.status().name());
            line.put("customer", value(row.customerName()));
            line.put("quantity", row.quantity());
            line.put("unitPrice", row.unitPrice());
            line.put("discount", row.discountPercent());
            line.put("total", row.lineTotal());
            line.put("user", value(row.userName()));
            line.put("store", value(row.storeName()));
            line.put("warehouse", value(row.warehouseName()));
        });
        return renderer.render(DocumentTemplateType.HISTORIAL_VENTAS_PRODUCTO, DocumentTemplateFormat.A4,
                data, "historial-ventas-producto.pdf");
    }

    private ObjectNode base(String number, LocalDate date, String status, String origin, String concept, boolean draft) {
        var data = mapper.createObjectNode();
        var issuer = data.putObject("issuer");
        var store = organization.currentStore();
        var company = organization.currentCompany();
        issuer.put("name", company.getRazonSocial());
        issuer.put("taxId", company.getTaxId());
        issuer.put("store", store.getNombreEfectivo());
        issuer.put("details", company.getRazonSocial() + " · NIF " + company.getTaxId()
                + " · Tienda " + store.getNombreEfectivo() + " · " + origin);
        var document = data.putObject("document");
        document.put("displayNumber", value(number));
        document.put("date", date == null ? "" : date.toString());
        document.put("status", value(status));
        document.put("concept", value(concept));
        document.put("watermark", draft ? "BORRADOR" : "");
        data.putArray("lines");
        return data;
    }

    private static void addLine(ArrayNode lines, String code, String name, BigDecimal quantity, BigDecimal value) {
        var line = lines.addObject();
        line.put("code", value(code));
        line.put("articleName", value(name));
        line.put("quantity", quantity == null ? BigDecimal.ZERO : quantity);
        line.put("value", value == null ? "" : value.toPlainString());
    }

    private static String firstNonBlank(String value, Object fallback) {
        if (value != null && !value.isBlank()) return value;
        return fallback == null ? "" : fallback.toString();
    }

    private static String safeFileName(DocumentTemplateType type, String number) {
        var suffix = number == null ? "documento" : number.replaceAll("[^A-Za-z0-9_-]", "_");
        return type.name().toLowerCase(Locale.ROOT) + "-" + suffix + ".pdf";
    }

    private static String value(String value) { return value == null ? "" : value; }

    private static Comparator<StockSalesHistoryRow> historyComparator(String key, String direction) {
        Comparator<StockSalesHistoryRow> comparator = switch (key) {
            case "document" -> Comparator.comparing(StockSalesHistoryRow::documentNumber,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "status" -> Comparator.comparing(row -> row.status() == null ? null : row.status().name(),
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "customer" -> Comparator.comparing(StockSalesHistoryRow::customerName,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "quantity" -> Comparator.comparing(StockSalesHistoryRow::quantity,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "unitPrice" -> Comparator.comparing(StockSalesHistoryRow::unitPrice,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "discount" -> Comparator.comparing(StockSalesHistoryRow::discountPercent,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "total" -> Comparator.comparing(StockSalesHistoryRow::lineTotal,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            case "warehouse" -> Comparator.comparing(StockSalesHistoryRow::warehouseName,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "store" -> Comparator.comparing(StockSalesHistoryRow::storeName,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            case "user" -> Comparator.comparing(StockSalesHistoryRow::userName,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
            default -> Comparator.comparing(StockSalesHistoryRow::occurredAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        return "desc".equalsIgnoreCase(direction) ? comparator.reversed() : comparator;
    }

    public record HistoryPrintCommand(
            LocalDate from,
            LocalDate to,
            List<String> columns,
            String sortBy,
            String sortDirection,
            String status) {
    }
}
