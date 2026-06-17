package com.tpverp.backend.verifactu;

import java.io.InputStream;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

@Component
public class VerifactuOfficialXsdValidator {

    private static final String XSD_ROOT = "verifactu/xsd/";
    private final Schema schema;

    public VerifactuOfficialXsdValidator() {
        this.schema = schema();
    }

    public void validate(String xml) {
        try {
            schema.newValidator().validate(new StreamSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IllegalArgumentException("XML VERI*FACTU no cumple XSD oficial", exception);
        }
    }
    // Valida el lote contra los XSD oficiales empaquetados con la aplicacion.

    private static Schema schema() {
        try {
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver(new ClasspathXsdResolver());
            return factory.newSchema(source("SuministroLR.xsd"));
        } catch (SAXException exception) {
            throw new IllegalStateException("No se pudo cargar el XSD oficial VERI*FACTU", exception);
        }
    }

    private static Source source(String name) {
        var url = VerifactuOfficialXsdValidator.class.getClassLoader()
                .getResource(XSD_ROOT + name);
        if (url == null) {
            throw new IllegalStateException("XSD no encontrado: " + name);
        }
        var source = new StreamSource(url.toExternalForm());
        source.setSystemId(url.toExternalForm());
        return source;
    }

    private static class ClasspathXsdResolver implements LSResourceResolver {
        @Override
        public LSInput resolveResource(
                String type,
                String namespaceUri,
                String publicId,
                String systemId,
                String baseUri) {
            var name = fileName(systemId);
            var stream = VerifactuOfficialXsdValidator.class.getClassLoader()
                    .getResourceAsStream(XSD_ROOT + name);
            return stream == null ? null : new ClasspathLsInput(publicId, systemId, stream);
        }

        private static String fileName(String systemId) {
            if (systemId == null || systemId.isBlank()) {
                return "";
            }
            var normalized = systemId.replace('\\', '/');
            return normalized.substring(normalized.lastIndexOf('/') + 1);
        }
    }

    private record ClasspathLsInput(
            String publicId,
            String systemId,
            InputStream byteStream) implements LSInput {

        @Override public String getStringData() {
            return null;
        }

        @Override public void setStringData(String stringData) {
        }

        @Override public String getSystemId() {
            return systemId;
        }

        @Override public void setSystemId(String systemId) {
        }

        @Override public String getPublicId() {
            return publicId;
        }

        @Override public void setPublicId(String publicId) {
        }

        @Override public String getBaseURI() {
            return null;
        }

        @Override public void setBaseURI(String baseURI) {
        }

        @Override public InputStream getByteStream() {
            return byteStream;
        }

        @Override public void setByteStream(InputStream byteStream) {
        }

        @Override public java.io.Reader getCharacterStream() {
            return null;
        }

        @Override public void setCharacterStream(java.io.Reader characterStream) {
        }

        @Override public String getEncoding() {
            return "UTF-8";
        }

        @Override public void setEncoding(String encoding) {
        }

        @Override public boolean getCertifiedText() {
            return false;
        }

        @Override public void setCertifiedText(boolean certifiedText) {
        }
    }
}
