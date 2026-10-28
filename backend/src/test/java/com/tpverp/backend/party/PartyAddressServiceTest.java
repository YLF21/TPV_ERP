package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartyAddressServiceTest {
    private HttpServer server;
    private URI endpoint;
    private final AtomicReference<String> body = new AtomicReference<>("[]");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            query.set(exchange.getRequestURI().getRawQuery());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Location", endpoint.toString());
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void filtersNonAddressAndForeignResultsAndEncodesOnlyTypedAddress() {
        body.set("""
                [{"id":12,"type":"portal","address":"Calle Niño 1","countryCode":"011"},
                 {"id":"b","type":"callejero","address":"MAYOR","countryCode":"ES"},
                 {"id":"c","type":"Codpost","address":"28013","countryCode":"011"},
                 {"id":"d","type":"portal","address":"foreign","countryCode":"FR"},
                 {"id":"e","type":"portal","address":"unknown"}]
                """);
        var result = service().suggestions("Niño & Sol?#");
        assertThat(result.available()).isTrue();
        assertThat(result.items()).containsExactly(
                new PartyAddressService.Suggestion("12", "portal", "Calle Niño 1"),
                new PartyAddressService.Suggestion("b", "callejero", "MAYOR"));
        assertThat(query.get()).startsWith("q=Ni%C3%B1o+%26+Sol%3F%23&");
        assertThat(URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                .contains("countrycodes=es", "no_process=municipio,provincia,").doesNotContain("filter", "Madrid");
        assertThat(authorization.get()).isNull();
    }

    @Test
    void shortQueriesDoNotUseNetworkAndSearchUsesOnlyTypedAddress() {
        assertThat(service().suggestions(" a ").items()).isEmpty();
        assertThat(calls.get()).isZero();
        service().suggestions("Calle, Toledo");
        assertThat(URLDecoder.decode(query.get(), StandardCharsets.UTF_8))
                .startsWith("q=Calle, Toledo&").doesNotContain("cod_postal_filter", "Madrid");
    }

    @Test
    void resolvesStructuredStreetAndReturnsEmptyMissingFields() {
        body.set("""
                {"address":"MAYOR","tip_via":"CALLE","portalNumber":1,"extension":"B",
                 "postalCode":"28013","poblacion":"Madrid","muni":"Madrid","province":"Madrid","countryCode":"011"}
                """);
        var resolved = service().resolve("a&/#?=niño", "portal");
        assertThat(resolved).isEqualTo(new PartyAddressService.Resolved(true,
                new PartyAddressService.Address("CALLE MAYOR 1B", "28013", "MADRID", "MADRID", "ES")));
        assertThat(query.get()).isEqualTo("id=a%26%2F%23%3F%3Dni%C3%B1o&type=portal");
        body.set("""
                {"address":"CALLE MAYOR","tip_via":"CALLE","portalNumber":9,"noNumber":true,"muni":"Madrid","countryCode":"ES"}
                """);
        assertThat(service().resolve("a", "callejero").address()).isEqualTo(
                new PartyAddressService.Address("CALLE MAYOR", "", "MADRID", "", "ES"));
    }

    @Test
    void usesMunicipalityBeforePopulationAndFallsBackOnlyWhenMunicipalityIsMissing() {
        body.set("""
                {"address":"Tunte","tip_via":"Calle","portalNumber":5,"postalCode":"35120",
                 "poblacion":"Los Canarios","muni":"Mogán","province":"Las Palmas","countryCode":"011"}
                """);
        assertThat(service().resolve("tunte-5", "portal").address()).isEqualTo(
                new PartyAddressService.Address("Calle Tunte 5", "35120", "MOGÁN", "LAS PALMAS", "ES"));

        body.set("""
                {"address":"Tunte","tip_via":"Calle","portalNumber":5,"poblacion":"Los Canarios",
                 "muni":"  ","countryCode":"011"}
                """);
        assertThat(service().resolve("tunte-5", "portal").address().city()).isEqualTo("LOS CANARIOS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "null", "malformed", "{\"address\":\"x\",\"countryCode\":\"FR\"}",
            "{\"address\":\"x\",\"countryCode\":\"011\",\"type\":\"Codpost\"}"})
    void rejectsInvalidResolutions(String payload) {
        body.set(payload);
        assertThat(service().resolve("1", "portal")).isEqualTo(new PartyAddressService.Resolved(false, null));
    }

    @Test
    void invalidTypeDoesNotReachRemoteAndMalformedSuggestionsAreUnavailable() {
        assertThat(service().resolve("1", "Codpost").available()).isFalse();
        assertThat(calls.get()).isZero();
        body.set("{}");
        assertThat(service().suggestions("calle").available()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 401, 500, 503})
    void upstreamFailuresDoNotFollowRedirects(int responseCode) {
        status.set(responseCode);
        assertThat(service().suggestions("calle").available()).isFalse();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsOversizedBodiesAndConnectionFailure() {
        body.set(" ".repeat(65536) + "[]");
        assertThat(service().suggestions("calle").available()).isFalse();
        server.stop(0);
        assertThat(service().suggestions("calle").available()).isFalse();
    }

    @Test
    void deadlineIncludesStalledResponseBody() throws Exception {
        server.removeContext("/");
        var release = new CountDownLatch(1);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            exchange.getResponseBody().write('[');
            exchange.getResponseBody().flush();
            try { release.await(); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        try {
            long start = System.nanoTime();
            assertThat(service(Duration.ofMillis(150)).suggestions("calle").available()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally { release.countDown(); }
    }

    private PartyAddressService service() { return service(Duration.ofSeconds(2)); }
    private PartyAddressService service(Duration timeout) {
        return new PartyAddressService(endpoint, new ObjectMapper(), HttpClient.newBuilder()
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build(), timeout);
    }
}
