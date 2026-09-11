package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tpv.sync.document-recovery-enabled", havingValue = "true")
public class DocumentSyncRecoveryClient {
    private final String baseUrl;
    private final LicenseSaasCredentialStore credentials;
    private final SaasLicenseIdentityResolver identities;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public DocumentSyncRecoveryClient(@Value("${tpv.sync.central-url:}") String baseUrl,
            LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper json) {
        // Technical reads are explicitly enabled by document-recovery-enabled, independently of dispatch.
        this(baseUrl, credentials, identities, json, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    DocumentSyncRecoveryClient(String baseUrl, LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper json, HttpClient http) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.credentials = credentials; this.identities = identities; this.json = json; this.http = http;
    }

    public List<RemoteRow> status(Scope scope, List<Expectation> expected) {
        if (baseUrl.isBlank()) throw DocumentSyncRecoveryException.unavailable();
        try {
            var identity = identities.resolve(scope.companyId(), scope.storeId());
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/commercial-document-queries/recovery-status"))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("X-TPV-Installation-Token", credentials.readToken().orElseThrow(DocumentSyncRecoveryException::unavailable))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(Map.of("companyId", identity.companyId(),
                            "storeId", identity.storeId(), "documents", expected)))).build();
            var response = http.send(request, info -> new SaasCustomerDocumentClient.BoundedBody(info.statusCode() == 200 ? 128 * 1024 : 8192));
            if (response.statusCode() != 200) throw DocumentSyncRecoveryException.unavailable();
            JsonNode root = json.readTree(response.body());
            if (root == null || !root.isObject() || !identity.companyId().toString().equals(root.path("companyId").asText())
                    || !identity.storeId().toString().equals(root.path("storeId").asText())
                    || requiredLong(root.get("schemaVersion")) != 2 || uuid(root.get("installationId")) == null
                    || !root.path("documents").isArray() || root.path("documents").size() != expected.size()) {
                throw DocumentSyncRecoveryException.malformed();
            }
            List<RemoteRow> result = new ArrayList<>();
            for (int i = 0; i < expected.size(); i++) {
                JsonNode row = root.path("documents").get(i);
                UUID id = uuid(row.get("documentId"));
                String state = row.path("status").asText();
                String eventState = row.path("requestedEventStatus").asText();
                Long revision = nullableLong(row.get("currentRevision"));
                UUID event = uuid(row.get("currentEventId"));
                JsonNode linked = row.get("customerLinked");
                String total = nullableText(row.get("total"), 64);
                String currency = nullableText(row.get("currency"), 3);
                if (!expected.get(i).documentId().equals(id) || !Set.of("MISSING", "PROJECTED", "OTHER_INSTALLATION").contains(state)
                        || !Set.of("NOT_REQUESTED", "MISSING", "RECEIVED", "PROJECTED", "IGNORED", "ERROR").contains(eventState)
                        || !row.path("requestedRevisionRecorded").isBoolean() || linked == null || !linked.isNull() && !linked.isBoolean()
                        || state.equals("PROJECTED") && (revision == null || event == null
                                || total == null || !total.matches("-?[0-9]+(?:\\.[0-9]+)?")
                                || currency == null || !currency.matches("[A-Z]{3}"))
                        || !state.equals("PROJECTED") && (revision != null || event != null || !linked.isNull()
                                || total != null || currency != null)) {
                    throw DocumentSyncRecoveryException.malformed();
                }
                result.add(new RemoteRow(id, state, revision, event, eventState,
                        row.path("requestedRevisionRecorded").booleanValue(), linked.isNull() ? null : linked.booleanValue(),
                        total, currency));
            }
            return List.copyOf(result);
        } catch (DocumentSyncRecoveryException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw DocumentSyncRecoveryException.unavailable(); }
        catch (Exception exception) { throw DocumentSyncRecoveryException.malformed(); }
    }
    private static UUID uuid(JsonNode value) {
        if (value != null && value.isNull()) return null;
        if (value == null || !value.isTextual()) throw DocumentSyncRecoveryException.malformed();
        return UUID.fromString(value.textValue());
    }
    private static long requiredLong(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw DocumentSyncRecoveryException.malformed();
        }
        return value.longValue();
    }
    private static Long nullableLong(JsonNode value) {
        return value != null && value.isNull() ? null : requiredLong(value);
    }
    private static String nullableText(JsonNode value, int limit) {
        if (value != null && value.isNull()) return null;
        if (value == null || !value.isTextual() || value.textValue().length() > limit) throw DocumentSyncRecoveryException.malformed();
        return value.textValue();
    }
}
