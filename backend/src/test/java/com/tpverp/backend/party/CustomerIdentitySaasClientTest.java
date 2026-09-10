package com.tpverp.backend.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CustomerIdentitySaasClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
    private final SaasLicenseIdentityResolver identities = mock(SaasLicenseIdentityResolver.class);
    private final UUID company = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID centralCompany = UUID.randomUUID();
    private final UUID centralStore = UUID.randomUUID();
    private final UUID centralCustomer = UUID.randomUUID();
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        when(credentials.readToken()).thenReturn(Optional.of("synthetic-installation-token"));
        when(identities.resolve(company, store)).thenReturn(
                new SaasLicenseIdentityResolver.SaasIdentity(centralCompany, centralStore));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            received.set(mapper.readTree(exchange.getRequestBody()));
            receivedPath.set(exchange.getRequestURI().getPath());
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), status.get() == 204 ? -1 : response.length);
            if (status.get() != 204) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void sendsStableIdentityAndProfileUsingInstallationScopedCentralIds() throws Exception {
        var operation = operation(null);
        body.set(mapper.writeValueAsString(response(operation, 1L)));

        var result = client().reserve(operation, profile());

        assertThat(result.customerId()).isEqualTo(centralCustomer);
        assertThat(result.revision()).isEqualTo(1);
        assertThat(receivedPath.get()).isEqualTo("/api/v1/customer-identities/reservations");
        assertThat(receivedToken.get()).isEqualTo("synthetic-installation-token");
        assertThat(received.get().path("companyId").asText()).isEqualTo(centralCompany.toString());
        assertThat(received.get().path("storeId").asText()).isEqualTo(centralStore.toString());
        assertThat(received.get().path("localCustomerId").asText()).isEqualTo(operation.customerId().toString());
        assertThat(received.get().path("operationId").asText()).isEqualTo(operation.operationId().toString());
        assertThat(received.get().path("documentNumber").asText()).isEqualTo("00000001R");
        assertThat(received.get().path("profile")).isEqualTo(mapper.valueToTree(profile()));
    }

    @Test
    void acceptsOnlyTheNextRevisionOfAnExistingCentralCustomer() throws Exception {
        var operation = operation(3L);
        body.set(mapper.writeValueAsString(response(operation, 4L)));
        assertThat(client().reserve(operation, profile()).revision()).isEqualTo(4L);
        assertThat(received.get().path("expectedRevision").asLong()).isEqualTo(3);
        assertThat(received.get().path("expectedCustomerId").asText()).isEqualTo(centralCustomer.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"operationId", "customerId", "revision", "documentType", "documentNumber"})
    void missingRequiredResponseFieldFailsClosed(String missing) throws Exception {
        var operation = operation(null);
        var response = response(operation, 1L);
        response.remove(missing);
        body.set(mapper.writeValueAsString(response));
        unavailable(() -> client().reserve(operation, profile()));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 2, 3, 5})
    void rejectsStaleOrSkippedRevisions(long revision) throws Exception {
        var operation = operation(3L);
        body.set(mapper.writeValueAsString(response(operation, revision)));
        unavailable(() -> client().reserve(operation, profile()));
    }

    @Test
    void rejectsAResponseForAnotherCustomerEvenWhenIdentityMatches() throws Exception {
        var operation = operation(3L);
        var response = response(operation, 4L);
        response.put("customerId", UUID.randomUUID());
        body.set(mapper.writeValueAsString(response));
        unavailable(() -> client().reserve(operation, profile()));
    }

    @ParameterizedTest
    @CsvSource({"409,CUSTOMER_DOCUMENT_DUPLICATE", "409,CUSTOMER_IDENTITY_CONFLICT", "422,CUSTOMER_DOCUMENT_INVALID"})
    void preservesOnlyApprovedErrorCodesAndNeverUpstreamDetails(int httpStatus, String code) {
        status.set(httpStatus);
        body.set("{\"code\":\"" + code + "\",\"detail\":\"private upstream data\"}");
        assertThatThrownBy(() -> client().reserve(operation(null), profile()))
                .isInstanceOf(CustomerIdentityException.class).hasMessage(code).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void treatsUnavailableOrUnauthorizedCentralResponsesAsBlocked(int httpStatus) {
        status.set(httpStatus);
        body.set("{\"detail\":\"private upstream data\"}");
        unavailable(() -> client().reserve(operation(null), profile()));
    }

    @Test
    void blocksMissingCredentialsWithoutSendingAnyRequest() {
        when(credentials.readToken()).thenReturn(Optional.empty());
        unavailable(() -> client().reserve(operation(null), profile()));
        assertThat(calls.get()).isZero();
    }

    @Test
    void blocksWhenBackgroundSynchronizationIsDisabled() {
        var client = new CustomerIdentitySaasClient(url(), false, credentials, identities, mapper);
        unavailable(() -> client.reserve(operation(null), profile()));
        assertThat(calls.get()).isZero();
    }

    @Test
    void cancelUsesTheSameStableOwnerWithoutExposingProfileData() {
        status.set(204);
        var operation = operation(null);
        client().cancel(operation);
        assertThat(receivedPath.get()).endsWith("/" + operation.operationId() + "/cancel");
        assertThat(received.get().size()).isEqualTo(3);
        assertThat(received.get().path("localCustomerId").asText()).isEqualTo(operation.customerId().toString());
    }

    private CustomerIdentitySaasClient client() {
        return new CustomerIdentitySaasClient(url(), credentials, identities, mapper, HttpClient.newHttpClient());
    }
    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private CustomerIdentityOperations.Operation operation(Long expectedRevision) {
        return new CustomerIdentityOperations.Operation(UUID.randomUUID(), company, store, UUID.randomUUID(),
                expectedRevision == null, DocumentType.DNI, "00000001R",
                expectedRevision == null ? null : centralCustomer, expectedRevision, "PENDING");
    }
    private LinkedHashMap<String, Object> response(CustomerIdentityOperations.Operation operation, Long revision) {
        var result = new LinkedHashMap<String, Object>();
        result.put("operationId", operation.operationId());
        result.put("customerId", centralCustomer);
        result.put("revision", revision);
        result.put("documentType", operation.documentType().name());
        result.put("documentNumber", operation.documentNumber());
        return result;
    }
    private Map<String, Object> profile() {
        return Map.of("clientId", "C-001-000001", "fiscalName", "Synthetic customer", "address", Map.of());
    }
    private static void unavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(CustomerIdentityException.class)
                .hasMessage("CUSTOMER_IDENTITY_SAAS_UNAVAILABLE").hasNoCause();
    }
}
