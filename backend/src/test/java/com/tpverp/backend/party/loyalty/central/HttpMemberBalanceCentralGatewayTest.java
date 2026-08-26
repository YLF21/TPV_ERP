package com.tpverp.backend.party.loyalty.central;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpMemberBalanceCentralGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void reservaConTokenEnElEndpointCentral() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        UUID reservationId = UUID.randomUUID();
        HttpServer server = server(201, reservationBody(reservationId), received, token);
        try {
            server.start();
            HttpMemberBalanceCentralGateway gateway = gateway(server, "token-local");
            UUID memberId = UUID.randomUUID();

            MemberBalanceCentralGateway.ReservationResponse response = gateway.reserve(
                    new MemberBalanceCentralGateway.ReserveRequest(
                            UUID.randomUUID(), UUID.randomUUID(), memberId, "terminal", "sale"));

            assertThat(response.reservationId()).isEqualTo(reservationId);
            assertThat(received.get().get("memberId").asText()).isEqualTo(memberId.toString());
            assertThat(token.get()).isEqualTo("token-local");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clasificaConflictoDeOtraCaja() throws Exception {
        HttpServer server = server(
                409,
                "{\"detail\":\"Saldo reservado en otra caja\"}",
                new AtomicReference<>(),
                new AtomicReference<>());
        try {
            server.start();
            HttpMemberBalanceCentralGateway gateway = gateway(server, "token-local");

            assertThatThrownBy(() -> gateway.reserve(new MemberBalanceCentralGateway.ReserveRequest(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "terminal", "sale")))
                    .isInstanceOfSatisfying(MemberBalanceCentralException.class, exception -> {
                        assertThat(exception.getKind()).isEqualTo(MemberBalanceCentralException.Kind.CONFLICT);
                        assertThat(exception.getStatusCode()).isEqualTo(409);
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallaCerradoSinTokenSaas() {
        LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
        when(credentials.readToken()).thenReturn(Optional.empty());
        HttpMemberBalanceCentralGateway gateway = new HttpMemberBalanceCentralGateway(
                URI.create("http://127.0.0.1:1"), credentials, mapper, HttpClient.newHttpClient());

        assertThatThrownBy(() -> gateway.reserve(new MemberBalanceCentralGateway.ReserveRequest(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "terminal", "sale")))
                .isInstanceOfSatisfying(MemberBalanceCentralException.class,
                        exception -> assertThat(exception.getKind())
                                .isEqualTo(MemberBalanceCentralException.Kind.UNAVAILABLE));
    }

    @Test
    void interpretaComoAusenteUnBootstrapDeCarteraNoEncontrado() throws Exception {
        HttpServer server = discoveryServer(
                "/api/v2/loyalty/member-wallet/bootstrap/discover", 404);
        try {
            server.start();
            HttpMemberBalanceCentralGateway gateway = gateway(server, "token-local");

            Optional<MemberBalanceCentralGateway.MemberWalletBootstrapStatus> result =
                    gateway.discoverBootstrap(
                            new MemberBalanceCentralGateway.BootstrapStoreRequest(
                                    UUID.randomUUID(), UUID.randomUUID()));

            assertThat(result).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void interpretaComoAusenteUnBootstrapDePuntosNoEncontrado() throws Exception {
        HttpServer server = discoveryServer(
                "/api/v2/loyalty/member-points/bootstrap/discover", 404);
        try {
            server.start();
            HttpMemberBalanceCentralGateway gateway = gateway(server, "token-local");

            Optional<MemberBalanceCentralGateway.PointsBootstrapStatus> result =
                    gateway.discoverPointsBootstrap(
                            new MemberBalanceCentralGateway.PointsBootstrapStoreRequest(
                                    UUID.randomUUID(), UUID.randomUUID()));

            assertThat(result).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private HttpMemberBalanceCentralGateway gateway(HttpServer server, String token) {
        LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
        when(credentials.readToken()).thenReturn(Optional.of(token));
        return new HttpMemberBalanceCentralGateway(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                credentials,
                mapper,
                HttpClient.newHttpClient());
    }

    private HttpServer server(
            int status,
            String responseBody,
            AtomicReference<JsonNode> received,
            AtomicReference<String> token) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/loyalty/member-wallet/reservations", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            byte[] body = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }

    private HttpServer discoveryServer(String path, int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return server;
    }

    private String reservationBody(UUID reservationId) {
        return """
                {
                  "reservationId":"%s",
                  "memberId":"00000000-0000-0000-0000-000000000001",
                  "status":"ACTIVE",
                  "reservedLoyaltyAmount":10.00,
                  "reservedReturnCreditAmount":0.00,
                  "preparedLoyaltyAmount":0.00,
                  "preparedReturnCreditAmount":0.00,
                  "prepareOperationId":null,
                  "consumedLoyaltyAmount":0.00,
                  "consumedReturnCreditAmount":0.00,
                  "accountLoyaltyBalance":10.00,
                  "accountReturnCreditBalance":0.00,
                  "reservedLots":[],
                  "heartbeatAt":"2026-08-18T12:00:00Z",
                  "leaseExpiresAt":"2026-08-18T12:02:00Z",
                  "heartbeatIntervalSeconds":30,
                  "leaseSeconds":120
                }
                """.formatted(reservationId);
    }
}
