package com.tpverp.backend.connectivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SaasConnectivityServiceTest {
    private static final String VALID_BODY = "{\"service\":\"tpv-erp-saas\",\"status\":\"UP\"}";
    private final AtomicReference<String> body = new AtomicReference<>(VALID_BODY);
    private final AtomicInteger responseCode = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-19T12:00:00Z");
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        when(clock.instant()).thenReturn(now);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/connectivity/ping", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set("Location", baseUrl + "/api/v1/connectivity/ping");
            exchange.sendResponseHeaders(responseCode.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void probesSaasAndSharesCachedResultAcrossConcurrentTerminalsThenDetectsLossAndRecovery() throws Exception {
        var service = service(baseUrl, Duration.ofSeconds(2));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var requests = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(ignored -> executor.submit(service::status)).toList();
            for (var request : requests) {
                assertThat(request.get().saasConnected()).isTrue();
            }
        }
        assertThat(calls.get()).isEqualTo(1);
        assertThat(authorization.get()).isNull();
        responseCode.set(503);
        when(clock.instant()).thenReturn(now.plusSeconds(19));
        assertThat(service.status().saasConnected()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        when(clock.instant()).thenReturn(now.plusSeconds(20));
        assertThat(service.status().saasConnected()).isFalse();
        assertThat(service.status().checkedAt()).isEqualTo(now.plusSeconds(20));
        assertThat(calls.get()).isEqualTo(2);
        responseCode.set(200);
        when(clock.instant()).thenReturn(now.plusSeconds(40));
        assertThat(service.status().saasConnected()).isTrue();
        assertThat(calls.get()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<html>Gateway page</html>", "{}", "null", "[]",
            "{\"service\":\"other\",\"status\":\"UP\"}",
            "{\"service\":\"tpv-erp-saas\",\"status\":\"DOWN\"}"})
    void rejectsInvalidResponses(String response) {
        body.set(response);
        assertThat(service(baseUrl, Duration.ofSeconds(2)).status().saasConnected()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 403, 404, 500, 503})
    void rejectsHttpFailuresAndDoesNotFollowRedirects(int code) {
        responseCode.set(code);
        assertThat(service(baseUrl, Duration.ofSeconds(2)).status().saasConnected()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void limitsTheBodyAndTreatsClosedConnectionAsOffline() {
        body.set(" ".repeat(5000) + VALID_BODY);
        assertThat(service(baseUrl, Duration.ofSeconds(2)).status().saasConnected()).isFalse();
        server.stop(0);
        assertThat(service(baseUrl, Duration.ofSeconds(2)).status().saasConnected()).isFalse();
    }

    @Test
    void boundsTheWholeRequestEvenWhenTheResponseBodyStalls() throws Exception {
        server.removeContext("/api/v1/connectivity/ping");
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext("/api/v1/connectivity/ping", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try {
            long started = System.nanoTime();
            assertThat(service(baseUrl, Duration.ofMillis(150)).status().saasConnected()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidOrMissingConfigurationNeverMakesANetworkRequest() {
        for (String url : List.of("", " ", "file:///tmp/saas", "http://user:password@127.0.0.1",
                baseUrl + "?secret=x", "invalid url")) {
            assertThat(service(url, Duration.ofSeconds(2)).status().saasConnected()).isFalse();
        }
        assertThat(calls.get()).isZero();
    }

    private SaasConnectivityService service(String url, Duration timeout) {
        return new SaasConnectivityService(url, new ObjectMapper(), clock,
                HttpClient.newBuilder().connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }
}
