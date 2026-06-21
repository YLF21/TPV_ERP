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
        assertThat(text(document, "IDEmisorFactura", 0)).isEqualTo("A58818501");
        assertThat(text(document, "NumSerieFactura", 0)).isEqualTo("FACTURA-ANTERIOR");
        assertThat(text(document, "FechaExpedicionFactura", 0)).isEqualTo("13-06-2026");
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
    void generaUnDetallePorCadaCombinacionFiscal() {
        var first = Map.<String, Object>of(
                "regimenImpuesto", "IVA",
                "porcentajeImpuesto", new BigDecimal("21.00"),
                "base", new BigDecimal("10.00"),
                "impuesto", new BigDecimal("2.10"));
        var second = Map.<String, Object>of(
                "regimenImpuesto", "IVA",
                "porcentajeImpuesto", new BigDecimal("10.00"),
                "base", new BigDecimal("20.00"),
                "impuesto", new BigDecimal("2.00"));

        var document = parse(service().batchXml(request(
                record(FiscalRecordOperation.ALTA, List.of(first, second)), "Empresa SL")));

        assertThat(document.getElementsByTagNameNS("*", "DetalleDesglose").getLength())
                .isEqualTo(2);
        assertThat(text(document, "TipoImpositivo", 0)).isEqualTo("21.00");
        assertThat(text(document, "BaseImponibleOimporteNoSujeto", 0)).isEqualTo("10.00");
        assertThat(text(document, "CuotaRepercutida", 0)).isEqualTo("2.10");
        assertThat(text(document, "TipoImpositivo", 1)).isEqualTo("10.00");
        assertThat(text(document, "BaseImponibleOimporteNoSujeto", 1)).isEqualTo("20.00");
        assertThat(text(document, "CuotaRepercutida", 1)).isEqualTo("2.00");
    }

    @Test
    void incluyeFacturasSustituidasEnAltaF3() {
        var snapshot = new LinkedHashMap<>(snapshot(Map.of()));
        snapshot.put("facturasSustituidas", List.of(Map.of(
                "nifEmisor", "B12345674",
                "numero", "001-260614-000001",
                "fecha", "2026-06-14")));
        var replacement = fiscalRecord(
                FiscalDocumentType.F3, "FV-001-26-000001", snapshot);

        var document = parse(service().batchXml(request(replacement, "Empresa SL")));

        assertThat(text(document, "FacturasSustituidas", 0)).isNotBlank();
        assertThat(text(document, "IDFacturaSustituida", 0)).isNotBlank();
        assertThat(text(document, "IDEmisorFactura", 1)).isEqualTo("B12345674");
        assertThat(text(document, "NumSerieFactura", 1))
                .isEqualTo("001-260614-000001");
        assertThat(text(document, "FechaExpedicionFactura", 1))
                .isEqualTo("14-06-2026");
    }

    @Test
    void incluyeTipoRectificativaEnFacturasRectificativas() {
        var rectification = new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 3, FiscalRecordOperation.ALTA, FiscalDocumentType.R1,
                "FRV-001-26-000001", LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T09:15:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "B".repeat(64), "A".repeat(64), "C".repeat(64),
                snapshot(Map.of(), "S"), "1.0", "SHA-256", "0.0.1");

        var xml = service().batchXml(request(rectification, "Empresa SL"));
        var document = parse(xml);

        assertThat(text(document, "TipoFactura", 0)).isEqualTo("R1");
        assertThat(text(document, "TipoRectificativa", 0)).isEqualTo("S");
    }

    @Test
    void incluyeIndicadoresDestinatarioYDescripcionDeSubsanacionEnOrdenOficial() {
        var corrected = new LinkedHashMap<>(snapshot(Map.of()));
        corrected.put("subsanacion", "S");
        corrected.put("rechazoPrevio", "S");
        corrected.put("descripcionOperacion", "Venta corregida");
        corrected.put("cliente", Map.of(
                "numeroDocumento", "B12345674",
                "nombreFiscal", "Cliente Corregido SL"));

        var xml = service().batchXml(request(
                fiscalRecord(FiscalDocumentType.F1, "FV-001-26-000001", corrected),
                "Empresa SL"));

        assertThat(xml).containsSubsequence(
                "<sf:NombreRazonEmisor>",
                "<sf:Subsanacion>S</sf:Subsanacion>",
                "<sf:RechazoPrevio>S</sf:RechazoPrevio>",
                "<sf:TipoFactura>F1</sf:TipoFactura>");
        assertThat(xml)
                .contains("<sf:DescripcionOperacion>Venta corregida</sf:DescripcionOperacion>")
                .contains("<sf:Destinatarios>")
                .contains("<sf:NombreRazon>Cliente Corregido SL</sf:NombreRazon>")
                .contains("<sf:NIF>B12345674</sf:NIF>");
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
        return record(operation, line.isEmpty() ? List.of() : List.of(line));
    }

    private static FiscalRecord record(
            FiscalRecordOperation operation, List<Map<String, Object>> lines) {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, operation, FiscalDocumentType.F2,
                "001-260614-000001", LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T09:15:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "B".repeat(64), "A".repeat(64), "C".repeat(64), snapshot(lines),
                "1.0", "SHA-256", "0.0.1");
    }

    private static FiscalRecord fiscalRecord(
            FiscalDocumentType type, String number, Map<String, Object> snapshot) {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, FiscalRecordOperation.ALTA, type,
                number, LocalDate.of(2026, 6, 14),
                Instant.parse("2026-06-14T09:15:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "B".repeat(64), "A".repeat(64), "C".repeat(64), snapshot,
                "1.0", "SHA-256", "0.0.1");
    }

    private static Map<String, Object> snapshot(Map<String, Object> line) {
        return snapshot(line.isEmpty() ? List.of() : List.of(line), null);
    }

    private static Map<String, Object> snapshot(Map<String, Object> line, String rectificationType) {
        return snapshot(line.isEmpty() ? List.of() : List.of(line), rectificationType);
    }

    private static Map<String, Object> snapshot(List<Map<String, Object>> lines) {
        return snapshot(lines, null);
    }

    private static Map<String, Object> snapshot(
            List<Map<String, Object>> lines, String rectificationType) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("baseTotal", new BigDecimal("10.00"));
        snapshot.put("impuestoTotal", new BigDecimal("2.10"));
        snapshot.put("total", new BigDecimal("12.10"));
        snapshot.put("lineas", lines);
        snapshot.put("registroAnterior", Map.of(
                "nifEmisor", "A58818501",
                "numero", "FACTURA-ANTERIOR",
                "fecha", "2026-06-13",
                "huella", "B".repeat(64)));
        if (rectificationType != null) {
            snapshot.put("tipoRectificativa", rectificationType);
        }
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
