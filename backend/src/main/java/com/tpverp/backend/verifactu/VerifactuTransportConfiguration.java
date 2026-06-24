package com.tpverp.backend.verifactu;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import com.tpverp.backend.shared.crypto.WindowsMachineDpapiSecretProtector;
import java.nio.file.Path;

@Configuration
public class VerifactuTransportConfiguration {

    @Bean
    public VerifactuTransport verifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        return new ConfiguredVerifactuTransport(propertiesFactory, keyStores, clients);
    }
    // Registra el transporte real usando el certificado configurado para mTLS.

    @Bean
    VerifactuCertificateSecretStore verifactuCertificateSecretStore(
            @Value("${tpv.verifactu.secret-directory}") Path directory) {
        return new VerifactuCertificateSecretStore(
                directory, new WindowsMachineDpapiSecretProtector());
    }
}
