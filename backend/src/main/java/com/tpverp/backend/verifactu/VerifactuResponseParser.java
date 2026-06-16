package com.tpverp.backend.verifactu;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

@Component
public class VerifactuResponseParser {

    public VerifactuSubmissionResult parse(VerifactuTransportResponse response) {
        var body = response.body();
        if (response.httpStatus() < 200 || response.httpStatus() > 299) {
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.ENVIADO, "HTTP_" + response.httpStatus(),
                    "Respuesta HTTP no aceptada por AEAT", body);
        }
        try {
            var document = document(body);
            var status = text(document, "EstadoEnvio");
            if ("Correcto".equalsIgnoreCase(status)) {
                return new VerifactuSubmissionResult(
                        FiscalSubmissionStatus.ACEPTADO, null, null, body);
            }
            if ("ParcialmenteCorrecto".equalsIgnoreCase(status)) {
                return incident(FiscalSubmissionStatus.ACEPTADO_CON_ERRORES, document, body);
            }
            return incident(FiscalSubmissionStatus.RECHAZADO, document, body);
        } catch (Exception exception) {
            return new VerifactuSubmissionResult(
                    FiscalSubmissionStatus.DEFECTUOSO,
                    "INVALID_AEAT_RESPONSE",
                    "Respuesta AEAT no interpretable",
                    body);
        }
    }
    // Clasifica la respuesta funcional de AEAT sin decidir reintentos de transporte.

    private static VerifactuSubmissionResult incident(
            FiscalSubmissionStatus status, Document document, String body) {
        return new VerifactuSubmissionResult(
                status,
                fallback(text(document, "CodigoErrorRegistro"), "AEAT_ERROR"),
                fallback(text(document, "DescripcionErrorRegistro"), "Error devuelto por AEAT"),
                body);
    }

    private static Document document(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new SilentXmlErrorHandler());
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String text(Document document, String tag) {
        var nodes = document.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName(tag);
        }
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
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
