package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpverp.backend.licensing.application.TaxpayerType;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VerifactuActivationServiceTest {

    private final VerifactuActivationService service = new VerifactuActivationService();

    @ParameterizedTest
    @CsvSource({
        "SOCIEDAD,2026-12-31T22:59:59Z,Europe/Madrid,false",
        "SOCIEDAD,2026-12-31T23:00:00Z,Europe/Madrid,true",
        "SOCIEDAD,2026-12-31T23:59:59Z,Atlantic/Canary,false",
        "SOCIEDAD,2027-01-01T00:00:00Z,Atlantic/Canary,true",
        "AUTONOMO,2027-06-30T22:00:00Z,Europe/Madrid,true",
        "AUTONOMO,2027-07-01T00:00:00Z,Atlantic/Canary,true"
    })
    void exponeLaFechaDeAdaptacionSifEnLaZonaFiscal(
            TaxpayerType type, Instant now, ZoneId zoneId, boolean expected) {
        assertThat(service.isSifAdaptationRequired(type, now, zoneId)).isEqualTo(expected);
    }

    @Test
    void unaPoliticaExplicitaPosteriorSeRespetaSinForzarLaFechaLegalSif() {
        var zone = ZoneId.of("Atlantic/Canary");

        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 3, 1),
                Instant.parse("2027-01-15T00:00:00Z"),
                zone)).isFalse();
        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 3, 1),
                Instant.parse("2027-03-01T00:00:00Z"),
                zone)).isTrue();
    }

    @Test
    void unaLicenciaSinFechaNoFuerzaVerifactuTrasLaAdaptacionSif() {
        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD,
                null,
                Instant.parse("2027-01-15T00:00:00Z"),
                ZoneId.of("Atlantic/Canary"))).isFalse();
    }

    @Test
    void unaPoliticaAnteriorPuedeAdelantarLaObligacion() {
        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2026, 12, 1),
                Instant.parse("2026-12-01T00:00:00Z"),
                ZoneId.of("Atlantic/Canary"))).isTrue();
    }

    @Test
    void unaPoliticaEnCanariasRespetaElHorarioDeVerano() {
        var zone = ZoneId.of("Atlantic/Canary");
        var activationDate = java.time.LocalDate.of(2026, 6, 14);

        assertThat(service.activationInstant(
                TaxpayerType.SOCIEDAD, activationDate, zone))
                .isEqualTo(Instant.parse("2026-06-13T23:00:00Z"));
        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD, activationDate,
                Instant.parse("2026-06-13T22:59:59Z"), zone)).isFalse();
        assertThat(service.isAutomaticallyRequired(
                TaxpayerType.SOCIEDAD, activationDate,
                Instant.parse("2026-06-13T23:00:00Z"), zone)).isTrue();
    }

    @Test
    void unaPrimeraRemisionConservaLaActivacionAunqueLaPoliticaPosteriorCambie() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-06-14T10:00:00Z"));
        service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 1, 1),
                Instant.parse("2026-06-14T10:05:00Z"),
                ZoneId.of("Atlantic/Canary"));

        assertThat(service.isActive(
                configuration,
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2028, 1, 1),
                Instant.parse("2026-06-15T00:00:00Z"),
                ZoneId.of("Atlantic/Canary"))).isTrue();
    }

    @Test
    void permiteDesactivarAntesDeLaFechaLegalYPrimeraRemision() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-06-14T10:00:00Z"));

        service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z"),
                ZoneId.of("Atlantic/Canary"));

        assertThat(configuration.isVoluntarilyActive()).isFalse();
        assertThat(service.isActive(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z"),
                ZoneId.of("Atlantic/Canary"))).isFalse();
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
                Instant.parse("2026-06-14T10:05:00Z"),
                ZoneId.of("Atlantic/Canary"));

        assertThatThrownBy(() -> service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-06-15T10:00:00Z"),
                ZoneId.of("Atlantic/Canary")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void impideDesactivarCuandoLaPoliticaDeLicenciaYaEsEfectiva() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        configuration.activateVoluntarily(Instant.parse("2026-12-01T10:00:00Z"));

        assertThatThrownBy(() -> service.deactivateVoluntarily(
                configuration,
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 1, 1),
                Instant.parse("2027-01-01T00:00:00Z"),
                ZoneId.of("Atlantic/Canary")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registraPrimeraRemisionCuandoLaPoliticaDeLicenciaEstaActiva() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());
        var submittedAt = Instant.parse("2027-01-01T00:00:00Z");

        service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 1, 1),
                submittedAt,
                ZoneId.of("Atlantic/Canary"));

        assertThat(configuration.getFirstSubmissionAt()).isEqualTo(submittedAt);
        assertThat(configuration.getActivatedAt())
                .isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void conservaComoActivacionElInicioDeLaPoliticaDeLicenciaEnMadrid() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());

        service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                java.time.LocalDate.of(2027, 1, 1),
                Instant.parse("2027-01-01T10:00:00Z"),
                ZoneId.of("Europe/Madrid"));

        assertThat(configuration.getActivatedAt())
                .isEqualTo(Instant.parse("2026-12-31T23:00:00Z"));
    }

    @Test
    void rechazaPrimeraRemisionSinActivacionVoluntariaNiLegal() {
        var configuration = new VerifactuConfiguration(UUID.randomUUID());

        assertThatThrownBy(() -> service.markFirstSubmission(
                configuration,
                TaxpayerType.SOCIEDAD,
                Instant.parse("2026-12-31T22:59:59Z"),
                ZoneId.of("Europe/Madrid")))
                .isInstanceOf(IllegalStateException.class);
    }
}
