package com.tpverp.backend.document.template;

import com.tpverp.backend.document.CustomerModel347Report;
import com.tpverp.backend.document.CustomerModel347Report.Party;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.util.JRStringUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** Presentation of the already aggregated informational customer statement. */
@Service
public class CustomerModel347JasperRenderer {

    private static final String TEMPLATE =
            "reports/customer-documents/MODELO_347_RESUMEN_A4.jrxml";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final Charset WESTERN_PDF_CHARSET = Charset.forName("windows-1252");
    private final SafeJrxmlCompiler compiler;
    private byte[] compiled;

    public CustomerModel347JasperRenderer(SafeJrxmlCompiler compiler) {
        this.compiler = compiler;
    }

    public byte[] render(CustomerModel347Report report, String locale) {
        Objects.requireNonNull(report, "report");
        var labels = Labels.forLocale(locale);
        var parameters = new LinkedHashMap<String, Object>();
        parameters.put("TITLE", labels.title());
        parameters.put("SUBTITLE", labels.subtitle());
        parameters.put("YEAR", labels.year() + ": " + report.year());
        parameters.put("ISSUER_LABEL", labels.issuer());
        parameters.put("CUSTOMER_LABEL", labels.customer());
        parameters.put("ISSUER", partyText(report.issuer(), labels, false));
        parameters.put("CUSTOMER", partyText(report.customer(), labels, true));
        parameters.put("QUARTER_LABEL", labels.quarter());
        parameters.put("PERIOD_LABEL", labels.period());
        parameters.put("AMOUNT_LABEL", labels.amount());
        parameters.put("TOTAL_LABEL", labels.annualTotal());
        parameters.put("ANNUAL_TOTAL", money(report.annualTotal(), labels.numberLocale()));
        parameters.put("SCOPE", labels.scope());
        parameters.put("NOTICE", labels.notice());
        parameters.put("PAGE_LABEL", labels.page());
        parameters.replaceAll((key, value) -> styledText((String) value));

        var rows = new ArrayList<Map<String, ?>>();
        for (var quarter : report.quarters()) {
            var from = LocalDate.of(report.year(), (quarter.number() - 1) * 3 + 1, 1);
            rows.add(Map.of(
                    "quarter", labels.quarterPrefix() + quarter.number(),
                    "period", DATE.format(from) + " - " + DATE.format(from.plusMonths(3).minusDays(1)),
                    "amount", money(quarter.total(), labels.numberLocale())));
        }
        try {
            var context = SafeJrxmlCompiler.secureContext();
            var print = JasperFillManager.getInstance(context).fill(
                    new ByteArrayInputStream(compiledTemplate()), parameters,
                    new JRMapCollectionDataSource(rows));
            return JasperExportManager.getInstance(context).exportToPdf(print);
        } catch (JRException exception) {
            throw new IllegalStateException("customer_model347_jasper_render_failed", exception);
        }
    }

    private synchronized byte[] compiledTemplate() {
        if (compiled == null) {
            try (var input = new ClassPathResource(TEMPLATE).getInputStream()) {
                compiled = compiler.compile(input.readAllBytes()).compiled();
            } catch (IOException exception) {
                throw new IllegalStateException("customer_model347_jasper_template_missing", exception);
            }
        }
        return compiled;
    }

    private static String partyText(Party party, Labels labels, boolean customer) {
        if (party == null) {
            return "";
        }
        return (customer ? labels.code() + ": " + text(party.code()) + "\n" : "")
                + labels.name() + ": " + text(party.name()) + "\n"
                + labels.taxId() + ": " + text(party.taxId()) + "\n"
                + labels.address() + ": " + text(party.address());
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static String money(BigDecimal value, Locale locale) {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(locale))
                .format(Objects.requireNonNull(value, "amount")) + " EUR";
    }

    /** Jasper styled text selects the built-in PDF font per run, without interpreting party markup. */
    private static String styledText(String value) {
        var encoder = WESTERN_PDF_CHARSET.newEncoder();
        var result = new StringBuilder();
        var run = new StringBuilder();
        boolean cjkRun = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            var character = new String(Character.toChars(codePoint));
            boolean cjk = !encoder.canEncode(character);
            if (cjk != cjkRun && !run.isEmpty()) {
                appendRun(result, run, cjkRun);
                run.setLength(0);
            }
            cjkRun = cjk;
            run.append(character);
            offset += Character.charCount(codePoint);
        }
        appendRun(result, run, cjkRun);
        return result.toString();
    }

    private static void appendRun(StringBuilder target, StringBuilder run, boolean cjk) {
        if (cjk) {
            // These CID metrics and mappings are already included in OpenPDF; no OS font path is used.
            target.append("<style pdfFontName=\"STSong-Light\" pdfEncoding=\"UniGB-UCS2-H\">");
        }
        target.append(JRStringUtil.xmlEncode(run.toString()));
        if (cjk) {
            target.append("</style>");
        }
    }

    private record Labels(
            String title, String subtitle, String year, String issuer, String customer,
            String code, String name, String taxId, String address, String quarter,
            String quarterPrefix, String period, String amount, String annualTotal,
            String scope, String notice, String page, Locale numberLocale) {

        private static Labels forLocale(String value) {
            var language = value == null ? "es"
                    : Locale.forLanguageTag(value.replace('_', '-')).getLanguage();
            return switch (language) {
                case "en" -> new Labels(
                        "FORM 347", "Informational customer summary", "Year", "ISSUER", "CUSTOMER",
                        "Code", "Name / legal name", "Tax ID", "Address", "Quarter", "Q", "Date range",
                        "Amount (EUR)", "ANNUAL TOTAL",
                        "All company stores. Invoices and credit notes, including taxes and their sign, "
                                + "whether paid or outstanding. Grouped by document date; drafts and cancelled "
                                + "documents excluded. No minimum amount.",
                        "Informational summary. This document is not an official Form 347 filing.",
                        "Page", Locale.UK);
                case "zh" -> new Labels(
                        "347 表", "客户往来信息汇总", "年度", "出具方", "客户",
                        "编号", "名称 / 公司名称", "税号", "地址", "季度", "T", "日期范围",
                        "金额 (EUR)", "年度合计",
                        "涵盖公司所有门店。包含发票及更正发票，金额含税并保留正负号，包含已付款和未付款单据。"
                                + "按单据日期归属季度；不含草稿及已作废单据，不设最低金额。",
                        "本文件仅为信息汇总，并非正式的 347 表申报文件。", "页", Locale.CHINA);
                default -> new Labels(
                        "MODELO 347", "Resumen informativo de operaciones con el cliente", "Ejercicio",
                        "EMISOR", "CLIENTE", "Código", "Nombre / razón social", "NIF", "Domicilio",
                        "Trimestre", "T", "Período", "Importe (EUR)", "TOTAL ANUAL",
                        "Todas las tiendas de la empresa. Facturas y rectificativas con impuestos incluidos "
                                + "y su signo, pagadas o pendientes. Según fecha del documento; excluye borradores "
                                + "y anulados. Sin importe mínimo.",
                        "Resumen informativo. Este documento no constituye una declaración oficial del modelo 347.",
                        "Página", Locale.forLanguageTag("es-ES"));
            };
        }
    }
}
