package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.licensing.application.TaxpayerType;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VerifactuActivationServiceTest {

    private final VerifactuActivationService service = new VerifactuActivationService();

    @ParameterizedTest
    @CsvSource({
        "SOCIEDAD,2026-12-31T23:59:59Z,false",
        "SOCIEDAD,2027-01-01T00:00:00Z,true",
        "AUTONOMO,2027-06-30T23:59:59Z,false",
        "AUTONOMO,2027-07-01T00:00:00Z,true"
    })
    void aplicaLaFechaLegal(TaxpayerType type, Instant now, boolean expected) {
        assertThat(service.isLegallyRequired(type, now)).isEqualTo(expected);
    }

    @Test
    void permiteDesactivarAntesDeLaFechaLegalYPrimeraRemision() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-06-14T10:00:00Z"));

        service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z"));

        assertThat(configuration.isVoluntarilyActive()).isFalse();
        assertThat(service.isActive(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z"))).isFalse();
    }

    @Test
    void activacionVoluntariaRepetidaConservaLaFechaOriginal() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        var original = Instant.parse("2026-06-14T10:00:00Z");

        configuration.activateVoluntarily(original);
        configuration.activateVoluntarily(Instant.parse("2026-06-15T10:00:00Z"));

        assertThat(configuration.getActivatedAt()).isEqualTo(original);
    }

    @Test
    void impideDesactivarDespuesDeLaPrimeraRemision() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-06-14T10:00:00Z"));
        service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-14T10:05:00Z"));

        assertThatThrownBy(() -> service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void impideDesactivarCuandoLaActivacionYaEsLegalmenteObligatoria() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-12-01T10:00:00Z"));

        assertThatThrownBy(() -> service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2027-01-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registraPrimeraRemisionCuandoLaObligacionLegalEstaActiva() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        var submittedAt = Instant.parse("2027-01-01T00:00:00Z");

        service.markFirstSubmission(configuration, TaxpayerType.SOCIEDAD, submittedAt);

        assertThat(configuration.getFirstSubmissionAt()).isEqualTo(submittedAt);
    }

    @Test
    void rechazaPrimeraRemisionSinActivacionVoluntariaNiLegal() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());

        assertThatThrownBy(() -> service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-12-31T23:59:59Z")))
                .isInstanceOf(IllegalStateException.class);
    }
}
