package com.tpverp.backend.licensing;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class LicenseSaasValidationStartupTest {

    @Test
    void validaLicenciaActivaAlArrancar() {
        var service = org.mockito.Mockito.mock(LicenseSaasValidationService.class);
        var startup = new LicenseSaasValidationStartup(service);

        startup.validateOnStartup();

        verify(service).validateActiveLicense();
    }
}
