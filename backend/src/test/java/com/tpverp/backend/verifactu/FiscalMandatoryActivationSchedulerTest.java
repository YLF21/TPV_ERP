package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tpverp.backend.licensing.License;
import com.tpverp.backend.licensing.LicenseRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FiscalMandatoryActivationSchedulerTest {

    @Test
    void aislaElFalloDeUnaLicenciaYContinuaConLasDemas() {
        Instant now = Instant.parse("2027-01-01T00:00:05Z");
        var first = license("LIC-1");
        var second = license("LIC-2");
        var licenses = mock(LicenseRepository.class);
        when(licenses.findByActivaTrueOrderByValidaDesdeDesc())
                .thenReturn(List.of(first, second));
        var activation = mock(FiscalMandatoryActivationService.class);
        when(activation.activateIfDue(first.getId(), now))
                .thenThrow(new IllegalStateException("politica ausente"));
        when(activation.activateIfDue(second.getId(), now)).thenReturn(true);
        var scheduler = new FiscalMandatoryActivationScheduler(
                licenses, activation, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(scheduler.applyDue(now)).isEqualTo(1);

        verify(activation).activateIfDue(first.getId(), now);
        verify(activation).activateIfDue(second.getId(), now);
    }

    private static License license(String reference) {
        var license = mock(License.class);
        when(license.getId()).thenReturn(UUID.randomUUID());
        when(license.getReferencia()).thenReturn(reference);
        return license;
    }
}
