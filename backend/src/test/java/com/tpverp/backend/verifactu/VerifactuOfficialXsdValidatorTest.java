package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerifactuOfficialXsdValidatorTest {

    @Test
    void validaXmlDeAltaContraXsdOficial() {
        var xml = new VerifactuXmlService().batchXml(request(record()));

        assertThatCode(() -> validator().validate(xml))
                .doesNotThrowAnyException();
    }

    @Test
    void validaFacturaF3ConFacturaSustituidaContraXsdOficial() {
        var snapshot = new LinkedHashMap<>(snapshot());
        snapshot.put("facturasSustituidas", List.of(Map.of(
                "nifEmisor", "B12345674",
                "numero", "001-260617-000001",
                "fecha", "2026-06-17")));
        snapshot.put("registroAnterior", Map.of(
                "nifEmisor", "B12345674",
                "numero", "001-260617-000001",
                "fecha", "2026-06-17",
                "huella", "A".repeat(64)));
        var replacement = new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, FiscalRecordOperation.ALTA, FiscalDocumentType.F3,
                "FV-001-26-000001", LocalDate.of(2026, 6, 17),
                Instant.parse("2026-06-17T09:16:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "A".repeat(64), "B".repeat(64), "C".repeat(64), snapshot,
                "1.0", "SHA-256", "0.0.1");

        var xml = new VerifactuXmlService().batchXml(request(replacement));

        assertThatCode(() -> validator().validate(xml)).doesNotThrowAnyException();
    }

    @Test
    void validaSubsanacionConDestinatarioContraXsdOficial() {
        var corrected = new LinkedHashMap<>(snapshot());
        corrected.put("subsanacion", "S");
        corrected.put("rechazoPrevio", "S");
        corrected.put("descripcionOperacion", "Venta corregida");
        corrected.put("cliente", Map.of(
                "numeroDocumento", "B12345674",
                "nombreFiscal", "Cliente Corregido SL"));
        corrected.put("registroAnterior", Map.of(
                "nifEmisor", "B12345674",
                "numero", "001-260617-000001",
                "fecha", "2026-06-17",
                "huella", "A".repeat(64)));
        var correction = new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 2, FiscalRecordOperation.ALTA, FiscalDocumentType.F1,
                "FV-001-26-000001", LocalDate.of(2026, 6, 17),
                Instant.parse("2026-06-17T09:16:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                "A".repeat(64), "B".repeat(64), "C".repeat(64), corrected,
                "1.0", "SHA-256", "0.0.1");

        var xml = new VerifactuXmlService().batchXml(request(correction));

        assertThatCode(() -> validator().validate(xml)).doesNotThrowAnyException();
    }

    @Test
    void rechazaXmlQueNoCumpleElXsdOficial() {
        assertThatThrownBy(() -> validator().validate("""
                <sfLR:RegFactuSistemaFacturacion
                  xmlns:sfLR="https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroLR.xsd"/>
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XSD");
    }

    private static VerifactuOfficialXsdValidator validator() {
        return new VerifactuOfficialXsdValidator();
    }

    private static VerifactuXmlBatchRequest request(FiscalRecord record) {
        return new VerifactuXmlBatchRequest(
                "Empresa SL",
                "B12345674",
                List.of(record),
                new VerifactuSystemInfo(
                        "Fabricante TPV ERP", "B12345674", "TPV ERP", "01",
                        "0.0.1", "INST-001", true, false, false));
    }

    private static FiscalRecord record() {
        return new FiscalRecord(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, FiscalRecordOperation.ALTA, FiscalDocumentType.F2,
                "001-260617-000001", LocalDate.of(2026, 6, 17),
                Instant.parse("2026-06-17T09:15:30Z"), "Atlantic/Canary",
                "B12345674", new BigDecimal("2.10"), new BigDecimal("12.10"),
                null, "A".repeat(64), "B".repeat(64), snapshot(),
                "1.0", "SHA-256", "0.0.1");
    }

    private static Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("baseTotal", new BigDecimal("10.00"));
        snapshot.put("impuestoTotal", new BigDecimal("2.10"));
        snapshot.put("total", new BigDecimal("12.10"));
        snapshot.put("lineas", List.of(Map.of(
                "regimenImpuesto", "IVA",
                "porcentajeImpuesto", new BigDecimal("21.00"))));
        return snapshot;
    }
}
