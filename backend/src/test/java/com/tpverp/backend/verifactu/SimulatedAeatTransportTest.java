package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
