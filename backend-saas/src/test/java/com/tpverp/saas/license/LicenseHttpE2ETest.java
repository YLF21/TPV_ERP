package com.tpverp.saas.license;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tpverp.saas.SaasTestData.fiscalAddress;
import static com.tpverp.saas.SaasTestData.validCif;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.saas.admin.CreateCompanyRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LicenseHttpE2ETest {

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void localBackendLicenseFlowWorksAgainstRealSaasHttpApi() throws Exception {
        JsonNode company = postJson(
                "/api/v1/admin/companies",
                new CreateCompanyRequest(
                        "Empresa E2E",
                        validCif("B80808080"),
                        TaxpayerType.SOCIEDAD,
                        TaxRegime.IGIC,
                        CommercialProfile.MAYORISTA,
                        fiscalAddress(),
                        "001",
                        "Tienda 1",
                        fiscalAddress(),
                        "Atlantic/Canary",
                        Instant.parse("2099-07-01T00:00:00Z"),
                        2,
                        1),
                basic("admin", "admin"),
                null);

        UUID installationId = UUID.randomUUID();
        JsonNode link = postJson(
                "/api/v1/license/link",
                new LicenseSaasLinkRequest(
                        company.get("pairingCode").asText(),
                        installationId,
                        "INST-E2E",
                        "public-key",
                        UUID.fromString(company.get("storeId").asText()),
                        "001",
                        validCif("B80808080"),
                        "Empresa E2E",
                        null,
                        null,
                        "Atlantic/Canary"),
                null,
                null,
                "recovery-token-0123456789abcdef0123456789abcdef");

        assertThat(link.get("licenseReference").asText()).isEqualTo(company.get("licenseReference").asText());
        assertThat(link.get("installationToken").asText()).isNotBlank();

        JsonNode firstValidation = validate(company, installationId, link.get("installationToken").asText());
        assertThat(firstValidation.get("status").asText()).isEqualTo("VALIDA");
        assertThat(firstValidation.get("validUntil").asText()).isEqualTo("2099-07-01T00:00:00Z");

        postJson(
                "/api/v1/admin/licenses/" + company.get("licenseReference").asText() + "/block",
                "",
                basic("admin", "admin"),
                null);

        JsonNode blockedValidation = validate(company, installationId, link.get("installationToken").asText());
        assertThat(blockedValidation.get("status").asText()).isEqualTo("BLOQUEADA_MANUAL");
    }

    private JsonNode validate(JsonNode company, UUID installationId, String token) throws Exception {
        return postJson(
                "/api/v1/license/validate",
                new LicenseSaasValidationRequest(
                        installationId,
                        "INST-E2E",
                        UUID.fromString(company.get("storeId").asText()),
                        company.get("licenseReference").asText(),
                        "hash-local"),
                null,
                token);
    }

    private JsonNode postJson(String path, Object body, String authorization, String token) throws Exception {
        return postJson(path, body, authorization, token, null);
    }

    private JsonNode postJson(
            String path,
            Object body,
            String authorization,
            String token,
            String recoveryToken) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        body instanceof String text ? text : mapper.writeValueAsString(body)));
        if (authorization != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (token != null) {
            builder.header("X-TPV-Installation-Token", token);
        }
        if (recoveryToken != null) {
            builder.header("X-TPV-Link-Recovery-Token", recoveryToken);
        }
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isBetween(200, 299);
        if (response.body() == null || response.body().isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(response.body());
    }

    private String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
