package com.tpverp.backend.licensing;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LicenseSaasValidationStartupTest {

    @Test
    void validaTodasLasLicenciasSaasActivasAlArrancar() {
        var service = org.mockito.Mockito.mock(LicenseSaasValidationService.class);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.activeSaasLicenseIds()).thenReturn(List.of(first, second));
        var startup = new LicenseSaasValidationStartup(service);

        startup.validateOnStartup();

        verify(service).validateLicense(first);
        verify(service).validateLicense(second);
    }
}
