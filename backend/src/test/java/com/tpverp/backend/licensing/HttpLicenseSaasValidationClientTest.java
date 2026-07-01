package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpLicenseSaasValidationClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enviaPeticionYLeeRespuestaDeValidacion() throws Exception {
        var received = new AtomicReference<JsonNode>();
        var token = new AtomicReference<String>();
        HttpServer server = server(200, """
                {"status":"VALIDA","validUntil":"2027-08-10T00:00:00Z"}
                """, received, token);
        try {
            server.start();
            var credentials = org.mockito.Mockito.mock(LicenseSaasCredentialStore.class);
            org.mockito.Mockito.when(credentials.readToken()).thenReturn(Optional.of("token-local"));
            var client = new HttpLicenseSaasValidationClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials,
                    mapper,
                    HttpClient.newHttpClient());
            var request = new LicenseSaasValidationRequest(
                    UUID.randomUUID(), "INST-1", UUID.randomUUID(), "LIC-1", "hash");

            LicenseSaasValidationResponse response = client.validate(request);

            assertThat(response.status()).isEqualTo(LicenseSaasStatus.VALIDA);
            assertThat(response.validUntil()).isEqualTo(Instant.parse("2027-08-10T00:00:00Z"));
            assertThat(received.get().get("installationReference").asText()).isEqualTo("INST-1");
            assertThat(received.get().get("licenseReference").asText()).isEqualTo("LIC-1");
            assertThat(received.get().get("licenseHash").asText()).isEqualTo("hash");
            assertThat(token.get()).isEqualTo("token-local");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void propagaRespuestaNoExitosa() throws Exception {
        HttpServer server = server(503, "{}", new AtomicReference<>());
        try {
            server.start();
            var client = new HttpLicenseSaasValidationClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    org.mockito.Mockito.mock(LicenseSaasCredentialStore.class),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.validate(new LicenseSaasValidationRequest(
                    UUID.randomUUID(), "INST-1", UUID.randomUUID(), "LIC-1", "hash")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("503");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(int status, String response, AtomicReference<JsonNode> received)
            throws Exception {
        return server(status, response, received, new AtomicReference<>());
    }

    private HttpServer server(
            int status,
            String response,
            AtomicReference<JsonNode> received,
            AtomicReference<String> token)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/license/validate", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            byte[] body = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}
