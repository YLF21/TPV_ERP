package com.tpverp.backend.verifactu;

public class ConfiguredVerifactuTransport implements VerifactuTransport {

    private final VerifactuSubmissionPropertiesFactory propertiesFactory;
    private final VerifactuPkcs12KeyStoreLoader keyStores;
    private final VerifactuMutualTlsHttpClientFactory clients;

    public ConfiguredVerifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            VerifactuPkcs12KeyStoreLoader keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        this.propertiesFactory = propertiesFactory;
        this.keyStores = keyStores;
        this.clients = clients;
    }

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        var properties = propertiesFactory.current();
        var keyStore = keyStores.load(
                properties.certificatePath(), properties.certificatePassword());
        var client = clients.create(keyStore, properties.certificatePassword());
        return new HttpVerifactuTransport(client).send(endpoint, soapEnvelope);
    }
    // Retrasa la carga del certificado hasta que exista un envio real.
}
