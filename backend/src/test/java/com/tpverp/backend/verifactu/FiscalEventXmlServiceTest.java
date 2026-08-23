package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import java.io.StringReader;

class FiscalEventXmlServiceTest {
    private final FiscalEventXmlService service = new FiscalEventXmlService();

    @Test
    void generaRegistroEventoStandaloneConCadenaYHuella() throws Exception {
        var system = new VerifactuSystemInfo(
                "TPV ERP DEV", "B00000000", "TPV ERP", "TPVERP", "4.1.0",
                "DEV-1", false, true, false);
        var xml = service.unsignedXml(system, "Empresa DEV", "DEMO-00000000",
                FiscalEventType.START_NO_VERIFACTU, "inicio", 
                OffsetDateTime.parse("2026-08-23T19:00:00+01:00"), null,
                "A".repeat(64));

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(document.getDocumentElement().getLocalName()).isEqualTo("RegistroEvento");
        assertThat(document.getDocumentElement().getNamespaceURI())
                .isEqualTo(FiscalEventXmlService.EVENT_NS);
        assertThat(document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS,
                "TipoEvento").item(0).getTextContent()).isEqualTo("01");
        assertThat(document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS,
                "PrimerEvento").item(0).getTextContent()).isEqualTo("S");
        assertThat(document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS,
                "HuellaEvento").item(0).getTextContent()).isEqualTo("A".repeat(64));
    }

    @Test
    void incluyeDatosPropiosEnLanzamientoDeAnomalias() throws Exception {
        var system = new VerifactuSystemInfo(
                "TPV ERP DEV", "B00000000", "TPV ERP", "01", "4.1.0", "DEV-1",
                false, true, false);
        var xml = service.unsignedXml(system, "Empresa DEV", "B00000000",
                FiscalEventType.BILLING_ANOMALY_SCAN_STARTED, null,
                OffsetDateTime.parse("2026-08-23T19:00:00+01:00"), null,
                "A".repeat(64));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        assertThat(document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS,
                "LanzamientoProcesoDeteccionAnomaliasRegFacturacion").getLength()).isEqualTo(1);
        assertThat(document.getElementsByTagNameNS(FiscalEventXmlService.EVENT_NS,
                "NumeroDeRegistrosFacturacionProcesadosSobreIntegridadHuellas")
                .item(0).getTextContent()).isEqualTo("0");
    }
}
