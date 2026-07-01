package com.tpverp.backend.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public class HttpLicenseSaasLinkClient implements LicenseSaasLinkClient {

    private final URI endpoint;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpLicenseSaasLinkClient(URI saasUrl, ObjectMapper mapper) {
        this(saasUrl, mapper, HttpClient.newHttpClient());
    }

    HttpLicenseSaasLinkClient(URI saasUrl, ObjectMapper mapper, HttpClient client) {
        this.endpoint = URI.create(saasUrl.toString().replaceAll("/+$", "") + "/api/v1/license/link");
        this.mapper = mapper.findAndRegisterModules();
        this.client = client;
    }

    @Override
    public LicenseSaasLinkResponse link(LicenseSaasLinkRequest link) {
        try {
            var request = HttpRequest.newBuilder(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(link)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("SaaS respondio " + response.statusCode());
            }
            return mapper.readValue(response.body(), LicenseSaasLinkResponse.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vinculacion SaaS interrumpida", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("No se pudo vincular con SaaS", exception);
        }
    }
}
