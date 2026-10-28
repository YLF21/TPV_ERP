package com.tpverp.backend.party;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.ProviderException;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/** Platform trust is scoped to the optional public CartoCiudad client, never the JVM. */
final class PartyAddressHttpClientFactory {
    private PartyAddressHttpClientFactory() { }

    static HttpClient create(Duration timeout) {
        var builder = HttpClient.newBuilder().connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            try {
                // Read only Windows' trusted roots; do not import the remote certificate.
                var roots = KeyStore.getInstance("Windows-ROOT");
                roots.load(null, null);
                builder.sslContext(contextFor(roots));
            } catch (GeneralSecurityException | IOException | ProviderException exception) {
                // An unavailable OS store falls back to ordinary JVM trust, never trust-all.
            }
        }
        var parameters = new SSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        return builder.sslParameters(parameters).build();
    }

    static SSLContext contextFor(KeyStore roots) throws GeneralSecurityException {
        var trust = TrustManagerFactory.getInstance("PKIX");
        trust.init(roots);
        var context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
