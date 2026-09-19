package com.tpverp.backend.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Installation credentials stay in the local backend; remote errors never disclose their body. */
@Component
public class SaasProductSalesHistoryClient {
    private final String baseUrl;
    private final LicenseSaasCredentialStore credentials;
    private final SaasLicenseIdentityResolver identities;
    private final ObjectMapper mapper;
    private final HttpClient http;
    @Autowired
    public SaasProductSalesHistoryClient(@Value("${tpv.sync.central-url:}") String baseUrl,
            @Value("${tpv.sync.worker-enabled:false}") boolean enabled, LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper mapper) {
        this(enabled ? baseUrl : "", credentials, identities, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    SaasProductSalesHistoryClient(String baseUrl, LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.credentials = credentials; this.identities = identities; this.mapper = mapper; this.http = http;
    }
    public JsonNode query(String operation, UUID companyId, UUID storeId, String productCode, Map<String, Object> query) {
        if (!List.of("page", "export").contains(operation)) throw new IllegalArgumentException("Invalid operation");
        if (baseUrl.isBlank()) throw SaasProductSalesHistoryException.unavailable();
        try {
            var identity = identities.resolve(companyId, storeId);
            var body = new LinkedHashMap<>(query);
            body.put("companyId", identity.companyId()); body.put("storeId", identity.storeId()); body.put("productCode", productCode);
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/product-sales-history/" + operation))
                    .timeout(Duration.ofSeconds(operation.equals("page") ? 30 : 60))
                    .header("Content-Type", "application/json")
                    .header("X-TPV-Installation-Token", credentials.readToken().orElseThrow(SaasProductSalesHistoryException::unavailable))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            int maximum = operation.equals("export") ? 64 * 1024 * 1024 : 4 * 1024 * 1024;
            var response = http.send(request, info -> new BoundedBody(info.statusCode() == 200 ? maximum : 8192));
            if (response.statusCode() != 200) {
                if (response.statusCode() == 413 || response.statusCode() == 422) throw SaasProductSalesHistoryException.limit();
                throw SaasProductSalesHistoryException.unavailable();
            }
            JsonNode value;
            try { value = mapper.readTree(response.body()); }
            catch (Exception exception) { throw SaasProductSalesHistoryException.invalidResponse(); }
            if (value == null || !value.isObject()
                    || !identity.companyId().toString().equals(value.path("companyId").asText())
                    || !productCode.equals(value.path("productCode").asText())
                    || !"RECEIVED_IN_SAAS".equals(value.path("coverage").asText())) {
                throw SaasProductSalesHistoryException.invalidResponse();
            }
            return value;
        } catch (SaasProductSalesHistoryException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw SaasProductSalesHistoryException.unavailable(); }
        catch (Exception exception) { throw SaasProductSalesHistoryException.unavailable(); }
    }
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(SaasProductSalesHistoryException.invalidResponse()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
