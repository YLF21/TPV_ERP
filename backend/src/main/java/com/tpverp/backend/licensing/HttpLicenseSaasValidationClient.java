package com.tpverp.backend.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

public class HttpLicenseSaasValidationClient implements LicenseSaasValidationClient {

    private final URI endpoint;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public HttpLicenseSaasValidationClient(
            URI saasUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper) {
        this(saasUrl, credentials, mapper, HttpClient.newHttpClient());
    }

    HttpLicenseSaasValidationClient(
            URI saasUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper,
            HttpClient client) {
        this.endpoint = URI.create(saasUrl.toString().replaceAll("/+$", "") + "/api/v1/license/validate");
        this.credentials = credentials;
        this.mapper = mapper.findAndRegisterModules();
        this.client = client;
    }

    @Override
    public LicenseSaasValidationResponse validate(LicenseSaasValidationRequest validation) {
        try {
            var builder = HttpRequest.newBuilder(endpoint)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            credentials.readToken()
                    .ifPresent(token -> builder.header("X-TPV-Installation-Token", token));
            var request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(validation)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("SaaS respondio " + response.statusCode());
            }
            return mapper.readValue(response.body(), LicenseSaasValidationResponse.class);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validacion SaaS interrumpida", exception);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("No se pudo validar licencia con SaaS", exception);
        }
    }
}
