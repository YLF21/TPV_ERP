package com.tpverp.backend.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.tpverp.backend.inventory.SaasProductSalesHistoryTestData.*;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SaasProductSalesHistoryClientTest {
    @Test void transportUsesLicensedIdentityAndChecksProductCompanyAndCoverage() throws Exception {
        var credentials = mock(LicenseSaasCredentialStore.class); var identities = mock(SaasLicenseIdentityResolver.class);
        var localCompany = UUID.randomUUID(); var localStore = UUID.randomUUID();
        when(credentials.readToken()).thenReturn(Optional.of("synthetic-token"));
        when(identities.resolve(localCompany, localStore)).thenReturn(new SaasLicenseIdentityResolver.SaasIdentity(COMPANY, STORE));
        var body = new AtomicReference<>(response()); var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var token = new AtomicReference<String>(); var path = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.set(MAPPER.readTree(exchange.getRequestBody())); token.set(exchange.getRequestHeaders().getFirst("X-TPV-Installation-Token"));
            path.set(exchange.getRequestURI().getPath()); var bytes = MAPPER.writeValueAsBytes(body.get());
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new SaasProductSalesHistoryClient("http://127.0.0.1:" + server.getAddress().getPort(), credentials, identities, MAPPER, HttpClient.newHttpClient());
            client.query("page", localCompany, localStore, "00042", Map.of("companyId", UUID.randomUUID(), "productCode", "fake"));
            assertThat(token.get()).isEqualTo("synthetic-token"); assertThat(path.get()).isEqualTo("/api/v1/product-sales-history/page");
            assertThat(received.get().path("companyId").asText()).isEqualTo(COMPANY.toString());
            assertThat(received.get().path("storeId").asText()).isEqualTo(STORE.toString()); assertThat(received.get().path("productCode").asText()).isEqualTo("00042");
            body.get().put("companyId", UUID.randomUUID().toString());
            assertThatThrownBy(() -> client.query("page", localCompany, localStore, "00042", Map.of())).hasMessage("SAAS_PRODUCT_HISTORY_INVALID_RESPONSE").hasNoCause();
            when(credentials.readToken()).thenReturn(Optional.empty());
            assertThatThrownBy(() -> client.query("page", localCompany, localStore, "00042", Map.of())).hasMessage("SAAS_PRODUCT_HISTORY_UNAVAILABLE").hasNoCause();
        } finally { server.stop(0); }
    }
    @Test void streamingLimitCancelsBeforeBufferingOversizedResponses() {
        var subscriber = new SaasProductSalesHistoryClient.BoundedBody(4); var subscription = mock(java.util.concurrent.Flow.Subscription.class);
        subscriber.onSubscribe(subscription); subscriber.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[5])));
        verify(subscription).cancel(); assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join()).hasCauseInstanceOf(SaasProductSalesHistoryException.class);
    }
}
