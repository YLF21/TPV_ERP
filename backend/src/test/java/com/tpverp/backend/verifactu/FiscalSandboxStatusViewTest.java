package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FiscalSandboxStatusViewTest {

    @Test
    void publicaElNombreDeContratoQueConsumeAppGestion() throws Exception {
        var view = new FiscalSandboxStatusView(
                true,
                FiscalRuntimeClass.SANDBOX,
                FiscalEndpointEnvironment.TEST,
                FiscalTransportMode.SIMULATED,
                SimulatedAeatOutcome.ACCEPTED);

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(view));

        assertThat(json.path("sandboxEnabled").asBoolean()).isTrue();
        assertThat(json.has("enabled")).isFalse();
    }
}
