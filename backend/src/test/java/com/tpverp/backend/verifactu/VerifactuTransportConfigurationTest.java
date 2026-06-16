package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;

class VerifactuTransportConfigurationTest {

    @Test
    void creaTransporteHttpConCertificadoConfigurado() throws Exception {
        var properties = new VerifactuSubmissionProperties(
                VerifactuEndpointMode.TEST, Path.of("cert.p12"),
                "secreto".toCharArray(), "TPV ERP", "01");
        var factory = mock(VerifactuSubmissionPropertiesFactory.class);
        var loader = mock(VerifactuPkcs12KeyStoreLoader.class);
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, "secreto".toCharArray());
        when(factory.current()).thenReturn(properties);
        when(loader.load(Path.of("cert.p12"), "secreto".toCharArray())).thenReturn(keyStore);

        var transport = new VerifactuTransportConfiguration()
                .verifactuTransport(factory, loader, new VerifactuMutualTlsHttpClientFactory());

        assertThat(transport).isInstanceOf(ConfiguredVerifactuTransport.class);
    }

    @Test
    void noCargaCertificadoAlCrearElBean() {
        var factory = mock(VerifactuSubmissionPropertiesFactory.class);
        var loader = mock(VerifactuPkcs12KeyStoreLoader.class);

        var transport = new VerifactuTransportConfiguration()
                .verifactuTransport(factory, loader, new VerifactuMutualTlsHttpClientFactory());

        assertThat(transport).isInstanceOf(ConfiguredVerifactuTransport.class);
        verify(factory, never()).current();
        verify(loader, never()).load(null, null);
    }
}
