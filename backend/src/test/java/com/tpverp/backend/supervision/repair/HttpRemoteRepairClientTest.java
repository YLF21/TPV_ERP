package com.tpverp.backend.supervision.repair;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class HttpRemoteRepairClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> response = new AtomicReference<>("[]");
    private final AtomicReference<Integer> responseCode = new AtomicReference<>(200);
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private final UUID installation = UUID.randomUUID();
    private HttpServer server;
    private HttpRemoteRepairClient client;
    private LicenseSaasCredentialStore credentials;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/sync/repairs", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            receivedPath.set(exchange.getRequestURI().getPath());
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseCode.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        credentials = mock(LicenseSaasCredentialStore.class);
        when(credentials.readToken()).thenReturn(Optional.of("synthetic-installation-token"));
        client = new HttpRemoteRepairClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), credentials, mapper);
    }
    @AfterEach void cleanup() { server.stop(0); }

    @Test void actualHttpContractUsesInstallationAuthenticationAndExportsTheActualResultBody() throws Exception {
        UUID commandId = UUID.randomUUID(); UUID eventId = UUID.randomUUID();
        response.set(mapper.writeValueAsString(List.of(payload(commandId, eventId))));
        var commands = client.claim(installation);
        assertThat(commands).hasSize(1);
        assertThat(commands.getFirst().commandId()).isEqualTo(commandId);
        assertThat(commands.getFirst().eventId()).isEqualTo(eventId);
        assertThat(commands.getFirst().expectedVersion()).isEqualTo(7);
        assertThat(received.get().size()).isEqualTo(1);
        assertThat(received.get().get("installationId").textValue()).isEqualTo(installation.toString());
        assertThat(receivedPath.get()).isEqualTo("/api/v1/sync/repairs/claim");
        assertThat(token.get()).isEqualTo("synthetic-installation-token");
        response.set(mapper.writeValueAsString(Map.of("commandId", commandId, "status", "EXPIRED", "resultCode", "REPAIR_EXPIRED")));
        client.report(new RemoteRepairResult(commandId, installation, "SUCCEEDED", "SYNC_DELIVERED"));
        assertThat(receivedPath.get()).isEqualTo("/api/v1/sync/repairs/" + commandId + "/result");
        assertThat(received.get().size()).isEqualTo(3);
        assertThat(received.get().get("status").textValue()).isEqualTo("SUCCEEDED");
        assertThat(received.get().get("resultCode").textValue()).isEqualTo("SYNC_DELIVERED");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/phase2-local-repair-result.json"), mapper.writeValueAsString(received.get()));
    }

    @Test void rejectsInvalidOrOversizedBatchesWithoutLeakingResponseContentsAndAcceptsUnknownActionForSafeLocalFailure() throws Exception {
        for (String invalid : List.of("{}", "null", "[{}]", "[" + "{},".repeat(10) + "{}]", "x".repeat(65537))) {
            response.set(invalid);
            assertThatThrownBy(() -> client.claim(installation)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE").hasNoCause();
        }
        var unknown = new java.util.LinkedHashMap<>(payload(UUID.randomUUID(), UUID.randomUUID()));
        unknown.put("action", "EXECUTE_SQL");
        response.set(mapper.writeValueAsString(List.of(unknown)));
        assertThat(client.claim(installation).getFirst().action()).isEqualTo("EXECUTE_SQL");
        unknown.put("expectedVersion", 1.5);
        response.set(mapper.writeValueAsString(List.of(unknown)));
        assertThatThrownBy(() -> client.claim(installation)).isInstanceOf(IllegalStateException.class);
    }

    @Test void failedReportMustNotBeAcknowledgedAndMissingCredentialsPreventAnyRequest() {
        response.set("private central error token"); responseCode.set(503);
        assertThatThrownBy(() -> client.report(new RemoteRepairResult(UUID.randomUUID(), installation, "RUNNING", "RETRY_QUEUED")))
                .hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE").hasNoCause();
        received.set(null);
        when(credentials.readToken()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> client.claim(installation)).hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE");
        assertThat(received.get()).isNull();
    }

    @Test void onlyMatchingStructuredAcknowledgementCanRetireADurableReceipt() throws Exception {
        var result = new RemoteRepairResult(UUID.randomUUID(), installation, "SUCCEEDED", "SYNC_DELIVERED");
        for (String invalid : List.of("", "<html>proxy fallback</html>", "null", "[]", "{}",
                mapper.writeValueAsString(Map.of("commandId", UUID.randomUUID(), "status", result.status(), "resultCode", result.resultCode())),
                mapper.writeValueAsString(Map.of("commandId", result.commandId(), "status", "RUNNING", "resultCode", "RETRY_QUEUED")),
                mapper.writeValueAsString(Map.of("commandId", result.commandId(), "status", "EXPIRED", "resultCode", "RETRY_FAILED")))) {
            response.set(invalid);
            assertThatThrownBy(() -> client.report(result)).hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE").hasNoCause();
        }
        responseCode.set(204);
        response.set("");
        assertThatThrownBy(() -> client.report(result)).hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE").hasNoCause();
        responseCode.set(200);
        response.set(mapper.writeValueAsString(Map.of("commandId", result.commandId(), "status", result.status(),
                "resultCode", result.resultCode(), "additionalCentralField", true)));
        assertThatCode(() -> client.report(result)).doesNotThrowAnyException();
        response.set(mapper.writeValueAsString(Map.of("commandId", result.commandId(), "status", "EXPIRED", "resultCode", "REPAIR_EXPIRED")));
        assertThatCode(() -> client.report(result)).doesNotThrowAnyException();
    }

    @Test void requestTimeoutIsFiveSeconds() throws Exception {
        HttpClient transport = mock(HttpClient.class);
        when(transport.send(any(), any())).thenThrow(new java.io.IOException("private token"));
        var bounded = new HttpRemoteRepairClient(URI.create("http://localhost:1"), credentials, mapper, transport);
        assertThatThrownBy(() -> bounded.claim(installation)).hasMessage("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE").hasNoCause();
        var request = org.mockito.ArgumentCaptor.forClass(java.net.http.HttpRequest.class);
        verify(transport).send(request.capture(), any());
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
    }

    @Test @EnabledIfEnvironmentVariable(named = "TPV_PHASE2_CENTRAL_CLAIM", matches = ".+")
    void consumesRealCentralClaimFixtureThroughActualHttpParsing() throws Exception {
        response.set(Files.readString(Path.of(System.getenv("TPV_PHASE2_CENTRAL_CLAIM"))));
        var commands = client.claim(installation);
        assertThat(commands).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(commands.getFirst().action()).isEqualTo("RETRY_SYNC_OUTBOX");
        assertThat(commands.getFirst().companyId()).isNotNull();
        assertThat(commands.getFirst().storeId()).isNotNull();
    }

    private Map<String, Object> payload(UUID commandId, UUID eventId) {
        return Map.of("commandId", commandId, "companyId", UUID.randomUUID(), "storeId", UUID.randomUUID(),
                "action", "RETRY_SYNC_OUTBOX", "eventId", eventId, "expectedVersion", 7,
                "expiresAt", Instant.parse("2026-09-22T12:15:00Z").toString());
    }
}
