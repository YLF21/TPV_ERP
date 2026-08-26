package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FiscalScheduledTransitionViewTest {

    @Test
    void serializaSoloElCodigoDeErrorYNoElDetalleTecnico() throws Exception {
        var view = new FiscalScheduledTransitionView(
                FiscalMode.VERIFACTU, FiscalMode.NO_VERIFACTU,
                FiscalModeTransitionStatus.FALLIDA,
                Instant.parse("2026-08-26T10:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                LocalDate.of(2026, 8, 31), "ACK-1", "TRANSITION_FAILED");

        var mapper = new ObjectMapper().findAndRegisterModules();
        var json = mapper.readTree(mapper.writeValueAsString(view));

        assertThat(json.path("lastErrorCode").asText()).isEqualTo("TRANSITION_FAILED");
        assertThat(json.has("lastError")).isFalse();
        assertThat(json.has("technicalDetail")).isFalse();
    }
}
