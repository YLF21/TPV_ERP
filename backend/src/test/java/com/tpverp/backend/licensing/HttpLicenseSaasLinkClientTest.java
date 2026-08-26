package com.tpverp.backend.licensing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.application.CommercialProfile;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import com.tpverp.backend.licensing.application.TaxRegime;
import com.tpverp.backend.licensing.application.TaxpayerType;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpLicenseSaasLinkClientTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void enviaCodigoTemporalYDevuelveLicenciaSaas() throws Exception {
        var received = new AtomicReference<JsonNode>();
        var receivedToken = new AtomicReference<String>();
        var receivedRecoveryToken = new AtomicReference<String>();
        HttpServer server = server(
                200, response(), received, receivedToken, receivedRecoveryToken);
        try {
            server.start();
            var credentials = credentials("token-previo", "recovery-token");
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials,
                    mapper,
                    HttpClient.newHttpClient());

            LicenseSaasLinkResponse result = client.link(request(), "recovery-token");

            assertThat(received.get().get("pairingCode").asText()).isEqualTo("ABC123");
            assertThat(received.get().get("installationReference").asText()).isEqualTo("INST-1");
            assertThat(received.get().get("timeZoneId").asText()).isEqualTo("Atlantic/Canary");
            assertThat(receivedToken.get()).isEqualTo("token-previo");
            assertThat(receivedRecoveryToken.get()).isEqualTo("recovery-token");
            assertThat(result.licenseReference()).isEqualTo("LIC-SAAS-1");
            assertThat(result.companyTaxId()).isEqualTo("B12345674");
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
        HttpServer server = server(
                409, "{\"detail\":\"La instalacion conserva otra credencial\"}",
                new AtomicReference<>(), new AtomicReference<>(),
                new AtomicReference<>());
        try {
            server.start();
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials(null, "recovery-token"),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.link(request(), "recovery-token"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("SaaS respondio 409: La instalacion conserva otra credencial");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void noExponeCuerposRemotosNoEstructurados() throws Exception {
        HttpServer server = server(
                502, "<html>proxy-interno</html>", new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        try {
            server.start();
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials(null, "recovery-token"),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.link(request(), "recovery-token"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("SaaS respondio 502")
                    .hasMessageNotContaining("proxy-interno");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rechazaRespuestaActualSinPerfilComercial() throws Exception {
        JsonNode incomplete = mapper.readTree(response());
        ((com.fasterxml.jackson.databind.node.ObjectNode) incomplete).remove("commercialProfile");
        HttpServer server = server(
                200, mapper.writeValueAsString(incomplete), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
        try {
            server.start();
            var client = new HttpLicenseSaasLinkClient(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials(null, "recovery-token"),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> client.link(request(), "recovery-token"))
                    .isInstanceOf(LicenseValidationException.class)
                    .hasMessageContaining("commercialProfile");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(
            int status,
            String response,
            AtomicReference<JsonNode> received,
            AtomicReference<String> receivedToken,
            AtomicReference<String> receivedRecoveryToken)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/license/link", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst(
                    "X-TPV-Installation-Token"));
            receivedRecoveryToken.set(exchange.getRequestHeaders().getFirst(
                    "X-TPV-Link-Recovery-Token"));
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
                UUID.randomUUID(),
                "001",
                "B12345674",
                "Empresa",
                null,
                null,
                "Atlantic/Canary");
    }

    private String response() throws Exception {
        return mapper.writeValueAsString(new LicenseSaasLinkResponse(
                "LIC-SAAS-1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "B12345674",
                "EMPRESA REAL",
                address(),
                "001",
                "TIENDA 001",
                address(),
                "Atlantic/Canary",
                Instant.parse("2027-08-10T00:00:00Z"),
                LicenseSaasStatus.VALIDA,
                2,
                1,
                4,
                "B12345674",
                TaxpayerType.SOCIEDAD,
                TaxRegime.IGIC,
                CommercialProfile.MAYORISTA,
                java.time.LocalDate.of(2027, 1, 1),
                3,
                Instant.parse("2026-07-22T10:00:00Z"),
                "token"));
    }

    private LicenseSaasCredentialStore credentials(String token, String recoveryToken) {
        var credentials = mock(LicenseSaasCredentialStore.class);
        when(credentials.readToken()).thenReturn(Optional.ofNullable(token));
        when(credentials.readLinkRecoveryToken()).thenReturn(Optional.ofNullable(recoveryToken));
        return credentials;
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
