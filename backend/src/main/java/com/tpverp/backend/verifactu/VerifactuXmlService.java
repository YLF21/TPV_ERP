package com.tpverp.backend.verifactu;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.io.ByteArrayInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

@Component
public class VerifactuXmlService {

    private static final String LR_NS = "https://www2.agenciatributaria.gob.es/static_files/common/"
            + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroLR.xsd";
    private static final String SF_NS = "https://www2.agenciatributaria.gob.es/static_files/common/"
            + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter XML_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    public String batchXml(VerifactuXmlBatchRequest request) {
        try {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var root = document.createElementNS(LR_NS, "sfLR:RegFactuSistemaFacturacion");
            root.setAttribute("xmlns:sf", SF_NS);
            document.appendChild(root);
            root.appendChild(header(document, request));
            request.records().forEach(record -> root.appendChild(invoiceRecord(document, request, record)));
            return xml(document);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el XML VERI*FACTU", exception);
        }
    }

    /**
     * Builds an AEAT requirement batch around already signed RegistroAlta/
     * RegistroAnulacion documents. The signed nodes are imported without
     * reparsing or regenerating their content, so the XAdES bytes remain the
     * exact frozen evidence produced at registration time.
     */
    public String signedRequirementBatchXml(
            String issuerName,
            String issuerTaxId,
            List<String> signedRecords,
            FiscalRequirementContext requirement) {
        var name = required(issuerName, "nombre del emisor");
        var taxId = required(issuerTaxId, "NIF del emisor");
        if (signedRecords == null || signedRecords.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un registro fiscal");
        }
        Objects.requireNonNull(requirement, "requirement");
        try {
            var document = newDocumentBuilder().newDocument();
            var root = document.createElementNS(LR_NS, "sfLR:RegFactuSistemaFacturacion");
            root.setAttribute("xmlns:sf", SF_NS);
            document.appendChild(root);
            root.appendChild(requirementHeader(document, name, taxId, requirement));
            for (String signedXml : signedRecords) {
                var signedDocument = newDocumentBuilder().parse(
                        new ByteArrayInputStream(required(signedXml, "XML firmado")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var signedRoot = signedDocument.getDocumentElement();
                if (signedRoot == null || !SF_NS.equals(signedRoot.getNamespaceURI())
                        || !("RegistroAlta".equals(signedRoot.getLocalName())
                                || "RegistroAnulacion".equals(signedRoot.getLocalName()))) {
                    throw new IllegalArgumentException(
                            "El XML firmado no contiene RegistroAlta ni RegistroAnulacion");
                }
                var container = element(document, LR_NS, "sfLR:RegistroFactura");
                container.appendChild(document.importNode(signedRoot, true));
                root.appendChild(container);
            }
            return xml(document);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo generar el XML de requerimiento fiscal", exception);
        }
    }

    /**
     * Wraps immutable registration XML in the AEAT batch envelope. Neither the
     * invoice data nor the SIF identity is rebuilt from current configuration.
     */
    public String frozenBatchXml(
            String issuerName, String issuerTaxId, List<String> frozenRecords) {
        var name = required(issuerName, "nombre del emisor congelado");
        var taxId = required(issuerTaxId, "NIF del emisor congelado");
        if (frozenRecords == null || frozenRecords.isEmpty()) {
            throw new IllegalArgumentException("Debe existir al menos un registro fiscal congelado");
        }
        try {
            var document = newDocumentBuilder().newDocument();
            var root = document.createElementNS(LR_NS, "sfLR:RegFactuSistemaFacturacion");
            root.setAttribute("xmlns:sf", SF_NS);
            document.appendChild(root);
            var header = element(document, LR_NS, "sfLR:Cabecera");
            var obligated = child(document, header, "ObligadoEmision");
            text(document, obligated, "NombreRazon", name);
            text(document, obligated, "NIF", taxId);
            root.appendChild(header);
            for (String frozenXml : frozenRecords) {
                var frozenDocument = newDocumentBuilder().parse(
                        new ByteArrayInputStream(required(frozenXml, "XML fiscal congelado")
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var frozenRoot = frozenDocument.getDocumentElement();
                if (frozenRoot == null || !SF_NS.equals(frozenRoot.getNamespaceURI())
                        || !("RegistroAlta".equals(frozenRoot.getLocalName())
                                || "RegistroAnulacion".equals(frozenRoot.getLocalName()))) {
                    throw new IllegalArgumentException(
                            "El artefacto no contiene RegistroAlta ni RegistroAnulacion");
                }
                var container = element(document, LR_NS, "sfLR:RegistroFactura");
                container.appendChild(document.importNode(frozenRoot, true));
                root.appendChild(container);
            }
            return xml(document);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "No se pudo envolver el XML fiscal congelado", exception);
        }
    }

    /**
     * Serialises exactly one RegistroAlta or RegistroAnulacion as the signing
     * input required by the AEAT signature specification. The batch wrapper is
     * deliberately not part of this document.
     */
    public String recordXml(VerifactuXmlBatchRequest request, FiscalRecord record) {
        try {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var node = record.getOperation() == FiscalRecordOperation.ALTA
                    ? alta(document, request, record)
                    : cancellation(document, request, record);
            document.appendChild(node);
            return xml(document);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el XML del registro fiscal", exception);
        }
    }
    // Generates the base official XML used by the future AEAT SOAP client.

    /**
     * Recovers the obligated identity only from an immutable legacy
     * {@code RegistroAlta}. It deliberately refuses cancellations and
     * ambiguous/malformed XML: those records must inherit the identity from
     * their explicit {@link FiscalRelationType#ANULA} relation.
     */
    public FrozenIssuerIdentity frozenAltaIssuerIdentity(String frozenXml) {
        try {
            var document = newDocumentBuilder().parse(new ByteArrayInputStream(
                    required(frozenXml, "XML fiscal congelado")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var root = document.getDocumentElement();
            if (root == null || !SF_NS.equals(root.getNamespaceURI())
                    || !"RegistroAlta".equals(root.getLocalName())) {
                throw new IllegalArgumentException(
                        "La identidad legacy solo puede recuperarse de RegistroAlta");
            }
            return new FrozenIssuerIdentity(
                    uniqueDirectText(root, "NombreRazonEmisor"),
                    uniqueDirectText(uniqueDirectElement(root, "IDFactura"),
                            "IDEmisorFactura"));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "No se pudo recuperar la identidad del XML fiscal congelado", exception);
        }
    }

    private static String uniqueDirectText(Element parent, String localName) {
        return required(uniqueDirectElement(parent, localName).getTextContent(), localName);
    }

    private static Element uniqueDirectElement(Element parent, String localName) {
        Element match = null;
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)
                    || !SF_NS.equals(element.getNamespaceURI())
                    || !localName.equals(element.getLocalName())) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException(
                        "El XML fiscal contiene una identidad ambigua: " + localName);
            }
            match = element;
        }
        if (match == null) {
            throw new IllegalArgumentException(
                    "El XML fiscal no contiene una identidad inequivoca: " + localName);
        }
        return match;
    }

    public record FrozenIssuerIdentity(String issuerName, String issuerTaxId) {
        public FrozenIssuerIdentity {
            issuerName = required(issuerName, "nombre del obligado");
            issuerTaxId = required(issuerTaxId, "NIF del obligado");
        }
    }

    private static Element header(Document document, VerifactuXmlBatchRequest request) {
        var header = element(document, LR_NS, "sfLR:Cabecera");
        var obligated = child(document, header, "ObligadoEmision");
        text(document, obligated, "NombreRazon", request.issuerName());
        text(document, obligated, "NIF", request.issuerTaxId());
        if (request.requirement() != null) {
            appendRequirement(document, header, request.requirement());
        }
        return header;
    }

    private static Element requirementHeader(
            Document document, String issuerName, String issuerTaxId,
            FiscalRequirementContext requirement) {
        var header = element(document, LR_NS, "sfLR:Cabecera");
        var obligated = child(document, header, "ObligadoEmision");
        text(document, obligated, "NombreRazon", issuerName);
        text(document, obligated, "NIF", issuerTaxId);
        appendRequirement(document, header, requirement);
        return header;
    }

    private static void appendRequirement(
            Document document, Element header, FiscalRequirementContext requirement) {
        var remission = child(document, header, "RemisionRequerimiento");
        text(document, remission, "RefRequerimiento", requirement.reference());
        text(document, remission, "FinRequerimiento", requirement.finishedValue());
    }

    private static Element invoiceRecord(
            Document document, VerifactuXmlBatchRequest request, FiscalRecord record) {
        var container = element(document, LR_NS, "sfLR:RegistroFactura");
        container.appendChild(record.getOperation() == FiscalRecordOperation.ALTA
                ? alta(document, request, record)
                : cancellation(document, request, record));
        return container;
    }

    private static Element alta(
            Document document, VerifactuXmlBatchRequest request, FiscalRecord record) {
        var alta = element(document, SF_NS, "sf:RegistroAlta");
        text(document, alta, "IDVersion", "1.0");
        invoiceId(document, child(document, alta, "IDFactura"), record, false);
        text(document, alta, "NombreRazonEmisor", request.issuerName());
        correctionIndicators(document, alta, record);
        text(document, alta, "TipoFactura", record.getDocumentType().name());
        if (isRectification(record.getDocumentType())) {
            text(document, alta, "TipoRectificativa", rectificationType(record));
        }
        rectifiedInvoices(document, alta, record);
        substitutedInvoices(document, alta, record);
        text(document, alta, "DescripcionOperacion",
                snapshotText(record, "descripcionOperacion", "Venta"));
        recipient(document, alta, record);
        breakdown(document, alta, record);
        text(document, alta, "CuotaTotal", amount(record.getSnapshot().get("impuestoTotal")));
        text(document, alta, "ImporteTotal", amount(record.getTotalAmount()));
        chain(document, alta, record);
        system(document, alta, request.systemInfo());
        text(document, alta, "FechaHoraHusoGenRegistro", generatedAt(record));
        text(document, alta, "TipoHuella", "01");
        text(document, alta, "Huella", record.getHash());
        return alta;
    }

    private static Element cancellation(
            Document document, VerifactuXmlBatchRequest request, FiscalRecord record) {
        var cancellation = element(document, SF_NS, "sf:RegistroAnulacion");
        text(document, cancellation, "IDVersion", "1.0");
        invoiceId(document, child(document, cancellation, "IDFactura"), record, true);
        chain(document, cancellation, record);
        system(document, cancellation, request.systemInfo());
        text(document, cancellation, "FechaHoraHusoGenRegistro", generatedAt(record));
        text(document, cancellation, "TipoHuella", "01");
        text(document, cancellation, "Huella", record.getHash());
        return cancellation;
    }

    private static void invoiceId(
            Document document, Element parent, FiscalRecord record, boolean cancellation) {
        text(document, parent, cancellation ? "IDEmisorFacturaAnulada" : "IDEmisorFactura",
                record.getIssuerTaxId());
        text(document, parent, cancellation ? "NumSerieFacturaAnulada" : "NumSerieFactura",
                record.getNumber());
        text(document, parent, cancellation
                ? "FechaExpedicionFacturaAnulada"
                : "FechaExpedicionFactura", DATE.format(record.getIssueDate()));
    }

    private static void breakdown(Document document, Element parent, FiscalRecord record) {
        var container = child(document, parent, "Desglose");
        fiscalBreakdowns(record).forEach(value -> {
            var detail = child(document, container, "DetalleDesglose");
            text(document, detail, "Impuesto", taxCode(value.regime()));
            boolean retailIgicExemption = isRetailIgicExemption(record, value);
            if (retailIgicExemption) {
                text(document, detail, "ClaveRegimen", "17");
                text(document, detail, "OperacionExenta", "E1");
            } else {
                text(document, detail, "CalificacionOperacion", "S1");
                if (value.rate() != null) {
                    text(document, detail, "TipoImpositivo", amount(value.rate()));
                }
            }
            text(document, detail, "BaseImponibleOimporteNoSujeto", amount(value.base()));
            if (!retailIgicExemption) {
                text(document, detail, "CuotaRepercutida", amount(value.tax()));
            }
        });
    }

    private static boolean isRetailIgicExemption(
            FiscalRecord record, FiscalBreakdown value) {
        return "IGIC_MINORISTA".equals(record.getSnapshot().get("perfilFiscalFactura"))
                && "IGIC".equalsIgnoreCase(value.regime())
                && value.rate() != null
                && value.rate().signum() == 0
                && value.tax().signum() == 0;
    }

    private static void correctionIndicators(
            Document document, Element parent, FiscalRecord record) {
        var correction = optionalSnapshotText(record, "subsanacion");
        if (correction == null) {
            return;
        }
        text(document, parent, "Subsanacion", correction);
        text(document, parent, "RechazoPrevio",
                snapshotText(record, "rechazoPrevio", "N"));
    }

    @SuppressWarnings("unchecked")
    private static void recipient(Document document, Element parent, FiscalRecord record) {
        if (!(record.getSnapshot().get("cliente") instanceof Map<?, ?> value)) {
            return;
        }
        var customer = (Map<String, Object>) value;
        var recipients = child(document, parent, "Destinatarios");
        var recipient = child(document, recipients, "IDDestinatario");
        text(document, recipient, "NombreRazon", string(customer, "nombreFiscal"));
        text(document, recipient, "NIF", string(customer, "numeroDocumento"));
    }

    private static void chain(Document document, Element parent, FiscalRecord record) {
        var chain = child(document, parent, "Encadenamiento");
        if (record.getPreviousHash() == null || record.getPreviousHash().isBlank()) {
            text(document, chain, "PrimerRegistro", "S");
            return;
        }
        var previous = child(document, chain, "RegistroAnterior");
        var identity = previousIdentity(record);
        text(document, previous, "IDEmisorFactura", string(identity, "nifEmisor"));
        text(document, previous, "NumSerieFactura", string(identity, "numero"));
        text(document, previous, "FechaExpedicionFactura",
                DATE.format(java.time.LocalDate.parse(string(identity, "fecha"))));
        text(document, previous, "Huella", string(identity, "huella"));
    }

    @SuppressWarnings("unchecked")
    private static void substitutedInvoices(
            Document document, Element parent, FiscalRecord record) {
        var values = (List<Map<String, Object>>) record.getSnapshot()
                .getOrDefault("facturasSustituidas", List.of());
        if (values.isEmpty()) {
            return;
        }
        var container = child(document, parent, "FacturasSustituidas");
        values.forEach(value -> {
            var invoice = child(document, container, "IDFacturaSustituida");
            text(document, invoice, "IDEmisorFactura", string(value, "nifEmisor"));
            text(document, invoice, "NumSerieFactura", string(value, "numero"));
            text(document, invoice, "FechaExpedicionFactura",
                    DATE.format(java.time.LocalDate.parse(string(value, "fecha"))));
        });
    }

    @SuppressWarnings("unchecked")
    private static void rectifiedInvoices(
            Document document, Element parent, FiscalRecord record) {
        var values = (List<Map<String, Object>>) record.getSnapshot()
                .getOrDefault("facturasRectificadas", List.of());
        if (values.isEmpty()) {
            return;
        }
        var container = child(document, parent, "FacturasRectificadas");
        values.forEach(value -> {
            var invoice = child(document, container, "IDFacturaRectificada");
            text(document, invoice, "IDEmisorFactura", string(value, "nifEmisor"));
            text(document, invoice, "NumSerieFactura", string(value, "numero"));
            text(document, invoice, "FechaExpedicionFactura",
                    DATE.format(java.time.LocalDate.parse(string(value, "fecha"))));
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> previousIdentity(FiscalRecord record) {
        var value = record.getSnapshot().get("registroAnterior");
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("registroAnterior es obligatorio para encadenar");
    }

    private static String string(Map<String, Object> values, String key) {
        var value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("registroAnterior." + key + " es obligatorio");
        }
        return value.toString();
    }

    private static String snapshotText(
            FiscalRecord record, String key, String fallback) {
        var value = optionalSnapshotText(record, key);
        return value == null ? fallback : value;
    }

    private static String optionalSnapshotText(FiscalRecord record, String key) {
        var value = record.getSnapshot().get(key);
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private static void system(Document document, Element parent, VerifactuSystemInfo info) {
        var system = child(document, parent, "SistemaInformatico");
        text(document, system, "NombreRazon", info.manufacturerName());
        text(document, system, "NIF", info.manufacturerTaxId());
        text(document, system, "NombreSistemaInformatico", info.systemName());
        text(document, system, "IdSistemaInformatico", info.systemId());
        text(document, system, "Version", info.version());
        text(document, system, "NumeroInstalacion", info.installationNumber());
        text(document, system, "TipoUsoPosibleSoloVerifactu", yesNo(info.onlyVerifactu()));
        text(document, system, "TipoUsoPosibleMultiOT", yesNo(info.multiTaxpayer()));
        text(document, system, "IndicadorMultiplesOT", yesNo(info.multipleTaxpayersActive()));
    }

    private static Element child(Document document, Element parent, String name) {
        var child = element(document, SF_NS, "sf:" + name);
        parent.appendChild(child);
        return child;
    }

    private static void text(Document document, Element parent, String name, String value) {
        var child = child(document, parent, name);
        child.setTextContent(value);
    }

    private static Element element(Document document, String namespace, String name) {
        return document.createElementNS(namespace, name);
    }

    private static String generatedAt(FiscalRecord record) {
        return formatXmlDateTime(record.getGeneratedAt()
                .atZone(ZoneId.of(record.getTimezone()))
                .toOffsetDateTime());
    }

    static String formatXmlDateTime(OffsetDateTime value) {
        return XML_DATE_TIME.format(Objects.requireNonNull(value, "value"));
    }

    private static String amount(Object value) {
        return decimal(Map.of("importe", value), "importe")
                .setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> lines(FiscalRecord record) {
        var lines = (List<Map<String, Object>>) record.getSnapshot().getOrDefault("lineas", List.of());
        return lines;
    }

    private static List<FiscalBreakdown> fiscalBreakdowns(FiscalRecord record) {
        var lines = lines(record);
        if (lines.isEmpty()) {
            return List.of(new FiscalBreakdown(
                    "IVA", null,
                    decimal(record.getSnapshot(), "baseTotal"),
                    decimal(record.getSnapshot(), "impuestoTotal")));
        }
        var grouped = new LinkedHashMap<FiscalKey, FiscalBreakdown>();
        for (var line : lines) {
            var regime = String.valueOf(line.getOrDefault("regimenImpuesto", "IVA"));
            var rate = optionalDecimal(line.get("porcentajeImpuesto"));
            var base = optionalDecimal(line.get("base"));
            if (base == null) {
                base = singleLineTotal(lines, record, "baseTotal");
            }
            var tax = optionalDecimal(line.get("impuesto"));
            if (tax == null) {
                tax = singleLineTotal(lines, record, "impuestoTotal");
            }
            var key = new FiscalKey(regime.toUpperCase(Locale.ROOT), rate);
            grouped.merge(key, new FiscalBreakdown(key.regime(), rate, base, tax),
                    FiscalBreakdown::add);
        }
        return List.copyOf(grouped.values());
    }
    // Agrupa lineas equivalentes para no declarar totales bajo un tipo fiscal incorrecto.

    private static BigDecimal singleLineTotal(
            List<Map<String, Object>> lines, FiscalRecord record, String key) {
        if (lines.size() != 1) {
            throw new IllegalArgumentException(key + " por linea es obligatorio");
        }
        return decimal(record.getSnapshot(), key);
    }

    private static BigDecimal decimal(Map<String, Object> values, String key) {
        var value = optionalDecimal(values.get(key));
        if (value == null) {
            throw new IllegalArgumentException(key + " es obligatorio");
        }
        return value;
    }

    private static String taxCode(String regime) {
        var normalized = regime
                .toUpperCase(Locale.ROOT);
        return "IGIC".equals(normalized) ? "03" : "01";
    }

    private record FiscalKey(String regime, BigDecimal rate) {
    }

    private record FiscalBreakdown(
            String regime, BigDecimal rate, BigDecimal base, BigDecimal tax) {

        FiscalBreakdown add(FiscalBreakdown other) {
            return new FiscalBreakdown(regime, rate, base.add(other.base), tax.add(other.tax));
        }
    }

    private static boolean isRectification(FiscalDocumentType type) {
        return type == FiscalDocumentType.R1
                || type == FiscalDocumentType.R2
                || type == FiscalDocumentType.R3
                || type == FiscalDocumentType.R4
                || type == FiscalDocumentType.R5;
    }

    private static String rectificationType(FiscalRecord record) {
        var value = String.valueOf(record.getSnapshot().getOrDefault("tipoRectificativa", "S"))
                .toUpperCase(Locale.ROOT);
        if (!value.equals("S") && !value.equals("I")) {
            throw new IllegalArgumentException("tipoRectificativa debe ser S o I");
        }
        return value;
    }

    private static String yesNo(boolean value) {
        return value ? "S" : "N";
    }

    /** Normalizes equivalent numeric representations materialized from JSONB. */
    private static BigDecimal optionalDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                // Fall through to the same clear fiscal-field error below.
            }
        }
        throw new IllegalArgumentException("importe fiscal no numerico");
    }

    private static String xml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private static javax.xml.parsers.DocumentBuilder newDocumentBuilder() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    private static String required(String value, String field) {
        var normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " es obligatorio");
        }
        return normalized;
    }
}
