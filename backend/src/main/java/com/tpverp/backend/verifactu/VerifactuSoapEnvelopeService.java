package com.tpverp.backend.verifactu;

import java.io.StringReader;
import java.io.StringWriter;
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
public class VerifactuSoapEnvelopeService {

    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    public String wrap(String fiscalXml) {
        try {
            var payload = parse(fiscalXml);
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            var envelope = document.createElementNS(SOAP_NS, "soapenv:Envelope");
            envelope.setAttribute("xmlns:soapenv", SOAP_NS);
            var body = document.createElementNS(SOAP_NS, "soapenv:Body");
            body.appendChild(document.importNode(payload.getDocumentElement(), true));
            envelope.appendChild(body);
            document.appendChild(envelope);
            return xml(document);
        } catch (Exception exception) {
            throw new IllegalArgumentException("XML VERI*FACTU no valido", exception);
        }
    }
    // Inserta el lote fiscal como cuerpo SOAP document/literal para el servicio AEAT.

    private static Document parse(String xml) throws Exception {
        if (xml == null || xml.isBlank()) {
            throw new IllegalArgumentException("XML VERI*FACTU obligatorio");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new SilentXmlErrorHandler());
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private static String xml(Document document) throws Exception {
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
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
