package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class VerifactuTransportConfigurationTest {

    @Test
    void creaTransporteHttpConCertificadoConfigurado() throws Exception {
        var factory = mock(VerifactuSubmissionPropertiesFactory.class);
        var managed = mock(ManagedCertificateKeyStoreFactory.class);

        var transport = new VerifactuTransportConfiguration()
                .verifactuTransport(factory, managed, new VerifactuMutualTlsHttpClientFactory());

        assertThat(transport).isInstanceOf(ConfiguredVerifactuTransport.class);
    }

    @Test
    void noCargaCertificadoAlCrearElBean() {
        var factory = mock(VerifactuSubmissionPropertiesFactory.class);
        var managed = mock(ManagedCertificateKeyStoreFactory.class);

        var transport = new VerifactuTransportConfiguration()
                .verifactuTransport(factory, managed, new VerifactuMutualTlsHttpClientFactory());

        assertThat(transport).isInstanceOf(ConfiguredVerifactuTransport.class);
        verify(factory, never()).current();
        verify(managed, never()).activeForCurrentCompany();
    }
}
