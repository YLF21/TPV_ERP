package com.tpverp.backend.party.loyalty.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HttpMemberCategoryBootstrapGatewayTest {

    @Test
    void aceptaCamposInformativosAdicionalesEnElEstadoSaas() throws Exception {
        UUID bootstrapId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        String response = """
                {
                  "bootstrapId":"%s",
                  "companyId":"%s",
                  "status":"COLLECTING",
                  "expectedStoreIds":["%s"],
                  "completedStoreIds":[],
                  "missingStoreIds":["%s"],
                  "conflictStoreIds":[],
                  "conflictReason":null,
                  "configRevision":null,
                  "assignmentRevision":null,
                  "createdAt":"2026-08-25T13:01:48Z",
                  "completedAt":null
                }
                """.formatted(bootstrapId, companyId, storeId, storeId);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/v2/loyalty/member-categories/bootstrap/discover",
                exchange -> {
                    exchange.getRequestBody().readAllBytes();
                    byte[] body = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        LicenseSaasCredentialStore credentials = mock(LicenseSaasCredentialStore.class);
        when(credentials.readToken()).thenReturn(Optional.of("token-local"));

        try {
            server.start();
            var gateway = new HttpMemberCategoryBootstrapGateway(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    credentials,
                    new ObjectMapper());

            var status = gateway.discover(companyId, storeId);

            assertThat(status.bootstrapId()).isEqualTo(bootstrapId);
            assertThat(status.isCollecting()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
