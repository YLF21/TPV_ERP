package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalSubmissionEvidenceTest {

    @Test
    void conservaRequestExactoYHashUtf8() {
        var request = "<soap>á</soap>";
        var evidence = new FiscalSubmissionEvidence(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalEndpointEnvironment.TEST, UUID.randomUUID(), Instant.now(), Instant.now(),
                request, FiscalSubmissionEvidence.sha256(request));

        assertThat(evidence.getRequestXml()).isSameAs(request);
        assertThat(evidence.getRequestSha256()).isEqualTo(FiscalSubmissionEvidence.sha256(request));
    }

    @Test
    void rechazaPayloadDeSolicitudPorEncimaDelLimite() {
        var request = "x".repeat(FiscalSubmissionEvidence.MAX_REQUEST_BYTES + 1);

        assertThatThrownBy(() -> new FiscalSubmissionEvidence(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                FiscalEndpointEnvironment.TEST, UUID.randomUUID(), Instant.now(), Instant.now(),
                request, FiscalSubmissionEvidence.sha256(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limite");
    }

    @Test
    void permiteRespuestaVaciaComoRespuestaExacta() {
        var response = new FiscalSubmissionResponseEvidence(
                UUID.randomUUID(), Instant.now(), "", FiscalSubmissionEvidence.sha256(""));

        assertThat(response.getResponsePayload()).isEmpty();
        assertThat(response.getResponseSha256()).hasSize(64);
    }

    @Test
    void rechazaRespuestaPorEncimaDelLimiteDelTransporte() {
        var response = "x".repeat(FiscalSubmissionResponseEvidence.MAX_RESPONSE_BYTES + 1);

        assertThatThrownBy(() -> new FiscalSubmissionResponseEvidence(
                UUID.randomUUID(), Instant.now(), response,
                FiscalSubmissionEvidence.sha256(response)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limite");
    }
}
