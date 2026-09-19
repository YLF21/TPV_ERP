package com.tpverp.backend.document.template;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.sf.jasperreports.engine.JRBand;
import net.sf.jasperreports.engine.JRTextElement;
import net.sf.jasperreports.engine.type.HorizontalTextAlignEnum;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.util.JRLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** One adaptive, repository-owned Jasper grid for detail and store comparisons. */
@Service
public class SaasProductSalesHistoryJasperRenderer {
    private final SafeJrxmlCompiler compiler;
    private byte[] compiled;
    public SaasProductSalesHistoryJasperRenderer(SafeJrxmlCompiler compiler) { this.compiler = compiler; }
    public byte[] render(String title, String metadata, String totals, String pageLabel, List<String> headers,
            List<Integer> weights, List<Boolean> numeric, List<List<String>> cells) {
        if (headers.isEmpty() || headers.size() > 12 || headers.size() != weights.size() || headers.size() != numeric.size()) throw new IllegalArgumentException("columns");
        try {
            var report = (JasperReport) JRLoader.loadObject(new ByteArrayInputStream(template()));
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("TITLE", styled(title)); parameters.put("METADATA", styled(metadata));
            parameters.put("TOTALS", styled(totals)); parameters.put("PAGE_LABEL", styled(pageLabel));
            parameters.put("COLUMN_COUNT", headers.size());
            for (int i = 0; i < headers.size(); i++) parameters.put("LABEL_" + i, styled(headers.get(i)));
            int sum = weights.stream().mapToInt(Integer::intValue).sum();
            int[] widths = new int[headers.size()]; int allocated = 0;
            for (int i = 0; i < widths.length; i++) {
                widths[i] = i == widths.length - 1 ? report.getColumnWidth() - allocated : report.getColumnWidth() * weights.get(i) / sum;
                allocated += widths[i];
            }
            resize(report.getColumnHeader(), widths);
            for (var band : report.getDetailSection().getBands()) {
                resize(band, widths);
                for (int i = 0; i < numeric.size(); i++) {
                    for (var element : band.getElements()) if (("column." + i).equals(element.getKey()) && element instanceof JRTextElement text) {
                        text.setHorizontalTextAlign(numeric.get(i) ? HorizontalTextAlignEnum.RIGHT : HorizontalTextAlignEnum.LEFT);
                    }
                }
            }
            var rows = new ArrayList<Map<String, ?>>();
            for (var values : cells) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 0; i < headers.size(); i++) row.put("CELL_" + i, styled(values.get(i)));
                rows.add(row);
            }
            var context = SafeJrxmlCompiler.secureContext();
            var print = JasperFillManager.getInstance(context).fill(report, parameters, new JRMapCollectionDataSource(rows));
            return JasperExportManager.getInstance(context).exportToPdf(print);
        } catch (Exception exception) { throw new IllegalStateException("product_history_pdf_failed", exception); }
    }
    private synchronized byte[] template() throws java.io.IOException {
        if (compiled == null) {
            try (var source = new ClassPathResource("reports/documents/v1/HISTORIAL_VENTAS_SAAS_A4.jrxml").getInputStream()) {
                compiled = compiler.compile(source.readAllBytes()).compiled();
            }
        }
        return compiled;
    }
    private static String styled(String value) { return CustomerModel347JasperRenderer.styledText(value == null ? "" : value); }
    private static void resize(JRBand band, int[] widths) {
        int x = 0;
        for (int i = 0; i < widths.length; i++) {
            for (var element : band.getElements()) if (("column." + i).equals(element.getKey())) {
                element.setX(x); element.setWidth(widths[i]);
            }
            x += widths[i];
        }
    }
}
