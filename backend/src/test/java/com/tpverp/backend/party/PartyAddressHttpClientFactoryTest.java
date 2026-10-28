package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartyAddressHttpClientFactoryTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(4);
    @TempDir static Path directory;
    private static HttpsServer server;
    private static KeyStore roots;

    @BeforeAll
    static void start() throws Exception {
        // Fresh test-only key; nothing is added to either the Windows or JVM trust store.
        var storePath = directory.resolve("server.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "keytool.exe" : "keytool");
        var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", storePath.toString(),
                "-storepass", "test-only-password", "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally { process.destroyForcibly(); }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storePath)) {
            keys.load(input, "test-only-password".toCharArray());
        }
        roots = KeyStore.getInstance("PKCS12");
        roots.load(null, null);
        roots.setCertificateEntry("server", keys.getCertificate("server"));
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "test-only-password".toCharArray());
        var tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
    }

    @AfterAll static void stop() { if (server != null) server.stop(0); }

    @Test
    void platformClientRejectsUntrustedCertificates() {
        try (var client = PartyAddressHttpClientFactory.create(TIMEOUT)) {
            assertThatThrownBy(() -> get(client, "localhost")).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void pkixContextAcceptsTrustedCertificateButRejectsIncorrectHostname() throws Exception {
        try (var platformClient = PartyAddressHttpClientFactory.create(TIMEOUT);
             var client = HttpClient.newBuilder().sslContext(PartyAddressHttpClientFactory.contextFor(roots))
                     .sslParameters(platformClient.sslParameters()).connectTimeout(TIMEOUT)
                     .followRedirects(HttpClient.Redirect.NEVER).build()) {
            assertThat(get(client, "localhost").statusCode()).isEqualTo(200);
            assertThatThrownBy(() -> get(client, "127.0.0.1"))
                    .isInstanceOf(SSLHandshakeException.class)
                    .hasMessageContaining("subject alternative");
        }
    }

    @Test
    void keepsGlobalTlsContextAndClientLimitsUnchanged() throws Exception {
        var global = SSLContext.getDefault();
        try (var client = PartyAddressHttpClientFactory.create(TIMEOUT)) {
            assertThat(SSLContext.getDefault()).isSameAs(global);
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            assertThat(client.connectTimeout()).contains(TIMEOUT);
            assertThat(client.sslParameters().getEndpointIdentificationAlgorithm()).isEqualTo("HTTPS");
        }
    }

    private static HttpResponse<byte[]> get(HttpClient client, String host) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("https://" + host + ":"
                + server.getAddress().getPort() + "/")).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
    }
}
