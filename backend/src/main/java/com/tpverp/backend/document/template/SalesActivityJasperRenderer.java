package com.tpverp.backend.document.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tpverp.backend.document.SalesActivityDocumentRowView;
import com.tpverp.backend.document.SalesActivityPrintGrouping;
import com.tpverp.backend.document.SalesDailySummaryView;
import com.tpverp.backend.organization.CurrentOrganization;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.json.query.JsonQueryExecuterFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class SalesActivityJasperRenderer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private final SafeJrxmlCompiler compiler;
    private final ObjectMapper mapper;
    private final CurrentOrganization organization;
    private final Map<String, byte[]> compiled = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>());

    public SalesActivityJasperRenderer(
            SafeJrxmlCompiler compiler,
            ObjectMapper mapper,
            CurrentOrganization organization) {
        this.compiler = compiler;
        this.mapper = mapper;
        this.organization = organization;
    }

    public RenderedReport renderDaily(
            SalesDailySummaryView summary, DocumentTemplateFormat format) {
        var root = baseModel("RESUMEN DE VENTAS DIARIAS", DATE.format(summary.date()),
                summary.netSalesTotal());
        var lines = root.putArray("lines");
        if (format == DocumentTemplateFormat.TICKET_80) {
            appendTicketSummary(lines, summary.netSalesTotal(),
                    summary.paymentMethods(), summary.counts());
            for (var user : summary.users()) {
                line(lines, "SECTION", "USUARIO: " + user.userName(), "");
                appendTicketSummary(lines, user.netSalesTotal(),
                        user.paymentMethods(), user.counts());
            }
        } else {
            appendSummary(lines, "TOTAL TIENDA", summary.netSalesTotal(),
                    summary.paymentMethods(), summary.counts());
            for (var user : summary.users()) {
                appendSummary(lines, "USUARIO: " + user.userName(), user.netSalesTotal(),
                        user.paymentMethods(), user.counts());
            }
        }
        return render(root, format == DocumentTemplateFormat.TICKET_80
                ? "RESUMEN_VENTAS_DIA_TICKET_80.jrxml"
                : "RESUMEN_VENTAS_DIA_A4.jrxml", format);
    }

    public RenderedReport renderDocuments(
            List<SalesActivityDocumentRowView> rows,
            LocalDate from,
            LocalDate to,
            SalesActivityPrintGrouping grouping,
            DocumentTemplateFormat format) {
        var total = rows.stream().map(SalesActivityDocumentRowView::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        var period = from.equals(to) ? DATE.format(from)
                : DATE.format(from) + " - " + DATE.format(to);
        var title = format == DocumentTemplateFormat.TICKET_80
                ? grouping == SalesActivityPrintGrouping.DOCUMENT
                        ? "DOCUMENTOS DE VENTAS - POR DOCUMENTO"
                        : "DOCUMENTOS DE VENTAS - POR DIA"
                : "DOCUMENTOS DE VENTAS";
        var root = baseModel(title, period, total);
        root.put("grouping", grouping.name());
        var lines = root.putArray("lines");
        if (format == DocumentTemplateFormat.TICKET_80) {
            appendTicketDocumentLines(lines, rows, grouping);
        } else {
            appendA4DocumentLines(lines, rows, grouping);
        }
        return render(root, format == DocumentTemplateFormat.TICKET_80
                ? "DOCUMENTOS_VENTAS_TICKET_80.jrxml"
                : "DOCUMENTOS_VENTAS_A4.jrxml", format);
    }

    private void appendA4DocumentLines(
            ArrayNode lines,
            List<SalesActivityDocumentRowView> rows,
            SalesActivityPrintGrouping grouping) {
        String previousDate = "";
        for (var row : rows) {
            String date = DATE.format(row.date());
            if (grouping == SalesActivityPrintGrouping.DAY && !date.equals(previousDate)) {
                line(lines, "SECTION", date, "");
                previousDate = date;
            }
            var occurred = row.occurredAt() == null ? "" : row.occurredAt()
                    .atZone(ZoneId.of(organization.currentStore().getTimezone()))
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
            String number = !row.ticketNumber().isBlank() && !row.invoiceNumber().isBlank()
                    ? row.ticketNumber() + " -> " + row.invoiceNumber()
                    : !row.ticketNumber().isBlank() ? row.ticketNumber() : row.invoiceNumber();
            String details = String.join(" | ", java.util.stream.Stream.of(
                            date + (occurred.isBlank() ? "" : " " + occurred), number,
                            row.userName(), String.join(", ", row.paymentMethods()),
                            displayStatus(row))
                    .filter(value -> value != null && !value.isBlank()).toList());
            line(lines, grouping == SalesActivityPrintGrouping.DOCUMENT
                    ? "DOCUMENT" : "ROW", details, money(row.total()));
        }
    }

    private static void appendTicketDocumentLines(
            ArrayNode lines,
            List<SalesActivityDocumentRowView> rows,
            SalesActivityPrintGrouping grouping) {
        if (grouping == SalesActivityPrintGrouping.DAY) {
            var totalsByDate = new LinkedHashMap<LocalDate, BigDecimal>();
            rows.forEach(row -> totalsByDate.merge(
                    row.date(), row.total(), BigDecimal::add));
            totalsByDate.forEach((date, total) ->
                    line(lines, "DAY", DATE.format(date), money(total)));
            return;
        }
        LocalDate previousDate = null;
        for (var row : rows) {
            if (!row.date().equals(previousDate)) {
                line(lines, "DATE", DATE.format(row.date()), "");
                previousDate = row.date();
            }
            String number = row.ticketNumber() == null || row.ticketNumber().isBlank()
                    ? row.invoiceNumber() : row.ticketNumber();
            line(lines, "DOCUMENT", number, displayStatus(row), money(row.total()));
        }
    }

    private ObjectNode baseModel(String title, String period, BigDecimal total) {
        var store = organization.currentStore();
        var root = mapper.createObjectNode();
        root.put("title", title);
        root.put("companyName", organization.currentCompany().getRazonSocial());
        root.put("storeCode", store.getCodigoTienda());
        root.put("dataPeriod", period);
        root.put("printedAt", LocalDateTime.now(ZoneId.of(store.getTimezone())).format(DATE_TIME));
        root.put("grandTotal", money(total));
        return root;
    }

    private static void appendSummary(
            ArrayNode lines,
            String title,
            BigDecimal total,
            List<SalesDailySummaryView.PaymentTotalView> methods,
            SalesDailySummaryView.ActivityCountsView counts) {
        line(lines, "SECTION", title, "");
        for (var method : methods) {
            line(lines, "PAYMENT", paymentLabel(method.method().name())
                    + ": (" + method.operationCount() + ")", money(method.amount()));
        }
        line(lines, "TOTAL", "TOTAL", money(total));
        line(lines, "COUNT", "VENTAS", Long.toString(counts.sales()));
        line(lines, "COUNT", "DEVOLUCIONES", Long.toString(counts.returns()));
        line(lines, "COUNT", "ANULADOS", Long.toString(counts.cancelled()));
        line(lines, "COUNT", "PENDIENTES", Long.toString(counts.pending()));
    }

    private static void appendTicketSummary(
            ArrayNode lines,
            BigDecimal total,
            List<SalesDailySummaryView.PaymentTotalView> methods,
            SalesDailySummaryView.ActivityCountsView counts) {
        for (var method : methods) {
            line(lines, "PAYMENT", ticketPaymentLabel(method.method().name())
                    + ": (" + method.operationCount() + ")", money(method.amount()));
        }
        line(lines, "TOTAL", "TOTAL:", money(total));
        line(lines, "COUNT", "VENTAS", Long.toString(counts.sales()));
        line(lines, "COUNT", "DEVOLUCIONES", Long.toString(counts.returns()));
        line(lines, "COUNT", "ANULADOS", Long.toString(counts.cancelled()));
        line(lines, "COUNT", "PENDIENTES", Long.toString(counts.pending()));
        line(lines, "END", "", "");
    }

    private static String paymentLabel(String value) {
        return switch (value) {
            case "PENDIENTE" -> "PENDIENTE DE COBRO";
            case "OTROS" -> "OTROS";
            default -> value;
        };
    }

    private static String ticketPaymentLabel(String value) {
        return "PENDIENTE".equals(value) ? "PENDIENTE" : paymentLabel(value);
    }

    private static String displayStatus(SalesActivityDocumentRowView row) {
        return switch (row.kind()) {
            case SALE -> row.status().name();
            case RETURN -> "DEVOLUCION";
            case CANCELLED -> "ANULADO";
        };
    }

    private static String money(BigDecimal value) {
        return String.format(java.util.Locale.ROOT, "%.2f€",
                value == null ? BigDecimal.ZERO : value);
    }

    private static void line(ArrayNode target, String type, String label, String value) {
        line(target, type, label, "", value);
    }

    private static void line(
            ArrayNode target, String type, String label, String secondary, String value) {
        var line = target.addObject();
        line.put("lineType", type);
        line.put("label", label == null ? "" : label);
        line.put("secondary", secondary == null ? "" : secondary);
        line.put("value", value == null ? "" : value);
    }

    private RenderedReport render(
            ObjectNode model, String filename, DocumentTemplateFormat format) {
        try {
            byte[] compiledTemplate = compiled.computeIfAbsent(filename, this::compile);
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put(JsonQueryExecuterFactory.JSON_INPUT_STREAM,
                    new ByteArrayInputStream(mapper.writeValueAsBytes(model)));
            var context = SafeJrxmlCompiler.secureContext();
            var print = JasperFillManager.getInstance(context).fill(
                    new ByteArrayInputStream(compiledTemplate), parameters);
            byte[] pdf = JasperExportManager.getInstance(context).exportToPdf(print);
            byte[] image = format == DocumentTemplateFormat.TICKET_80
                    ? InvoiceJasperRenderer.ticketRaster(print) : null;
            return new RenderedReport(pdf, image);
        } catch (IOException | JRException exception) {
            throw new IllegalStateException("sales_activity_jasper_render_failed", exception);
        }
    }

    private byte[] compile(String filename) {
        var resource = new ClassPathResource("reports/sales-activity/" + filename);
        try (var input = resource.getInputStream()) {
            return compiler.compile(input.readAllBytes()).compiled();
        } catch (IOException exception) {
            throw new IllegalStateException("sales_activity_jasper_template_missing", exception);
        }
    }

    public record RenderedReport(byte[] pdf, byte[] ticketRasterPng) {
    }
}
