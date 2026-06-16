package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;

class VerifactuSoapEnvelopeServiceTest {

    @Test
    void envuelveElLoteFiscalEnBodySoap() {
        var payload = """
                <?xml version="1.0" encoding="UTF-8"?>
                <sfLR:RegFactuSistemaFacturacion xmlns:sfLR="urn:test">
                    <sfLR:Cabecera/>
                </sfLR:RegFactuSistemaFacturacion>
                """;

        var envelope = new VerifactuSoapEnvelopeService().wrap(payload);
        var document = parse(envelope);

        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("Envelope");
        assertThat(document.getElementsByTagNameNS("*", "Body").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS("*", "RegFactuSistemaFacturacion").getLength())
                .isEqualTo(1);
    }

    @Test
    void rechazaXmlVacioOMalFormado() {
        var service = new VerifactuSoapEnvelopeService();

        assertThatThrownBy(() -> service.wrap(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML");
        assertThatThrownBy(() -> service.wrap("<no-cierra>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XML");
    }

    private static org.w3c.dom.Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
