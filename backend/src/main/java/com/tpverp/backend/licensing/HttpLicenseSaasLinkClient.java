package com.tpverp.backend.licensing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.application.LicenseValidationException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpLicenseSaasLinkClient implements LicenseSaasLinkClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOG = LoggerFactory.getLogger(HttpLicenseSaasLinkClient.class);

    private final URI endpoint;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final LicenseSaasCredentialStore credentials;

    public HttpLicenseSaasLinkClient(
            URI saasUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper) {
        this(saasUrl, credentials, mapper, HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build());
    }

    HttpLicenseSaasLinkClient(
            URI saasUrl,
            LicenseSaasCredentialStore credentials,
            ObjectMapper mapper,
            HttpClient client) {
        this.endpoint = URI.create(saasUrl.toString().replaceAll("/+$", "") + "/api/v1/license/link");
        this.credentials = credentials;
        this.mapper = mapper.findAndRegisterModules();
        this.client = client;
    }

    @Override
    public LicenseSaasLinkResponse link(LicenseSaasLinkRequest link, String recoveryToken) {
        try {
            var builder = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            credentials.readToken().ifPresent(token -> builder.header(
                    "X-TPV-Installation-Token", token));
            if (recoveryToken != null && !recoveryToken.isBlank()) {
                builder.header("X-TPV-Link-Recovery-Token", recoveryToken.trim());
            }
            var request = builder
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(link)))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = responseDetail(response.body());
                LOG.warn("SaaS rechazo el enlace con HTTP {}{}", response.statusCode(), detail);
                throw new IllegalStateException(
                        "SaaS respondio " + response.statusCode() + detail);
            }
            return LicenseSaasLinkResponseContract.requireCurrent(
                    mapper.readValue(response.body(), LicenseSaasLinkResponse.class));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Vinculacion SaaS interrumpida", exception);
        } catch (Exception exception) {
            if (exception instanceof LicenseValidationException validation) {
                throw validation;
            }
            if (exception instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("No se pudo vincular con SaaS", exception);
        }
    }

    private String responseDetail(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            var error = mapper.readTree(body);
            var detail = error.hasNonNull("detail")
                    ? error.get("detail").asText()
                    : error.hasNonNull("message") ? error.get("message").asText() : null;
            if (detail == null || detail.isBlank()) {
                return "";
            }
            String normalized = detail.trim();
            return ": " + normalized.substring(0, Math.min(normalized.length(), 512));
        } catch (Exception ignored) {
            // Unknown remote bodies must not leak HTML or infrastructure details.
            return "";
        }
    }
}
