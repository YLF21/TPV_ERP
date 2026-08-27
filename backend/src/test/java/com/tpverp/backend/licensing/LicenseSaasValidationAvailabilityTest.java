package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class LicenseSaasValidationAvailabilityTest {

    @Test
    void unaCaidaDelSaasNoAbortaElArranqueLocal() {
        LicenseSaasValidationService service = unavailableService();

        assertThatCode(() -> new LicenseSaasValidationStartup(service).validateOnStartup())
                .doesNotThrowAnyException();
    }

    @Test
    void unaCaidaDelSaasNoDetieneLasValidacionesProgramadas() {
        LicenseSaasValidationService service = unavailableService();

        assertThatCode(() -> new LicenseSaasValidationScheduler(service).tick())
                .doesNotThrowAnyException();
    }

    @Test
    void elFalloDeUnaTiendaNoImpideValidarLaSiguiente() {
        LicenseSaasValidationService service = mock(LicenseSaasValidationService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.activeSaasLicenseIds()).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("SaaS no disponible"))
                .when(service).validateLicense(first);

        assertThatCode(() -> new LicenseSaasValidationScheduler(service).tick())
                .doesNotThrowAnyException();

        verify(service).validateLicense(first);
        verify(service).validateLicense(second);
    }

    @Test
    void periodicValidationWaitsForTheStartupValidationToFinish() throws Exception {
        Scheduled scheduled = LicenseSaasValidationScheduler.class
                .getMethod("tick")
                .getAnnotation(Scheduled.class);

        org.assertj.core.api.Assertions.assertThat(scheduled.initialDelayString())
                .isEqualTo("${tpv.license.validation-initial-delay-ms:60000}");
    }

    private static LicenseSaasValidationService unavailableService() {
        LicenseSaasValidationService service = mock(LicenseSaasValidationService.class);
        when(service.activeSaasLicenseIds()).thenThrow(new IllegalStateException("SaaS no disponible"));
        return service;
    }
}
