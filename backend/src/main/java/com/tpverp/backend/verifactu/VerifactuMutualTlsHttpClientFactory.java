package com.tpverp.backend.verifactu;

import java.net.http.HttpClient;
import java.time.Duration;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.springframework.stereotype.Component;

@Component
public class VerifactuMutualTlsHttpClientFactory {

    public HttpClient create(KeyStore keyStore, char[] password) {
        var workingPassword = password == null ? new char[0] : password.clone();
        try {
            var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, workingPassword);
            var sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers.getKeyManagers(), null, null);
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sslContext)
                    .build();
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("No se pudo preparar el cliente mTLS VERI*FACTU", exception);
        } finally {
            Arrays.fill(workingPassword, '\0');
        }
    }
    // Construye un cliente HTTP con certificado cliente para el servicio SOAP de AEAT.
}
