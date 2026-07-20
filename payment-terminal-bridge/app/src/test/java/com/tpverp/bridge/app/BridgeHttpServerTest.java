package com.tpverp.bridge.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.bridge.spi.AdapterHealth;
import com.tpverp.bridge.spi.AdapterManifest;
import com.tpverp.bridge.spi.BridgeCapability;
import com.tpverp.bridge.spi.OperationRequest;
import com.tpverp.bridge.spi.OperationResult;
import com.tpverp.bridge.spi.PairingRequest;
import com.tpverp.bridge.spi.PaymentTerminalAdapter;
import com.tpverp.bridge.spi.TerminalProfile;
import com.tpverp.bridge.spi.TerminalExecutionMode;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BridgeHttpServerTest {
    private static final String TOKEN = "a-local-test-token-with-32-characters";
    @TempDir Path temporary;

    @Test
    void requiresAuthenticationAndImplementsTheBackendWireContract() throws Exception {
        var mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        var profile = new TerminalProfile("terminal-1", "GLOBAL_PAYMENTS", "globalpayments-sdk", TerminalExecutionMode.LIVE, "DX8000", "TCP_IP",
                "windows:gp-merchant", Map.of("ip", "127.0.0.1"));
        var service = new BridgeService(new TerminalProfileRegistry(java.util.List.of(profile)),
                AdapterRegistry.of(new Adapter()), new FileIdempotencyStore(temporary, mapper));
        try (var server = new BridgeHttpServer("127.0.0.1", 0, TOKEN, service, mapper)) {
            server.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

            var unauthorized = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(unauthorized.statusCode()).isEqualTo(401);

            var health = client.send(get(server, "/health"), HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"available\":true", "\"code\":\"OK\"");

            var capabilities = client.send(get(server, "/capabilities?provider=GLOBAL_PAYMENTS&mode=LIVE"), HttpResponse.BodyHandlers.ofString());
            assertThat(capabilities.body()).contains("HEALTH", "CHARGE", "QUERY");

            var adapters = client.send(get(server, "/adapters"), HttpResponse.BodyHandlers.ofString());
            assertThat(adapters.statusCode()).isEqualTo(200);
            assertThat(adapters.body()).contains("globalpayments-sdk", "certifiedLiveDriverInstalled", "LIVE");

            var operation = """
                    {"provider":"GLOBAL_PAYMENTS","terminalId":"terminal-1","mode":"LIVE","operationId":"operation-1",
                     "idempotencyKey":"stable-key","command":"CHARGE","amountMinor":514,"currency":"EUR",
                     "originalOperationId":null,"reference":null,"configurationReference":"config-1",
                     "configurationVersion":1,"parameters":{}}
                    """;
            var response = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/operation"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(operation)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"approved\":true", "\"code\":\"APPROVED\"", "\"reference\":\"GP-1\"");

            var malformed = client.send(HttpRequest.newBuilder(server.baseUri().resolve("/operation"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{invalid-json")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(malformed.statusCode()).isEqualTo(400);
            assertThat(malformed.body()).contains("INVALID_REQUEST");
        }
    }

    @Test
    void refusesWeakBridgeTokens() throws Exception {
        var mapper = new ObjectMapper();
        var service = new BridgeService(new TerminalProfileRegistry(java.util.List.of()), AdapterRegistry.of(),
                new FileIdempotencyStore(temporary, mapper));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new BridgeHttpServer("127.0.0.1", 0, "short", service, mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static HttpRequest get(BridgeHttpServer server, String path) {
        return HttpRequest.newBuilder(server.baseUri().resolve(path)).header("Authorization", "Bearer " + TOKEN).GET().build();
    }

    private static final class Adapter implements PaymentTerminalAdapter {
        @Override public String adapterId() { return "globalpayments-sdk"; }
        @Override public String provider() { return "GLOBAL_PAYMENTS"; }
        @Override public AdapterManifest manifest() {
            return new AdapterManifest(adapterId(), provider(), "Global Payments test", Set.of(TerminalExecutionMode.LIVE),
                    Set.of("TEST"), Set.of("TCP_IP"), true);
        }
        @Override public boolean supports(TerminalProfile profile) { return "DX8000".equals(profile.model()); }
        @Override public Set<BridgeCapability> capabilities(TerminalProfile profile) {
            return Set.of(BridgeCapability.CHARGE, BridgeCapability.QUERY);
        }
        @Override public AdapterHealth health(TerminalProfile profile) { return new AdapterHealth(true, "OK", "test"); }
        @Override public OperationResult pair(PairingRequest request, TerminalProfile profile) {
            return new OperationResult(true, "PAIRED", "PAIR-1", null, "OK", null);
        }
        @Override public OperationResult operate(OperationRequest request, TerminalProfile profile) {
            return new OperationResult(true, "APPROVED", "GP-1", "AUTH-1", "Aprobada", null);
        }
    }
}
