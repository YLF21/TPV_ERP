package com.tpverp.backend.verifactu;

import java.util.Arrays;
import java.util.UUID;

public class ConfiguredVerifactuTransport implements VerifactuTransport {

    private final VerifactuSubmissionPropertiesFactory propertiesFactory;
    private final ManagedCertificateKeyStoreFactory keyStores;
    private final VerifactuMutualTlsHttpClientFactory clients;
    private final VerifactuEndpointResolver endpoints;
    private final FiscalRuntimeProperties runtime;

    public ConfiguredVerifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients) {
        this(propertiesFactory, keyStores, clients, new VerifactuEndpointResolver());
    }

    public ConfiguredVerifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients,
            VerifactuEndpointResolver endpoints) {
        this(propertiesFactory, keyStores, clients, endpoints, null);
    }

    public ConfiguredVerifactuTransport(
            VerifactuSubmissionPropertiesFactory propertiesFactory,
            ManagedCertificateKeyStoreFactory keyStores,
            VerifactuMutualTlsHttpClientFactory clients,
            VerifactuEndpointResolver endpoints,
            FiscalRuntimeProperties runtime) {
        this.propertiesFactory = propertiesFactory;
        this.keyStores = keyStores;
        this.clients = clients;
        this.endpoints = endpoints;
        this.runtime = runtime;
    }

    @Override
    public VerifactuTransportResponse send(String endpoint, String soapEnvelope) {
        requireNetworkOptIn();
        endpoints.requireOfficial(endpoint);
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

    @Override
    public VerifactuTransportResponse send(
            UUID companyId, UUID installationId, String endpoint, String soapEnvelope) {
        requireNetworkOptIn();
        endpoints.requireOfficial(endpoint);
        propertiesFactory.current();
        try (var managed = keyStores.activeForCompany(companyId, installationId)) {
            var password = managed.password();
            try {
                var client = clients.create(managed.keyStore(), password);
                return new HttpVerifactuTransport(client).send(endpoint, soapEnvelope);
            } finally {
                Arrays.fill(password, '\0');
            }
        }
    }

    private void requireNetworkOptIn() {
        if (runtime != null) {
            runtime.requireAeatTestNetwork();
        }
    }
    // Retrasa la carga del certificado hasta que exista un envio real.
}
