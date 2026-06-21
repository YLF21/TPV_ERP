package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalCorrectionSnapshotTest {

    private final FiscalCorrectionSnapshot corrections = new FiscalCorrectionSnapshot();

    @Test
    void correctsAdministrativeDataWithoutChangingEconomicContent() {
        var original = snapshot();
        var corrected = corrections.apply(
                original,
                new FiscalCorrectionRequest(
                        "NIF transmitido incorrectamente",
                        "B12345674",
                        "Cliente Corregido SL",
                        "Venta corregida"),
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                UUID.fromString("20000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-06-21T10:15:30Z"),
                true);

        assertThat(customer(corrected))
                .containsEntry("numeroDocumento", "B12345674")
                .containsEntry("nombreFiscal", "Cliente Corregido SL");
        assertThat(corrected)
                .containsEntry("descripcionOperacion", "Venta corregida")
                .containsEntry("subsanacion", "S")
                .containsEntry("rechazoPrevio", "S")
                .containsEntry("subsanacionMotivo", "NIF transmitido incorrectamente")
                .containsEntry("baseTotal", new BigDecimal("100.00"))
                .containsEntry("impuestoTotal", new BigDecimal("21.00"))
                .containsEntry("total", new BigDecimal("121.00"));
        assertThat(corrected.get("lineas")).isEqualTo(original.get("lineas"));
        assertThat(customer(original)).containsEntry("numeroDocumento", "B00000000");
    }

    @Test
    void requiresReasonAndAtLeastOneAdministrativeChange() {
        assertThatThrownBy(() -> corrections.apply(
                snapshot(),
                new FiscalCorrectionRequest(" ", "B12345674", null, null),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("motivo de subsanacion es obligatorio");

        assertThatThrownBy(() -> corrections.apply(
                snapshot(),
                new FiscalCorrectionRequest("Correccion", null, null, null),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("La subsanacion requiere algun dato corregido");
    }

    @Test
    void requiresRecipientTaxIdAndNameTogether() {
        assertThatThrownBy(() -> corrections.apply(
                snapshot(),
                new FiscalCorrectionRequest("Correccion", "B12345674", null, null),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now(), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NIF y nombre del destinatario deben corregirse juntos");
    }

    private static Map<String, Object> snapshot() {
        var customer = new LinkedHashMap<String, Object>();
        customer.put("numeroDocumento", "B00000000");
        customer.put("nombreFiscal", "Cliente Original SL");
        var value = new LinkedHashMap<String, Object>();
        value.put("cliente", customer);
        value.put("baseTotal", new BigDecimal("100.00"));
        value.put("impuestoTotal", new BigDecimal("21.00"));
        value.put("total", new BigDecimal("121.00"));
        value.put("lineas", List.of(Map.of("codigo", "P1", "total", new BigDecimal("121.00"))));
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> customer(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("cliente");
    }
}
