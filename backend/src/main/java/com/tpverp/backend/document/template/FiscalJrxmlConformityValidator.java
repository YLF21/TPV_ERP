package com.tpverp.backend.document.template;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Structural gate evaluated independently for every trusted fiscal JRXML route. */
final class FiscalJrxmlConformityValidator {

    private static final Set<String> TICKET_FISCAL_ROUTES = Set.of(
            "ticket_pie.jrxml",
            "ticket_pie_compacta.jrxml",
            "ticket_pie_minimalista.jrxml");

    private FiscalJrxmlConformityValidator() {
    }

    static void require(Map<String, byte[]> sources, DocumentTemplateType type) {
        if (!isFiscal(type)) {
            return;
        }
        for (var route : fiscalRoutes(sources, type).entrySet()) {
            requireRoute(route.getKey(), route.getValue());
        }
    }

    private static boolean isFiscal(DocumentTemplateType type) {
        return type == DocumentTemplateType.TICKET
                || type == DocumentTemplateType.FACTURA_VENTA
                || type == DocumentTemplateType.RECTIFICATIVA_VENTA;
    }

    private static Map<String, byte[]> fiscalRoutes(
            Map<String, byte[]> sources, DocumentTemplateType type) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("document_template_fiscal_snapshot_required");
        }
        if (type != DocumentTemplateType.TICKET) {
            return sources;
        }
        if (sources.keySet().equals(Set.of(TicketJrxmlBundleCompiler.MASTER_FILENAME))) {
            return sources;
        }
        var routes = new LinkedHashMap<String, byte[]>();
        for (String filename : TICKET_FISCAL_ROUTES) {
            byte[] source = sources.get(filename);
            if (source == null) {
                throw new IllegalArgumentException(
                        "document_template_fiscal_route_missing:" + filename);
            }
            routes.put(filename, source);
        }
        return routes;
    }

    private static void requireRoute(String filename, byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException(
                    "document_template_fiscal_snapshot_required:" + filename);
        }
        var jrxml = new String(source, StandardCharsets.UTF_8);
        if (!jrxml.contains("snapshot_impresion_fiscal")
                && !jrxml.contains("fiscalVerificationUrl")) {
            throw new IllegalArgumentException("document_template_fiscal_snapshot_required");
        }
        if (!jrxml.contains("qr_url") && !jrxml.contains("fiscalVerificationUrl")
                && !jrxml.contains("contenido_qr")) {
            throw new IllegalArgumentException("document_template_fiscal_qr_url_required");
        }

        var document = parse(source);
        requirePrintedExpression(document,
                new String[] {"fiscalQrPrefix", "qr_prefijo"},
                "document_template_fiscal_qr_label_required");
        requirePrintedExpression(document,
                new String[] {"fiscalLegend", "qr_leyenda"},
                "document_template_fiscal_legend_required");
        requirePrintedExpression(document,
                new String[] {"fiscalTestNotice", "aviso_pruebas"},
                "document_template_fiscal_test_notice_required");
        requireConformingQr(document);
    }

    private static Document parse(byte[] source) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(source));
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "document_template_fiscal_qr_xml_invalid", exception);
        }
    }

    private static void requirePrintedExpression(
            Document document, String[] markers, String error) {
        var elements = document.getElementsByTagName("element");
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (Element) elements.item(index);
            if (!"textField".equals(element.getAttribute("kind"))) {
                continue;
            }
            var expressions = element.getElementsByTagName("expression");
            for (int expressionIndex = 0;
                    expressionIndex < expressions.getLength(); expressionIndex++) {
                if (containsAny(expressions.item(expressionIndex).getTextContent(), markers)) {
                    return;
                }
            }
        }
        var legacy = document.getElementsByTagName("textField");
        for (int index = 0; index < legacy.getLength(); index++) {
            if (containsAny(legacy.item(index).getTextContent(), markers)) {
                return;
            }
        }
        throw new IllegalArgumentException(error);
    }

    private static boolean containsAny(String value, String[] markers) {
        if (value == null) {
            return false;
        }
        for (var marker : markers) {
            if (value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void requireConformingQr(Document document) {
        var fiscalQrFound = false;
        var correctionLevelValid = false;
        var quietZoneValid = false;
        var sizeValid = false;
        var conformingQrFound = false;
        var elements = document.getElementsByTagName("element");
        for (int index = 0; index < elements.getLength(); index++) {
            var element = (Element) elements.item(index);
            if (!"component".equals(element.getAttribute("kind"))) {
                continue;
            }
            var components = element.getElementsByTagName("component");
            for (int componentIndex = 0;
                    componentIndex < components.getLength(); componentIndex++) {
                var component = (Element) components.item(componentIndex);
                if (!"barcode4j:QRCode".equals(component.getAttribute("kind"))) {
                    continue;
                }
                var expressions = component.getElementsByTagName("codeExpression");
                if (expressions.getLength() == 0
                        || !isPersistedFiscalQrExpression(
                                expressions.item(0).getTextContent())) {
                    continue;
                }
                fiscalQrFound = true;
                boolean currentCorrectionValid = "M".equals(
                        component.getAttribute("errorCorrectionLevel"));
                boolean currentQuietZoneValid = parsePositiveInt(
                        component.getAttribute("margin")) >= 4;
                boolean currentSizeValid = parsePositiveInt(element.getAttribute("width")) >= 99
                        && parsePositiveInt(element.getAttribute("height")) >= 99;
                correctionLevelValid |= currentCorrectionValid;
                quietZoneValid |= currentQuietZoneValid;
                sizeValid |= currentSizeValid;
                conformingQrFound |= currentCorrectionValid
                        && currentQuietZoneValid && currentSizeValid;
            }
        }
        if (!fiscalQrFound) {
            throw new IllegalArgumentException("document_template_fiscal_qr_component_required");
        }
        if (!correctionLevelValid) {
            throw new IllegalArgumentException(
                    "document_template_fiscal_qr_error_correction_required");
        }
        if (!quietZoneValid) {
            throw new IllegalArgumentException(
                    "document_template_fiscal_qr_quiet_zone_required");
        }
        if (!sizeValid) {
            throw new IllegalArgumentException("document_template_fiscal_qr_size_required");
        }
        if (!conformingQrFound) {
            throw new IllegalArgumentException("document_template_fiscal_qr_conformity_required");
        }
    }

    private static boolean isPersistedFiscalQrExpression(String expression) {
        return expression != null
                && (expression.contains("qr_url")
                        || expression.contains("fiscalVerificationUrl")
                        || expression.contains("contenido_qr"));
    }

    private static int parsePositiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
