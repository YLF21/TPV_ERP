package com.tpverp.backend.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty("tpv.sync.central-url")
public class HttpSyncEventSender implements SyncEventSender {

    private final URI endpoint;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpSyncEventSender(
            @org.springframework.beans.factory.annotation.Value("${tpv.sync.central-url}") URI centralUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper) {
        this(centralUrl, credentials, mapper, HttpClient.newHttpClient());
    }

    HttpSyncEventSender(URI centralUrl, ObjectMapper mapper, HttpClient client) {
        this(centralUrl, null, mapper, client);
    }

    HttpSyncEventSender(
            URI centralUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper,
            HttpClient client) {
        this.endpoint = URI.create(centralUrl.toString().replaceAll("/+$", "") + "/api/v1/sync/events");
        this.credentials = credentials;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public void send(SyncOutboxEvent event) {
        try {
            var builder = HttpRequest.newBuilder(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            if (credentials != null) {
                credentials.readToken()
                        .ifPresent(token -> builder.header("X-TPV-Installation-Token", token));
            }
            var request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload(event))))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("SaaS respondio " + response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Envio interrumpido", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("No se pudo enviar evento sync", exception);
        }
    }

    private Map<String, Object> payload(SyncOutboxEvent event) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("eventId", event.getEventId());
        payload.put("companyId", event.getCompanyId());
        payload.put("storeId", event.getStoreId());
        payload.put("terminalId", event.getTerminalId());
        payload.put("entityType", event.getEntityType());
        payload.put("entityId", event.getEntityId());
        payload.put("operation", event.getOperation());
        payload.put("payload", event.getPayload());
        return payload;
    }
}
