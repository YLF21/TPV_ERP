package com.tpverp.backend.verifactu;

import java.util.Arrays;

public class ConfiguredVerifactuTransport implements VerifactuTransport {

    private final VerifactuSubmissionPropertiesFactory propertiesFactory;
    private final ManagedCertificateKeyStoreFactory keyStores;
    private final VerifactuMutualTlsHttpClientFactory clients;

    public ConfiguredVerifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        this.propertiesFactory = propertiesFactory;
        this.keyStores = keyStores;
        this.clients = clients;
    }

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        propertiesFactory.current();
        try (var managed = keyStores.activeForCurrentCompany()) {
            var password = managed.password();
            try {
                var client = clients.create(managed.keyStore(), password);
                return new HttpVerifactuTransport(client).send(endpoint, soapEnvelope);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }
    // Retrasa la carga del certificado hasta que exista un envio real.
}
