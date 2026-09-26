package com.tpverp.backend.supervision.repair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
@ConditionalOnProperty("tpv.sync.central-url")
public class HttpRemoteRepairClient implements RemoteRepairClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> KEYS = Set.of("commandId", "companyId", "storeId", "action", "eventId", "expectedVersion", "expiresAt");
    private final String base;
    private final LicenseSaasCredentialStore credentials;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public HttpRemoteRepairClient(@Value("${tpv.sync.central-url}") URI centralUrl,
            LicenseSaasCredentialStore credentials, ObjectMapper mapper) {
        this(centralUrl, credentials, mapper, HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    HttpRemoteRepairClient(URI centralUrl, LicenseSaasCredentialStore credentials, ObjectMapper mapper, HttpClient client) {
        this.base = centralUrl.toString().replaceAll("/+$", "") + "/api/v1/sync/repairs";
        this.credentials = credentials; this.mapper = mapper; this.client = client;
    }

    @Override
    public List<RemoteRepairCommand> claim(UUID installationId) {
        try {
            String response = post("/claim", Map.of("installationId", installationId.toString()));
            JsonNode root = mapper.readTree(response);
            if (root == null || !root.isArray() || root.size() > 10) throw invalid();
            List<RemoteRepairCommand> commands = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isObject() || node.size() != KEYS.size()) throw invalid();
                var names = new java.util.HashSet<String>(); node.fieldNames().forEachRemaining(names::add);
                if (!names.equals(KEYS) || !node.get("expectedVersion").isIntegralNumber()
                        || !node.get("expectedVersion").canConvertToLong()) throw invalid();
                commands.add(new RemoteRepairCommand(uuid(node, "commandId"), uuid(node, "companyId"), uuid(node, "storeId"),
                        text(node, "action"), uuid(node, "eventId"), node.get("expectedVersion").longValue(),
                        Instant.parse(text(node, "expiresAt"))));
            }
            return List.copyOf(commands);
        } catch (RuntimeException exception) { throw invalid(); }
        catch (Exception exception) { throw invalid(); }
    }

    @Override
    public void report(RemoteRepairResult result) {
        try {
            JsonNode receipt = mapper.readTree(post("/" + result.commandId() + "/result",
                    Map.of("installationId", result.installationId().toString(),
                            "status", result.status(), "resultCode", result.resultCode())));
            if (receipt == null || !receipt.isObject() || !uuid(receipt, "commandId").equals(result.commandId())) throw invalid();
            String status = text(receipt, "status");
            String code = text(receipt, "resultCode");
            if (!(status.equals(result.status()) && code.equals(result.resultCode()))
                    && !("EXPIRED".equals(status) && "REPAIR_EXPIRED".equals(code))) throw invalid();
        } catch (Exception failure) { throw invalid(); }
    }

    private String post(String path, Map<String, String> body) {
        try {
            String token = credentials.readToken().orElseThrow(HttpRemoteRepairClient::invalid);
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(TIMEOUT)
                    .header("Content-Type", "application/json").header("X-TPV-Installation-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.limiting(
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), 65536));
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw invalid();
            // A terminal command already expired centrally can be acknowledged without changing its state.
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw invalid();
        } catch (Exception exception) { throw invalid(); }
    }

    private static String text(JsonNode node, String key) {
        if (!node.hasNonNull(key) || !node.get(key).isTextual()) throw invalid();
        return node.get(key).textValue();
    }
    private static UUID uuid(JsonNode node, String key) {
        String value = text(node, key);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equalsIgnoreCase(value)) throw invalid();
        return parsed;
    }
    private static IllegalStateException invalid() { return new IllegalStateException("REMOTE_REPAIR_TRANSPORT_UNAVAILABLE"); }
}
