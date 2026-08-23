package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

class SimulatedAeatTransportTest {

    @Test
    void consumesConfiguredOutcomeAtomicallyAndResetsToAccepted() {
        var transport = new SimulatedAeatTransport();
        transport.setNextOutcome(SimulatedAeatOutcome.REJECTED);

        var rejected = transport.send("test", "soap");
        var accepted = transport.send("test", "soap");

        assertThat(rejected.httpStatus()).isEqualTo(200);
        assertThat(rejected.body()).contains("Incorrecto");
        assertThat(accepted.httpStatus()).isEqualTo(200);
        assertThat(accepted.body()).contains("Correcto");
    }

    @Test
    void modelsTimeoutAsTransportFailure() {
        var transport = new SimulatedAeatTransport();
        transport.setNextOutcome(SimulatedAeatOutcome.TIMEOUT);

        assertThatThrownBy(() -> transport.send("test", "soap"))
                .isInstanceOf(VerifactuTransportException.class)
                .hasMessageContaining("Timeout");
    }

    @ParameterizedTest
    @EnumSource(value = SimulatedAeatOutcome.class, names = {
            "ACCEPTED", "ACCEPTED_WITH_ERRORS", "REJECTED", "DUPLICATE",
            "HTTP_ERROR", "INVALID_RESPONSE"
    })
    void modelsEveryNonTimeoutOutcomeAndResetsToAccepted(SimulatedAeatOutcome outcome) {
        var transport = new SimulatedAeatTransport();
        transport.setNextOutcome(outcome);

        var response = transport.send("https://prewww1.aeat.es/official", "soap");

        assertThat(response.httpStatus()).isEqualTo(outcome == SimulatedAeatOutcome.HTTP_ERROR
                ? 503 : 200);
        if (outcome == SimulatedAeatOutcome.INVALID_RESPONSE) {
            assertThat(response.body()).isEqualTo("not xml");
        } else if (outcome == SimulatedAeatOutcome.HTTP_ERROR) {
            assertThat(response.body()).contains("HTTP error");
        } else {
            assertThat(response.body()).isNotBlank();
        }
        assertThat(transport.nextOutcome()).isEqualTo(SimulatedAeatOutcome.ACCEPTED);
    }
}
