package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpLicenseSaasLinkClientTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void enviaCodigoTemporalYDevuelveLicenciaSaas() throws Exception {
        var received = new AtomicReference<JsonNode>();
        HttpServer server = server(200, response(), received);
        try {
            server.start();
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            LicenseSaasLinkResponse result = client.link(request());

            assertThat(received.get().get("pairingCode").asText()).isEqualTo("ABC123");
            assertThat(received.get().get("installationReference").asText()).isEqualTo("INST-1");
            assertThat(result.licenseReference()).isEqualTo("LIC-SAAS-1");
            assertThat(result.companyTaxId()).isEqualTo("B12345678");
            assertThat(result.companyName()).isEqualTo("EMPRESA REAL");
            assertThat(result.storeCode()).isEqualTo("001");
            assertThat(result.storeName()).isEqualTo("TIENDA 001");
            assertThat(result.status()).isEqualTo(LicenseSaasStatus.VALIDA);
            assertThat(result.validUntil()).isEqualTo(Instant.parse("2027-08-10T00:00:00Z"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rechazaRespuestaNoExitosa() throws Exception {
        HttpServer server = server(409, "{}", new AtomicReference<>());
        try {
            server.start();
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.link(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("409");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(int status, String response, AtomicReference<JsonNode> received)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/license/link", exchange -> {
            try (var body = exchange.getRequestBody()) {
                received.set(mapper.readTree(body));
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private LicenseSaasLinkRequest request() {
        return new LicenseSaasLinkRequest(
                "ABC123",
                UUID.randomUUID(),
                "INST-1",
                "public-key",
                null,
                null,
                null,
                null);
    }

    private String response() throws Exception {
        return mapper.writeValueAsString(new LicenseSaasLinkResponse(
                "LIC-SAAS-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "B12345678",
                "EMPRESA REAL",
                address(),
                "001",
                "TIENDA 001",
                address(),
                Instant.parse("2027-08-10T00:00:00Z"),
                LicenseSaasStatus.VALIDA,
                2,
                1,
                "B12345678",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                "token"));
    }

    private java.util.Map<String, String> address() {
        return java.util.Map.of(
                "linea1", "Calle Uno",
                "ciudad", "Las Palmas",
                "codigoPostal", "35001",
                "provincia", "Las Palmas",
                "pais", "ES");
    }
}
