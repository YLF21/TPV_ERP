package com.tpverp.backend.verifactu;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** Reads the immutable AEAT timestamp from frozen event XML without network access. */
public final class FiscalFrozenTimestampReader {
    private FiscalFrozenTimestampReader() {}

    /**
     * Reads the single persisted {@code FechaHoraHusoGenEvento} value.
     *
     * @throws IllegalStateException when XML is absent, malformed, ambiguous,
     *         or attempts to use external entities/resources
     */
    public static OffsetDateTime read(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IllegalStateException("fiscal_frozen_timestamp_missing");
        }
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)));
            var values = document.getElementsByTagNameNS("*", "FechaHoraHusoGenEvento");
            if (values.getLength() != 1) {
                throw new IllegalStateException("fiscal_frozen_timestamp_missing");
            }
            return OffsetDateTime.parse(values.item(0).getTextContent().trim());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("fiscal_frozen_timestamp_invalid", exception);
        }
    }
}
