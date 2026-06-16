package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

class VerifactuXmlServiceTest {

    @Test
    void generaLoteAltaConCabeceraDatosFiscalesHuellaYSistema() {
        var xml = service().batchXml(request(record(FiscalRecordOperation.ALTA), "Empresa SL"));
        var document = parse(xml);

        assertThat(text(document, "RegFactuSistemaFacturacion", 0)).isNotBlank();
        assertThat(text(document, "NombreRazon", 0)).isEqualTo("Empresa SL");
        assertThat(text(document, "NIF", 0)).isEqualTo("B12345674");
        assertThat(text(document, "IDVersion", 0)).isEqualTo("1.0");
        assertThat(text(document, "IDEmisorFactura", 0)).isEqualTo("B12345674");
        assertThat(text(document, "NumSerieFactura", 0)).isEqualTo("001-260614-000001");
        assertThat(text(document, "FechaExpedicionFactura", 0)).isEqualTo("14-06-2026");
        assertThat(text(document, "TipoFactura", 0)).isEqualTo("F2");
        assertThat(text(document, "DescripcionOperacion", 0)).isEqualTo("Venta");
        assertThat(text(document, "CuotaTotal", 0)).isEqualTo("2.10");
        assertThat(text(document, "ImporteTotal", 0)).isEqualTo("12.10");
        assertThat(text(document, "FechaHoraHusoGenRegistro", 0)).isEqualTo("2026-06-14T10:15:30+01:00");
        assertThat(text(document, "TipoHuella", 0)).isEqualTo("01");
        assertThat(text(document, "Huella", 0)).isEqualTo("B".repeat(64));
        assertThat(text(document, "Huella", 1)).isEqualTo("A".repeat(64));
        assertThat(text(document, "NombreSistemaInformatico", 0)).isEqualTo("TPV ERP");
    }

    @Test
    void generaLoteAnulacionConFacturaAnuladaYEncadenamiento() {
        var xml = service().batchXml(request(record(FiscalRecordOperation.ANULACION), "Empresa SL"));
        var document = parse(xml);

        assertThat(text(document, "IDEmisorFacturaAnulada", 0)).isEqualTo("B12345674");
        assertThat(text(document, "NumSerieFacturaAnulada", 0)).isEqualTo("001-260614-000001");
        assertThat(text(document, "FechaExpedicionFacturaAnulada", 0)).isEqualTo("14-06-2026");
        assertThat(text(document, "RegistroAnterior", 0)).isNotBlank();
        assertThat(text(document, "Huella", 0)).isEqualTo("B".repeat(64));
        assertThat(text(document, "Huella", 1)).isEqualTo("A".repeat(64));
    }

    @Test
    void usaElRegimenYPorcentajeFiscalDelSnapshotSiExistenLineas() {
        var xml = service().batchXml(request(record(
                FiscalRecordOperation.ALTA,
                Map.of("regimenImpuesto", "IGIC", "porcentajeImpuesto", new BigDecimal("7.00"))),
                "Empresa SL"));
        var document = parse(xml);

        assertThat(text(document, "Impuesto", 0)).isEqualTo("03");
        assertThat(text(document, "TipoImpositivo", 0)).isEqualTo("7.00");
    }

    @Test
    void rechazaLotesSinRegistrosONombreEmisor() {
        assertThatThrownBy(() -> service().batchXml(request(List.of(), "Empresa SL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("registro");
        assertThatThrownBy(() -> service().batchXml(request(record(FiscalRecordOperation.ALTA), " ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nombre");
    }

    private static VerifactuXmlService service() {
        return new VerifactuXmlService();
    }

    private static VerifactuXmlBatchRequest request(FiscalRecord record, String issuerName) {
        return request(List.of(record), issuerName);
    }

    private static VerifactuXmlBatchRequest request(List<FiscalRecord> records, String issuerName) {
        return new VerifactuXmlBatchRequest(
                issuerName,
                "B12345674",
                records,
                new VerifactuSystemInfo(
                        "Fabricante TPV ERP", "B12345674", "TPV ERP", "01",
                        "0.0.1", "INST-001", true, false, false));
    }

    private static FiscalRecord record(FiscalRecordOperation operation) {
        return record(operation, Map.of());
    }

    private static FiscalRecord record(FiscalRecordOperation operation, Map<String, Object> line) {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, operation, FiscalDocumentType.F2,
                "001-260614-000001", LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T09:15:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "B".repeat(64), "A".repeat(64), "C".repeat(64), snapshot(line),
                "1.0", "SHA-256", "0.0.1");
    }

    private static Map<String, Object> snapshot(Map<String, Object> line) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("baseTotal", new BigDecimal("10.00"));
        snapshot.put("impuestoTotal", new BigDecimal("2.10"));
        snapshot.put("total", new BigDecimal("12.10"));
        snapshot.put("lineas", line.isEmpty() ? List.of() : List.of(line));
        return snapshot;
    }

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String text(Document document, String tag, int index) {
        return document.getElementsByTagNameNS("*", tag).item(index).getTextContent();
    }
}
