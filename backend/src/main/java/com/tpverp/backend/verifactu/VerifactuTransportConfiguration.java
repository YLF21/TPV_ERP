package com.tpverp.backend.verifactu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VerifactuTransportConfiguration {

    @Bean
    public VerifactuTransport verifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        return new ConfiguredVerifactuTransport(propertiesFactory, keyStores, clients);
    }
    // Registra el transporte real usando el certificado configurado para mTLS.
}
