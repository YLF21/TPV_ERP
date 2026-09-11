package com.tpverp.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SaasCustomerDocumentClientTest {
    final ObjectMapper mapper = new ObjectMapper();
    final LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
    final SaasLicenseIdentityResolver identities = mock(SaasLicenseIdentityResolver.class);
    final UUID company = UUID.randomUUID(), store = UUID.randomUUID(), centralCompany = UUID.randomUUID(), centralStore = UUID.randomUUID();
    final UUID customer = UUID.randomUUID(), centralCustomer = UUID.randomUUID();
    final AtomicReference<String> body = new AtomicReference<>(), token = new AtomicReference<>(), path = new AtomicReference<>();
    final AtomicReference<JsonNode> request = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
    HttpServer server;

    @BeforeEach void start() throws Exception {
        when(credentials.readToken()).thenReturn(Optional.of("synthetic-installation-token"));
        when(identities.resolve(company, store)).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(centralCompany, centralStore));
        body.set(mapper.writeValueAsString(Map.of("companyId", centralCompany, "customer", Map.of("id", centralCustomer),
                "coverage", SaasCustomerDocumentApi.COVERAGE)));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); request.set(mapper.readTree(exchange.getRequestBody()));
            path.set(exchange.getRequestURI().getPath()); token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void scopeComesFromVerifiedLicenseAndBodyContainsBothCustomerIdentities() {
        var value = client().query("export", company, store, customer, centralCustomer,
                Map.of("companyId", UUID.randomUUID(), "storeId", UUID.randomUUID(), "reportKey", "invoices"));
        assertThat(value.path("companyId").asText()).isEqualTo(centralCompany.toString());
        assertThat(request.get().path("companyId").asText()).isEqualTo(centralCompany.toString());
        assertThat(request.get().path("storeId").asText()).isEqualTo(centralStore.toString());
        assertThat(request.get().path("localCustomerId").asText()).isEqualTo(customer.toString());
        assertThat(request.get().path("expectedCustomerId").asText()).isEqualTo(centralCustomer.toString());
        assertThat(path.get()).isEqualTo("/api/v1/commercial-document-queries/export");
        assertThat(token.get()).isEqualTo("synthetic-installation-token");
        assertThat(calls).hasValue(1);
    }
    @Test void missingTokenAndLicenseDoNotIssueAnUnauthenticatedRequest() {
        when(credentials.readToken()).thenReturn(Optional.empty());
        assertThatThrownBy(this::query).hasMessage("SAAS_CUSTOMER_DOCUMENTS_UNAVAILABLE").hasNoCause();
        verify(identities).resolve(company, store);
        assertThat(calls).hasValue(0);
    }
    @Test void rejectsWrongCompanyCustomerAndCoverageWithoutLeakingTheirContents() throws Exception {
        for (var response : List.of(
                Map.of("companyId", UUID.randomUUID(), "customer", Map.of("id", centralCustomer), "coverage", SaasCustomerDocumentApi.COVERAGE),
                Map.of("companyId", centralCompany, "customer", Map.of("id", UUID.randomUUID()), "coverage", SaasCustomerDocumentApi.COVERAGE),
                Map.of("companyId", centralCompany, "customer", Map.of("id", centralCustomer), "coverage", "COMPLETE"))) {
            body.set(mapper.writeValueAsString(response));
            assertThatThrownBy(this::query).hasMessage("SAAS_CUSTOMER_DOCUMENTS_INVALID_RESPONSE").hasNoCause();
        }
    }
    @ParameterizedTest
    @CsvSource({"409,SAAS_CUSTOMER_BINDING_REQUIRED,409", "409,CUSTOMER_DOCUMENT_SELECTION_UNAVAILABLE,409",
            "413,customer_documents_export_limit_exceeded,422"})
    void preservesOnlyApprovedCodesAndMapsExportLimitToLocal422(int upstream, String code, int expected) {
        status.set(upstream); body.set("{\"code\":\"" + code + "\",\"detail\":\"private token and profile\"}");
        assertThatThrownBy(this::query).isInstanceOfSatisfying(SaasCustomerDocumentException.class,
                error -> assertThat(error.status().value()).isEqualTo(expected)).hasMessage(code).hasNoCause();
    }
    @Test void unknownFailuresAreSanitizedWithoutUpstreamBodyOrNestedCause() {
        status.set(409); body.set("{\"code\":\"unknown\",\"detail\":\"private data\"}");
        assertThatThrownBy(this::query).hasMessage("SAAS_CUSTOMER_DOCUMENTS_UNAVAILABLE").hasNoCause();
    }
    @Test void boundedSubscriberCancelsBeforeBufferingAnOversizedBody() {
        var subscriber = new SaasCustomerDocumentClient.BoundedBody(4);
        var subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{1, 2, 3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[]{4, 5})));
        verify(subscription).cancel();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(SaasCustomerDocumentException.class);
    }
    private JsonNode query() { return client().query("page", company, store, customer, centralCustomer, Map.of("reportKey", "tickets")); }
    private SaasCustomerDocumentClient client() {
        return new SaasCustomerDocumentClient("http://127.0.0.1:" + server.getAddress().getPort(), credentials, identities, mapper,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }
}
