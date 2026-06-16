package com.tpverp.backend.verifactu;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;

class VerifactuMutualTlsHttpClientFactoryTest {

    @Test
    void creaClienteHttpConSslContextMutualTls() throws Exception {
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, "secreto".toCharArray());

        var client = new VerifactuMutualTlsHttpClientFactory()
                .create(keyStore, "secreto".toCharArray());

        assertThat(client).isNotNull();
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(client.sslContext()).isNotNull();
    }
}
