package com.tpverp.backend.verifactu;

import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Serialises the standalone RegistroEvento block used for NO VERI*FACTU. */
@Component
public class FiscalEventXmlService {
    static final String EVENT_NS = "https://www2.agenciatributaria.gob.es/static_files/common/"
            + "internet/dep/aplicaciones/es/aeat/tike/cont/ws/EventosSIF.xsd";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public String unsignedXml(VerifactuSystemInfo system, String obligatedName,
            String obligatedTaxId, FiscalEventType type, String detail,
            OffsetDateTime generatedAt, String previousHash, String hash) {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(generatedAt, "generatedAt");
        Objects.requireNonNull(hash, "hash");
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var document = factory.newDocumentBuilder().newDocument();
            var root = document.createElementNS(EVENT_NS, "sum:RegistroEvento");
            document.appendChild(root);
            eventText(document, root, "IDVersion", "1.0");
            var event = child(document, root, "Evento");
            var systemNode = child(document, event, "SistemaInformatico");
            eventText(document, systemNode, "NombreRazon", system.manufacturerName());
            eventText(document, systemNode, "NIF", system.manufacturerTaxId());
            eventText(document, systemNode, "NombreSistemaInformatico", system.systemName());
            eventText(document, systemNode, "IdSistemaInformatico", system.systemId());
            eventText(document, systemNode, "Version", system.version());
            eventText(document, systemNode, "NumeroInstalacion", system.installationNumber());
            eventText(document, systemNode, "TipoUsoPosibleSoloVerifactu", yesNo(system.onlyVerifactu()));
            eventText(document, systemNode, "TipoUsoPosibleMultiOT", yesNo(system.multiTaxpayer()));
            eventText(document, systemNode, "IndicadorMultiplesOT", yesNo(system.multipleTaxpayersActive()));
            var obligated = child(document, event, "ObligadoEmision");
            eventText(document, obligated, "NombreRazon", obligatedName);
            eventText(document, obligated, "NIF", obligatedTaxId);
            eventText(document, event, "FechaHoraHusoGenEvento", generatedAt.toString());
            eventText(document, event, "TipoEvento", type.code());
            eventData(document, event, type, detail, generatedAt, obligatedTaxId, previousHash, hash);
            if (detail != null && !detail.isBlank()) {
                eventText(document, event, "OtrosDatosEvento", detail.trim());
            }
            var chain = child(document, event, "Encadenamiento");
            if (previousHash == null || previousHash.isBlank()) {
                eventText(document, chain, "PrimerEvento", "S");
            } else {
                var previous = child(document, chain, "EventoAnterior");
                eventText(document, previous, "HuellaEvento", previousHash);
            }
            eventText(document, event, "TipoHuella", "01");
            eventText(document, event, "HuellaEvento", hash);
            return xml(document);
        } catch (Exception exception) {
            throw new IllegalStateException("No se pudo generar el XML del registro de evento", exception);
        }
    }

    private static Element child(Document document, Element parent, String name) {
        var child = document.createElementNS(EVENT_NS, "sum:" + name);
        parent.appendChild(child);
        return child;
    }

    private static void eventText(Document document, Element parent, String name, String value) {
        var child = document.createElementNS(EVENT_NS, "sum:" + name);
        parent.appendChild(child);
        child.setTextContent(value == null ? "" : value);
    }

    private static void eventData(Document document, Element event, FiscalEventType type,
            String detail, OffsetDateTime generatedAt, String obligatedTaxId,
            String previousHash, String hash) {
        switch (type) {
            case BILLING_ANOMALY_SCAN_STARTED -> launchData(document, event,
                    "LanzamientoProcesoDeteccionAnomaliasRegFacturacion", "RegFacturacion", "Facturacion");
            case EVENT_ANOMALY_SCAN_STARTED -> launchData(document, event,
                    "LanzamientoProcesoDeteccionAnomaliasRegEvento", "RegEvento", "Evento");
            case BILLING_ANOMALY_DETECTED -> anomalyData(document, event,
                    "DeteccionAnomaliasRegFacturacion", detail);
            case EVENT_ANOMALY_DETECTED -> anomalyData(document, event,
                    "DeteccionAnomaliasRegEvento", detail);
            case BILLING_EXPORT -> billingExportData(document, event, generatedAt,
                    obligatedTaxId, previousHash == null ? hash : previousHash);
            case EVENT_EXPORT -> eventExportData(document, event, generatedAt,
                    previousHash == null ? hash : previousHash);
            case SUMMARY -> summaryData(document, event);
            default -> { }
        }
    }

    private static void launchData(Document document, Element event, String name,
            String suffix, String countPrefix) {
        var data = child(document, event, "DatosPropiosEvento");
        var launch = child(document, data, name);
        eventText(document, launch, "RealizadoProcesoSobreIntegridadHuellas" + suffix, "S");
        eventText(document, launch, "NumeroDeRegistros" + countPrefix + "ProcesadosSobreIntegridadHuellas", "0");
        eventText(document, launch, "RealizadoProcesoSobreIntegridadFirmas" + suffix, "S");
        eventText(document, launch, "NumeroDeRegistros" + countPrefix + "ProcesadosSobreIntegridadFirmas", "0");
        eventText(document, launch, "RealizadoProcesoSobreTrazabilidadCadena" + suffix, "S");
        eventText(document, launch, "NumeroDeRegistros" + countPrefix + "ProcesadosSobreTrazabilidadCadena", "0");
        eventText(document, launch, "RealizadoProcesoSobreTrazabilidadFechas" + suffix, "S");
        eventText(document, launch, "NumeroDeRegistros" + countPrefix + "ProcesadosSobreTrazabilidadFechas", "0");
    }

    private static void anomalyData(Document document, Element event, String name, String detail) {
        var data = child(document, event, "DatosPropiosEvento");
        var anomaly = child(document, data, name);
        eventText(document, anomaly, "TipoAnomalia", "90");
        eventText(document, anomaly, "OtrosDatosAnomalia",
                detail == null || detail.isBlank() ? "Anomalia detectada" : detail);
    }

    private static void summaryData(Document document, Element event) {
        var data = child(document, event, "DatosPropiosEvento");
        var summary = child(document, data, "ResumenEventos");
        var type = child(document, summary, "TipoEvento");
        eventText(document, type, "TipoEvento", "01");
        eventText(document, type, "NumeroDeEventos", "0");
        eventText(document, summary, "NumeroDeRegistrosFacturacionAltaGenerados", "0");
        eventText(document, summary, "SumaCuotaTotalAlta", "0");
        eventText(document, summary, "SumaImporteTotalAlta", "0");
        eventText(document, summary, "NumeroDeRegistrosFacturacionAnulacionGenerados", "0");
    }

    private static void billingExportData(Document document, Element event,
            OffsetDateTime generatedAt, String taxId, String hash) {
        var data = child(document, event, "DatosPropiosEvento");
        var export = child(document, data, "ExportacionRegFacturacionPeriodo");
        eventText(document, export, "FechaHoraHusoInicioPeriodoExport", generatedAt.toString());
        eventText(document, export, "FechaHoraHusoFinPeriodoExport", generatedAt.toString());
        billingExportRecord(document, export, "RegistroFacturacionInicialPeriodo", taxId, hash,
                generatedAt);
        billingExportRecord(document, export, "RegistroFacturacionFinalPeriodo", taxId, hash,
                generatedAt);
        eventText(document, export, "NumeroDeRegistrosFacturacionAltaExportados", "0");
        eventText(document, export, "SumaCuotaTotalAlta", "0");
        eventText(document, export, "SumaImporteTotalAlta", "0");
        eventText(document, export, "NumeroDeRegistrosFacturacionAnulacionExportados", "0");
        eventText(document, export, "RegistrosFacturacionExportadosDejanDeConservarse", "N");
    }

    private static void billingExportRecord(Document document, Element parent, String name,
            String taxId, String hash, OffsetDateTime generatedAt) {
        var record = child(document, parent, name);
        eventText(document, record, "IDEmisorFactura", taxId);
        eventText(document, record, "NumSerieFactura", "EVENT-EXPORT");
        eventText(document, record, "FechaExpedicionFactura", DATE.format(generatedAt));
        eventText(document, record, "Huella", hash);
    }

    private static void eventExportData(Document document, Element event,
            OffsetDateTime generatedAt, String hash) {
        var data = child(document, event, "DatosPropiosEvento");
        var export = child(document, data, "ExportacionRegEventoPeriodo");
        eventText(document, export, "FechaHoraHusoInicioPeriodoExport", generatedAt.toString());
        eventText(document, export, "FechaHoraHusoFinPeriodoExport", generatedAt.toString());
        eventRecord(document, export, "RegistroEventoInicialPeriodo", hash, generatedAt);
        eventRecord(document, export, "RegistroEventoFinalPeriodo", hash, generatedAt);
        eventText(document, export, "NumeroDeRegEventoExportados", "0");
        eventText(document, export, "RegEventoExportadosDejanDeConservarse", "N");
    }

    private static void eventRecord(Document document, Element parent, String name,
            String hash, OffsetDateTime generatedAt) {
        var record = child(document, parent, name);
        eventText(document, record, "TipoEvento", "90");
        eventText(document, record, "FechaHoraHusoEvento", generatedAt.toString());
        eventText(document, record, "HuellaEvento", hash);
    }

    private static String yesNo(boolean value) { return value ? "S" : "N"; }

    private static String xml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
