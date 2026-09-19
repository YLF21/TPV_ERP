package com.tpverp.backend.document.template;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.sf.jasperreports.engine.JRBand;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;

/** Fits the opt-in history table to the requested columns without modifying cached artifacts. */
final class StockSalesHistoryJasperLayout {

    private static final String COLUMN_KEY_PREFIX = "history.column.";
    private static final List<String> DEFAULT_COLUMNS = List.of(
            "occurredAt", "document", "customer", "quantity", "unitPrice", "total");

    private StockSalesHistoryJasperLayout() {}

    static JasperReport prepare(byte[] compiled, ObjectNode data, Map<String, Object> parameters)
            throws JRException {
        // Each export gets a fresh report: widths from one request must never leak to another.
        var report = (JasperReport) JRLoader.loadObject(new ByteArrayInputStream(compiled));
        var weights = new LinkedHashMap<String, Integer>();
        if (report.getColumnHeader() != null) {
            for (var element : report.getColumnHeader().getElements()) {
                if (element.getKey() != null && element.getKey().startsWith(COLUMN_KEY_PREFIX)) {
                    weights.put(element.getKey().substring(COLUMN_KEY_PREFIX.length()), element.getWidth());
                }
            }
        }
        // Existing imported templates retain their own layout and original JSON fields.
        if (weights.isEmpty()) return report;
        var columns = new java.util.ArrayList<String>();
        data.path("visibleColumns").forEach(value -> {
            var key = value.asText();
            if (weights.containsKey(key) && !columns.contains(key)) columns.add(key);
        });
        if (columns.isEmpty()) DEFAULT_COLUMNS.stream().filter(weights::containsKey).forEach(columns::add);
        if (columns.isEmpty()) columns.addAll(weights.keySet());
        weights.keySet().forEach(key -> parameters.put("TPV_HISTORY_" + key, columns.contains(key)));
        parameters.put("TPV_HISTORY_DETAILS", data.path("issuer").path("details").asText());
        parameters.put("TPV_HISTORY_FILTERS", data.path("document").path("concept").asText());
        parameters.put("TPV_HISTORY_EMPTY", data.path("lines").isEmpty());
        parameters.put(JRParameter.REPORT_LOCALE, Locale.forLanguageTag("es-ES"));
        int totalWeight = columns.stream().mapToInt(weights::get).sum();
        var positions = new LinkedHashMap<String, int[]>();
        int x = 0;
        for (int index = 0; index < columns.size(); index++) {
            var key = columns.get(index);
            int width = index == columns.size() - 1 ? report.getColumnWidth() - x
                    : report.getColumnWidth() * weights.get(key) / totalWeight;
            positions.put(key, new int[] {x, width});
            x += width;
        }
        position(report.getColumnHeader(), positions);
        for (var band : report.getDetailSection().getBands()) position(band, positions);
        return report;
    }

    private static void position(JRBand band, Map<String, int[]> positions) {
        for (var element : band.getElements()) {
            if (element.getKey() == null || !element.getKey().startsWith(COLUMN_KEY_PREFIX)) continue;
            var position = positions.get(element.getKey().substring(COLUMN_KEY_PREFIX.length()));
            if (position != null) {
                element.setX(position[0]);
                element.setWidth(position[1]);
            }
        }
    }
}
