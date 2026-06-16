package com.tpverp.backend.verifactu;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;

@Component
public class VerifactuMutualTlsHttpClientFactory {

    public HttpClient create(KeyStore keyStore, char[] password) {
        try {
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password == null ? new char[0] : password.clone());
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers.getKeyManagers(), null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(sslContext)
                    .build();
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("No se pudo preparar el cliente mTLS VERI*FACTU", exception);
        }
    }
    // Construye un cliente HTTP con certificado cliente para el servicio SOAP de AEAT.
}
