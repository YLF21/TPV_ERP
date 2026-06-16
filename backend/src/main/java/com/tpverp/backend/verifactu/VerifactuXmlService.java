package com.tpverp.backend.verifactu;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
    // Genera el XML oficial base que usara el futuro cliente SOAP de AEAT.

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
        text(document, alta, "TipoFactura", record.getDocumentType().name());
        text(document, alta, "DescripcionOperacion", "Venta");
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
        var detail = child(document, child(document, parent, "Desglose"), "DetalleDesglose");
        var firstLine = firstLine(record);
        text(document, detail, "Impuesto", taxCode(firstLine));
        text(document, detail, "CalificacionOperacion", "S1");
        if (firstLine.containsKey("porcentajeImpuesto")) {
            text(document, detail, "TipoImpositivo", amount(firstLine.get("porcentajeImpuesto")));
        }
        text(document, detail, "BaseImponibleOimporteNoSujeto",
                amount(record.getSnapshot().get("baseTotal")));
        text(document, detail, "CuotaRepercutida", amount(record.getSnapshot().get("impuestoTotal")));
    }

    private static void chain(Document document, Element parent, FiscalRecord record) {
        var chain = child(document, parent, "Encadenamiento");
        if (record.getPreviousHash() == null || record.getPreviousHash().isBlank()) {
            text(document, chain, "PrimerRegistro", "S");
            return;
        }
        var previous = child(document, chain, "RegistroAnterior");
        text(document, previous, "IDEmisorFactura", record.getIssuerTaxId());
        text(document, previous, "NumSerieFactura", record.getNumber());
        text(document, previous, "FechaExpedicionFactura", DATE.format(record.getIssueDate()));
        text(document, previous, "Huella", record.getPreviousHash());
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
    private static Map<String, Object> firstLine(FiscalRecord record) {
        var lines = (List<Map<String, Object>>) record.getSnapshot().getOrDefault("lineas", List.of());
        return lines.isEmpty() ? Map.of() : lines.getFirst();
    }

    private static String taxCode(Map<String, Object> line) {
        var regime = String.valueOf(line.getOrDefault("regimenImpuesto", "IVA"))
                .toUpperCase(Locale.ROOT);
        return "IGIC".equals(regime) ? "03" : "01";
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
