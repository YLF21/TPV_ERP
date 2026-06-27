package com.tpverp.backend.verifactu;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
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
    // Generates the base official XML used by the future AEAT SOAP client.

    private static Element header(Document document, VerifactuXmlBatchRequest request) {
        var header = element(document, LR_NS, "sfLR:Cabecera");
        var obligated = child(document, header, "ObligadoEmision");
        text(document, obligated, "NombreRazon", request.issuerName());
        text(document, obligated, "NIF", request.issuerTaxId());
        return header;
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
            text(document, detail, "CalificacionOperacion", "S1");
            if (value.rate() != null) {
                text(document, detail, "TipoImpositivo", amount(value.rate()));
            }
            text(document, detail, "BaseImponibleOimporteNoSujeto", amount(value.base()));
            text(document, detail, "CuotaRepercutida", amount(value.tax()));
        });
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
        return record.getGeneratedAt()
                .atZone(ZoneId.of(record.getTimezone()))
                .toOffsetDateTime()
                .toString();
    }

    private static String amount(Object value) {
        return ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP).toPlainString();
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
            var rate = line.get("porcentajeImpuesto") instanceof BigDecimal value ? value : null;
            var base = line.get("base") instanceof BigDecimal value
                    ? value : singleLineTotal(lines, record, "baseTotal");
            var tax = line.get("impuesto") instanceof BigDecimal value
                    ? value : singleLineTotal(lines, record, "impuestoTotal");
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
        if (values.get(key) instanceof BigDecimal value) {
            return value;
        }
        throw new IllegalArgumentException(key + " es obligatorio");
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
                || type == FiscalDocumentType.R4;
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

    private static String xml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
