package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocumentSyncRecoveryClientTest {
    final ObjectMapper json = new ObjectMapper();
    final UUID company = UUID.randomUUID(), store = UUID.randomUUID(), centralCompany = UUID.randomUUID(), centralStore = UUID.randomUUID();
    final UUID document = UUID.randomUUID(), event = UUID.randomUUID();
    final Scope scope = new Scope(company, store, null, null, null);
    final LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
    final SaasLicenseIdentityResolver identities = mock(SaasLicenseIdentityResolver.class);
    final AtomicReference<JsonNode> sent = new AtomicReference<>();
    final AtomicReference<String> receivedToken = new AtomicReference<>(), path = new AtomicReference<>(), body = new AtomicReference<>();
    final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
    HttpServer server;
    ObjectNode response;

    @BeforeEach void start() throws Exception {
        when(credentials.readToken()).thenReturn(Optional.of("synthetic-token"));
        when(identities.resolve(company, store)).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(centralCompany, centralStore));
        response = json.createObjectNode().put("companyId", centralCompany.toString()).put("storeId", centralStore.toString())
                .put("installationId", UUID.randomUUID().toString()).put("schemaVersion", 2);
        response.putArray("documents").addObject().put("documentId", document.toString()).put("status", "PROJECTED")
                .put("currentRevision", 9007199254740993L).put("currentEventId", event.toString())
                .put("requestedEventStatus", "PROJECTED").put("requestedRevisionRecorded", true)
                .put("customerLinked", true).put("total", "123.45").put("currency", "EUR");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); sent.set(json.readTree(exchange.getRequestBody()));
            path.set(exchange.getRequestURI().getPath()); receivedToken.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            byte[] bytes = (body.get() == null ? response.toString() : body.get()).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Location", "/redirect-must-not-be-followed");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void usesInstallationScopeAndPreservesExactRevisionWithoutDocumentMutationRequest() {
        assertThat(query()).singleElement().satisfies(row -> {
            assertThat(row.currentRevision()).isEqualTo(9007199254740993L);
            assertThat(row.requestedRevisionRecorded()).isTrue();
        });
        assertThat(path.get()).isEqualTo("/api/v1/commercial-document-queries/recovery-status");
        assertThat(receivedToken.get()).isEqualTo("synthetic-token");
        assertThat(sent.get().path("companyId").asText()).isEqualTo(centralCompany.toString());
        assertThat(sent.get().path("storeId").asText()).isEqualTo(centralStore.toString());
        assertThat(sent.get().at("/documents/0/sourceRevision").longValue()).isEqualTo(9007199254740993L);
        assertThat(sent.get().has("payload")).isFalse();
    }
    @ParameterizedTest @ValueSource(strings = {"companyId", "storeId", "installationId", "schemaVersion", "documents"})
    void rejectsInvalidEnvelope(String field) {
        response.put(field, "untrusted");
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE").hasNoCause();
    }
    @ParameterizedTest @ValueSource(strings = {"documentId", "status", "currentRevision", "currentEventId", "requestedEventStatus",
            "requestedRevisionRecorded", "customerLinked", "total", "currency"})
    void rejectsInvalidRowFields(String field) {
        ((ObjectNode) response.path("documents").get(0)).put(field, "INVALID_REMOTE_CONTENT_THAT_MUST_NOT_LEAK");
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE").hasNoCause();
    }
    @Test void rejectsFractionalAndNegativeRevision() {
        ObjectNode row = (ObjectNode) response.path("documents").get(0);
        row.put("currentRevision", 2.5);
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE");
        row.put("currentRevision", -1);
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE");
    }
    @Test void acceptsCentralRevisionZeroForConflictDiagnosisEvenThoughLocalReceiptsStartAtOne() {
        ((ObjectNode) response.path("documents").get(0)).put("currentRevision", 0);
        assertThat(query()).singleElement().satisfies(row -> assertThat(row.currentRevision()).isZero());
    }
    @Test void missingDataIsNotReportedAsProjected() {
        ObjectNode row = (ObjectNode) response.path("documents").get(0);
        row.put("status", "MISSING").put("requestedEventStatus", "MISSING").put("requestedRevisionRecorded", false);
        for (String field : List.of("currentRevision", "currentEventId", "customerLinked", "total", "currency")) row.putNull(field);
        assertThat(query().getFirst().status()).isEqualTo("MISSING");
    }
    @Test void missingTokenOrDisabledEndpointDoesNotConnect() {
        when(credentials.readToken()).thenReturn(Optional.empty());
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_UNAVAILABLE");
        assertThatThrownBy(() -> new DocumentSyncRecoveryClient("", credentials, identities, json, HttpClient.newHttpClient())
                .status(scope, List.of(new Expectation(document, null, null)))).hasMessage("DOCUMENT_RECOVERY_SAAS_UNAVAILABLE");
        assertThat(calls.get()).isZero();
    }
    @Test void configuredTechnicalClientCanInspectSaasWithoutEnablingTheOutboxScheduler() {
        var client = new DocumentSyncRecoveryClient("http://127.0.0.1:" + server.getAddress().getPort(),
                credentials, identities, json);
        assertThat(client.status(scope, List.of(new Expectation(document, event, 9007199254740993L))))
                .singleElement().satisfies(row -> assertThat(row.status()).isEqualTo("PROJECTED"));
        assertThat(calls.get()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(ints = {302, 401, 403, 409, 429, 500})
    void non200AndRedirectsAreSanitized(int code) {
        status.set(code); body.set("private backend details and tokens");
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_UNAVAILABLE").hasNoCause();
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rejectsMalformedAndOversizedBodies() {
        body.set("not json");
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE");
        body.set("x".repeat(150_000));
        assertThatThrownBy(this::query).hasMessage("DOCUMENT_RECOVERY_SAAS_INVALID_RESPONSE");
    }
    private List<RemoteRow> query() {
        return new DocumentSyncRecoveryClient("http://127.0.0.1:" + server.getAddress().getPort(), credentials, identities, json,
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build())
                .status(scope, List.of(new Expectation(document, event, 9007199254740993L)));
    }
}
