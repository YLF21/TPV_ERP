package com.tpverp.backend.verifactu;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

@Component
public class VerifactuResponseParser {

    static final String RESPONSE_NAMESPACE =
            "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/RespuestaSuministro.xsd";
    static final String SUPPLY_NAMESPACE =
            "https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd";
    private static final String SOAP11_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12_NAMESPACE = "http://www.w3.org/2003/05/soap-envelope";
    private static final int MAX_WAIT_SECONDS = 9999;
    private final VerifactuOfficialXsdValidator officialValidator;

    public VerifactuResponseParser() {
        this(new VerifactuOfficialXsdValidator());
    }

    VerifactuResponseParser(VerifactuOfficialXsdValidator officialValidator) {
        this.officialValidator = officialValidator;
    }

    public VerifactuSubmissionResult parse(VerifactuTransportResponse response) {
        var body = response.body();
        if (response.httpStatus() < 200 || response.httpStatus() > 299) {
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.ENVIADO, "HTTP_" + response.httpStatus(),
                    "Respuesta HTTP no aceptada por AEAT", body);
        }
        try {
            var document = document(body);
            var root = legacyResponseRoot(document);
            if (isResponse(root)) officialValidator.validateResponse(serialize(root));
            var status = directTextAny(root, "EstadoEnvio");
            if ("Correcto".equalsIgnoreCase(status)) {
                return new VerifactuSubmissionResult(
                        FiscalSubmissionStatus.ACEPTADO, null, null, body);
            }
            if ("ParcialmenteCorrecto".equalsIgnoreCase(status)) {
                return incident(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES, root, body);
            }
            return incident(FiscalSubmissionStatus.RECHAZADO, root, body);
        } catch (Exception exception) {
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.DEFECTUOSO,
                    "INVALID_AEAT_RESPONSE",
                    "Respuesta AEAT no interpretable",
                    body);
        }
    }

    /**
     * Strict parser for a batch response. Every requested record must have one
     * and only one correlated RespuestaLinea. This overload is deliberately
     * separate from the legacy single-result parser because a global AEAT
     * status is not sufficient to classify individual records.
     */
    public VerifactuBatchResponse parseBatch(
            VerifactuTransportResponse response, List<FiscalRecord> requested) {
        if (response == null || requested == null || requested.isEmpty()
                || requested.size() > 1000) {
            return invalid(response == null ? null : response.body(), "Lote solicitado invalido");
        }
        if (response.httpStatus() < 200 || response.httpStatus() > 299) {
            return new VerifactuBatchResponse(
                    FiscalSubmissionStatus.ENVIADO, null, Map.of(),
                    "HTTP_" + response.httpStatus(),
                    "Respuesta HTTP no aceptada por AEAT", response.body(), true);
        }
        try {
            var document = document(response.body());
            var root = responseRoot(document);
            officialValidator.validateResponse(serialize(root));
            var global = uniqueDirectText(root, "EstadoEnvio", RESPONSE_NAMESPACE);
            var wait = parseWait(uniqueDirectText(root, "TiempoEsperaEnvio", RESPONSE_NAMESPACE));
            var globalStatus = globalStatus(global);
            var lines = directChildren(root, "RespuestaLinea", RESPONSE_NAMESPACE);
            if (lines.size() != requested.size()) {
                throw invalid("Numero de RespuestaLinea incoherente");
            }
            var byId = new HashMap<UUID, VerifactuBatchResponse.Line>();
            var used = new HashSet<UUID>();
            for (var line : lines) {
                var record = correlate(line, requested);
                if (!used.add(record.getId())) {
                    throw invalid("RespuestaLinea duplicada");
                }
                var rawStatus = lineStatus(uniqueDirectText(line, "EstadoRegistro", RESPONSE_NAMESPACE));
                var duplicate = firstDirectElement(line, "RegistroDuplicado", RESPONSE_NAMESPACE);
                var duplicateStatus = duplicate == null ? null
                        : optionalDirectText(duplicate, "EstadoRegistroDuplicado", SUPPLY_NAMESPACE);
                var status = resolveDuplicateStatus(rawStatus, duplicateStatus);
                var code = optionalDirectText(line, "CodigoErrorRegistro", RESPONSE_NAMESPACE);
                if (code == null && duplicate != null) {
                    code = optionalDirectText(duplicate, "CodigoErrorRegistro", SUPPLY_NAMESPACE);
                }
                validateErrorCode(code);
                var error = errorDescriptionLine(line, duplicate);
                if (status == FiscalSubmissionStatus.ACEPTADO_CON_ERRORES
                        || status == FiscalSubmissionStatus.RECHAZADO) {
                    if (code == null || error == null) {
                        throw invalid("RespuestaLinea de error incompleta");
                    }
                }
                byId.put(record.getId(), new VerifactuBatchResponse.Line(
                        record.getId(), status, code, error, rawStatus, duplicateStatus));
            }
            var result = new VerifactuBatchResponse(
                    globalStatus, wait, byId,
                    optionalDirectText(root, "CodigoErrorRegistro", RESPONSE_NAMESPACE),
                    optionalDirectText(root, "DescripcionErrorRegistro", RESPONSE_NAMESPACE), response.body(), false);
            if (!result.validFor(requested)) {
                throw invalid("Estado global y estados de linea incoherentes");
            }
            return result;
        } catch (Exception exception) {
            return invalid(response.body(), "Respuesta AEAT invalida: " + exception.getMessage());
        }
    }

    private static VerifactuBatchResponse invalid(String payload, String message) {
        return new VerifactuBatchResponse(
                FiscalSubmissionStatus.DEFECTUOSO, null, Map.of(),
                "INVALID_AEAT_RESPONSE", message, payload, false);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static FiscalSubmissionStatus globalStatus(String value) {
        if ("Correcto".equals(value)) return FiscalSubmissionStatus.ACEPTADO;
        if ("ParcialmenteCorrecto".equals(value)) {
            return FiscalSubmissionStatus.ACEPTADO_CON_ERRORES;
        }
        if ("Incorrecto".equals(value)) return FiscalSubmissionStatus.RECHAZADO;
        throw invalid("EstadoEnvio desconocido o ausente");
    }

    private static FiscalSubmissionStatus lineStatus(String value) {
        if ("Correcto".equals(value)) return FiscalSubmissionStatus.ACEPTADO;
        if ("AceptadoConErrores".equals(value)) {
            return FiscalSubmissionStatus.ACEPTADO_CON_ERRORES;
        }
        if ("Incorrecto".equals(value)) return FiscalSubmissionStatus.RECHAZADO;
        throw invalid("EstadoRegistro desconocido o ausente");
    }

    /**
     * AEAT reports a duplicate as an Incorrecto line, while the nested state
     * is the authoritative state of the already stored record. Anulada is
     * deliberately kept rejected: it is not an acceptance of this submission.
     */
    private static FiscalSubmissionStatus resolveDuplicateStatus(
            FiscalSubmissionStatus rawStatus, String duplicateStatus) {
        if (duplicateStatus == null) return rawStatus;
        if (rawStatus != FiscalSubmissionStatus.RECHAZADO) {
            throw invalid("RegistroDuplicado solo puede acompañar a una linea Incorrecto");
        }
        return switch (duplicateStatus) {
            case "Correcta" -> FiscalSubmissionStatus.ACEPTADO;
            case "AceptadaConErrores" -> FiscalSubmissionStatus.ACEPTADO_CON_ERRORES;
            case "Anulada" -> FiscalSubmissionStatus.RECHAZADO;
            default -> throw invalid("EstadoRegistroDuplicado desconocido");
        };
    }

    private static int parseWait(String value) {
        if (value == null || !value.matches("[0-9]{1,4}")) {
            throw invalid("TiempoEsperaEnvio ausente o no numerico");
        }
        int seconds;
        try {
            seconds = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid("TiempoEsperaEnvio invalido");
        }
        if (seconds < 0 || seconds > MAX_WAIT_SECONDS) {
            throw invalid("TiempoEsperaEnvio fuera de rango");
        }
        return seconds;
    }

    private static FiscalRecord correlate(
            org.w3c.dom.Element line, List<FiscalRecord> requested) {
        // A duplicate remains RECHAZADO. It is only correlated when AEAT
        // supplies the exact request identity (UUID or invoice identity plus
        // operation); guessing from position would risk accepting another
        // taxpayer's response.
        var explicitId = optionalDirectText(line, "IdRegistro", RESPONSE_NAMESPACE);
        if (explicitId == null) explicitId = optionalDirectText(line, "IDRegistro", RESPONSE_NAMESPACE);
        if (explicitId != null) {
            try {
                var id = UUID.fromString(explicitId);
                return requested.stream().filter(record -> record.getId().equals(id)).findFirst()
                        .orElseThrow(() -> invalid("RespuestaLinea desconocida"));
            } catch (IllegalArgumentException exception) {
                throw invalid("IdRegistro invalido o desconocido");
            }
        }
        var idFactura = firstDirectElement(line, "IDFactura", RESPONSE_NAMESPACE);
        if (idFactura == null) throw invalid("RespuestaLinea sin identidad");
        var issuer = optionalDirectText(idFactura, "IDEmisorFactura", SUPPLY_NAMESPACE);
        if (issuer == null) issuer = optionalDirectText(idFactura, "IDEmisorFacturaAnulada", SUPPLY_NAMESPACE);
        var number = optionalDirectText(idFactura, "NumSerieFactura", SUPPLY_NAMESPACE);
        if (number == null) number = optionalDirectText(idFactura, "NumSerieFacturaAnulada", SUPPLY_NAMESPACE);
        var date = optionalDirectText(idFactura, "FechaExpedicionFactura", SUPPLY_NAMESPACE);
        if (date == null) date = optionalDirectText(idFactura, "FechaExpedicionFacturaAnulada", SUPPLY_NAMESPACE);
        var operationElement = firstDirectElement(line, "Operacion", RESPONSE_NAMESPACE);
        var operation = operationElement == null ? null
                : normalizeOperation(optionalDirectText(operationElement, "TipoOperacion", SUPPLY_NAMESPACE));
        if (issuer == null || number == null || date == null || operation == null) {
            throw invalid("RespuestaLinea sin identidad completa");
        }
        final var issuerValue = issuer;
        final var numberValue = number;
        final var dateValue = date;
        final var operationValue = operation;
        var candidates = requested.stream().filter(record ->
                issuerValue.equals(record.getIssuerTaxId())
                        && numberValue.equals(record.getNumber())
                        && dateMatches(dateValue, record.getIssueDate())
                        && operationValue == record.getOperation()).toList();
        if (candidates.size() != 1) throw invalid("RespuestaLinea desconocida o ambigua");
        return candidates.getFirst();
    }

    private static boolean dateMatches(String value, LocalDate expected) {
        try {
            return expected.equals(LocalDate.parse(value, DateTimeFormatter.ofPattern("dd-MM-yyyy")))
                    || expected.equals(LocalDate.parse(value));
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static FiscalRecordOperation normalizeOperation(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase(Locale.ROOT).replace("ó", "o")) {
            case "alta", "registroalta", "a" -> FiscalRecordOperation.ALTA;
            case "anulacion", "registroanulacion", "anulacionfactura", "anulacionregistro" ->
                    FiscalRecordOperation.ANULACION;
            default -> null;
        };
    }

    private static List<org.w3c.dom.Element> directChildren(
            org.w3c.dom.Element parent, String localName, String namespace) {
        var result = new ArrayList<org.w3c.dom.Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element
                    && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                result.add(element);
            }
        }
        return result;
    }

    private static org.w3c.dom.Element firstDirectElement(
            org.w3c.dom.Element parent, String localName, String namespace) {
        var all = directChildren(parent, localName, namespace);
        return all.isEmpty() ? null : all.getFirst();
    }

    private static String uniqueDirectText(
            org.w3c.dom.Element parent, String tag, String namespace) {
        var values = directChildren(parent, tag, namespace).stream()
                .map(element -> element.getTextContent().trim())
                .filter(value -> !value.isBlank()).toList();
        if (values.size() != 1) throw invalid(tag + " ausente o duplicado");
        return values.getFirst();
    }

    private static String directTextAny(org.w3c.dom.Element parent, String tag) {
        var values = new ArrayList<String>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element
                    && tag.equals(element.getLocalName())) {
                var value = element.getTextContent().trim();
                if (!value.isBlank()) values.add(value);
            }
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static String optionalDirectText(
            org.w3c.dom.Element parent, String tag, String namespace) {
        var values = directChildren(parent, tag, namespace).stream()
                .map(element -> element.getTextContent().trim())
                .filter(value -> !value.isBlank()).toList();
        if (values.size() > 1) throw invalid(tag + " duplicado");
        return values.isEmpty() ? null : values.getFirst();
    }

    private static void validateErrorCode(String value) {
        if (value != null && !value.matches("[+-]?\\d+")) {
            throw invalid("CodigoErrorRegistro no es un integer XSD");
        }
    }

    private static org.w3c.dom.Element responseRoot(Document document) {
        var root = document.getDocumentElement();
        if (isResponse(root)) return root;
        if ("Envelope".equals(root.getLocalName())
                && (SOAP11_NAMESPACE.equals(root.getNamespaceURI())
                        || SOAP12_NAMESPACE.equals(root.getNamespaceURI()))) {
            var body = firstDirectElement(root, "Body", root.getNamespaceURI());
            if (body == null) throw invalid("SOAP sin Body");
            var response = directChildren(body, "RespuestaRegFactuSistemaFacturacion", RESPONSE_NAMESPACE);
            if (response.size() != 1) throw invalid("SOAP sin respuesta AEAT unica");
            return response.getFirst();
        }
        throw invalid("Elemento respuesta AEAT ausente o namespace invalido");
    }

    private static org.w3c.dom.Element legacyResponseRoot(Document document) {
        try {
            return responseRoot(document);
        } catch (IllegalArgumentException ignored) {
            var root = document.getDocumentElement();
            if (root != null && "RespuestaRegFactuSistemaFacturacion".equals(root.getLocalName())) {
                return root;
            }
            throw ignored;
        }
    }

    private static boolean isResponse(org.w3c.dom.Element element) {
        return element != null
                && "RespuestaRegFactuSistemaFacturacion".equals(element.getLocalName())
                && RESPONSE_NAMESPACE.equals(element.getNamespaceURI());
    }

    private static String serialize(org.w3c.dom.Element element) throws Exception {
        var factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var output = new StringWriter();
        transformer.transform(new DOMSource(element), new StreamResult(output));
        return output.toString();
    }
    // Clasifica la respuesta funcional de AEAT sin decidir reintentos de transporte.

    private static VerifactuSubmissionResult incident(
            FiscalSubmissionStatus status, org.w3c.dom.Element root, String body) {
        return new VerifactuSubmissionResult(
                status,
                fallback(directTextAny(root, "CodigoErrorRegistro"), "AEAT_ERROR"),
                errorDescription(root),
                body);
    }

    private static String errorDescription(Document document) {
        return errorDescription(document.getDocumentElement());
    }

    private static String errorDescription(org.w3c.dom.Element root) {
        var description = fallback(
                directTextAny(root, "DescripcionErrorRegistro"),
                "Error devuelto por AEAT");
        var duplicated = firstDirectElementAny(root, "RegistroDuplicado");
        var duplicatedRequest = duplicated == null ? null
                : directTextAny(duplicated, "IdPeticionRegistroDuplicado");
        var duplicatedState = duplicated == null ? null
                : directTextAny(duplicated, "EstadoRegistroDuplicado");
        if (duplicatedRequest == null && duplicatedState == null) {
            return description;
        }
        return "%s; duplicadoIdPeticion=%s; duplicadoEstado=%s"
                .formatted(description, fallback(duplicatedRequest, "N/D"),
                        fallback(duplicatedState, "N/D"));
    }

    private static org.w3c.dom.Element firstDirectElementAny(
            org.w3c.dom.Element parent, String localName) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof org.w3c.dom.Element element
                    && localName.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static String errorDescriptionLine(org.w3c.dom.Element element,
            org.w3c.dom.Element duplicated) {
        var description = optionalDirectText(element, "DescripcionErrorRegistro", RESPONSE_NAMESPACE);
        var duplicateDescription = duplicated == null ? null
                : optionalDirectText(duplicated, "DescripcionErrorRegistro", SUPPLY_NAMESPACE);
        if (description == null && duplicated != null) {
            description = duplicateDescription;
        }
        if (description == null) description = "Error devuelto por AEAT";
        var duplicatedRequest = duplicated == null ? null
                : optionalDirectText(duplicated, "IdPeticionRegistroDuplicado", SUPPLY_NAMESPACE);
        var duplicatedState = duplicated == null ? null
                : optionalDirectText(duplicated, "EstadoRegistroDuplicado", SUPPLY_NAMESPACE);
        var duplicatedCode = duplicated == null ? null
                : optionalDirectText(duplicated, "CodigoErrorRegistro", SUPPLY_NAMESPACE);
        if (duplicatedRequest == null && duplicatedState == null) return description;
        var detail = "";
        if (duplicateDescription != null && !duplicateDescription.equals(description)) {
            detail += "; detalleDuplicado=" + duplicateDescription;
        }
        if (duplicatedCode != null) detail += "; codigoDuplicado=" + duplicatedCode;
        return "%s%s; duplicadoIdPeticion=%s; duplicadoEstado=%s".formatted(
                description, detail,
                fallback(duplicatedRequest, "N/D"),
                fallback(duplicatedState, "N/D"));
    }

    private static Document document(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new SilentXmlErrorHandler());
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static class SilentXmlErrorHandler implements ErrorHandler {
        @Override public void warning(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }

        @Override public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    }
}
