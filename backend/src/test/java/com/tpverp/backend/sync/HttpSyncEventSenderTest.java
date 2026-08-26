package com.tpverp.backend.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;

class HttpSyncEventSenderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enviaEventoAlEndpointCentral() throws Exception {
        var received = new AtomicReference<JsonNode>();
        HttpServer server = server(200, received);
        try {
            server.start();
            var event = event();
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            sender.send(event);

            assertThat(received.get().get("eventId").asText()).isEqualTo(event.getEventId().toString());
            assertThat(received.get().get("companyId").asText()).isEqualTo(event.getCompanyId().toString());
            assertThat(received.get().get("entityType").asText()).isEqualTo("DOCUMENTO");
            assertThat(received.get().get("operation").asText()).isEqualTo("CONFIRMAR");
            assertThat(received.get().get("payload").get("numero").asText()).isEqualTo("T-1");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rechazaRespuestaNoExitosa() throws Exception {
        HttpServer server = server(503, new AtomicReference<>());
        try {
            server.start();
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            assertThatThrownBy(() -> sender.send(event()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("503");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enviaEventoConTiendaYTerminalOpcionales() throws Exception {
        var received = new AtomicReference<JsonNode>();
        HttpServer server = server(200, received);
        try {
            server.start();
            var event = new SyncOutboxEvent(
                    UUID.randomUUID(),
                    null,
                    null,
                    "DOCUMENTO",
                    UUID.randomUUID(),
                    SyncOperation.ANULAR,
                    Map.of(),
                    Instant.parse("2026-06-30T12:00:00Z"));
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            sender.send(event);

            assertThat(received.get().get("storeId").isNull()).isTrue();
            assertThat(received.get().get("terminalId").isNull()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enviaTokenDeInstalacionCuandoExiste() throws Exception {
        var token = new AtomicReference<String>();
        HttpServer server = server(200, new AtomicReference<>(), token);
        try {
            server.start();
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials("token-local"),
                    mapper,
                    HttpClient.newHttpClient());

            sender.send(event());

            assertThat(token.get()).isEqualTo("token-local");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void conservaCamposJsonNulosAlEnviarElEvento() throws Exception {
        var received = new AtomicReference<JsonNode>();
        HttpServer server = server(200, received);
        try {
            server.start();
            var payload = new LinkedHashMap<String, Object>();
            payload.put("modeSince", null);
            payload.put("effectiveMode", "VERIFACTU");
            var event = new SyncOutboxEvent(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "FISCAL_STATUS",
                    UUID.randomUUID(),
                    SyncOperation.ACTUALIZAR,
                    payload,
                    Instant.parse("2026-08-25T12:00:00Z"));
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    mapper,
                    HttpClient.newHttpClient());

            sender.send(event);

            assertThat(received.get().get("payload").has("modeSince")).isTrue();
            assertThat(received.get().get("payload").get("modeSince").isNull()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enviaLaIdentidadCentralAsignadaPorLaLicencia() throws Exception {
        var received = new AtomicReference<JsonNode>();
        HttpServer server = server(200, received);
        try {
            server.start();
            UUID centralCompanyId = UUID.randomUUID();
            UUID centralStoreId = UUID.randomUUID();
            UUID localCompanyId = UUID.randomUUID();
            UUID localStoreId = UUID.randomUUID();
            var identities = org.mockito.Mockito.mock(SaasLicenseIdentityResolver.class);
            org.mockito.Mockito.when(identities.resolve(
                            org.mockito.ArgumentMatchers.any(),
                            org.mockito.ArgumentMatchers.any()))
                    .thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(
                            centralCompanyId, centralStoreId));
            var sender = new HttpSyncEventSender(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials("token-local"),
                    identities,
                    mapper,
                    HttpClient.newHttpClient());

            sender.send(new SyncOutboxEvent(
                    localCompanyId,
                    localStoreId,
                    null,
                    "FISCAL_STATUS",
                    UUID.randomUUID(),
                    SyncOperation.ACTUALIZAR,
                    Map.of("companyId", localCompanyId, "storeId", localStoreId),
                    Instant.parse("2026-08-25T12:00:00Z")));

            assertThat(received.get().get("companyId").asText())
                    .isEqualTo(centralCompanyId.toString());
            assertThat(received.get().get("storeId").asText())
                    .isEqualTo(centralStoreId.toString());
            assertThat(received.get().get("payload").get("companyId").asText())
                    .isEqualTo(centralCompanyId.toString());
            assertThat(received.get().get("payload").get("storeId").asText())
                    .isEqualTo(centralStoreId.toString());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(int status, AtomicReference<JsonNode> received) throws Exception {
        return server(status, received, new AtomicReference<>());
    }

    private HttpServer server(
            int status,
            AtomicReference<JsonNode> received,
            AtomicReference<String> token) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sync/events", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        return server;
    }

    private static SyncOutboxEvent event() {
        return new SyncOutboxEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "DOCUMENTO",
                UUID.randomUUID(),
                SyncOperation.CONFIRMAR,
                Map.of("numero", "T-1"),
                Instant.parse("2026-06-30T12:00:00Z"));
    }

    private static LicenseSaasCredentialStore credentials(String token) {
        var credentials = org.mockito.Mockito.mock(LicenseSaasCredentialStore.class);
        org.mockito.Mockito.when(credentials.readToken()).thenReturn(Optional.of(token));
        return credentials;
    }
}
