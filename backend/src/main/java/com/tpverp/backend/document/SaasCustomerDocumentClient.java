package com.tpverp.backend.document;

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

@Component
public class SaasCustomerDocumentClient {
    private final String baseUrl;
    private final LicenseSaasCredentialStore credentials;
    private final SaasLicenseIdentityResolver identities;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public SaasCustomerDocumentClient(@Value("${tpv.sync.central-url:}") String baseUrl,
            @Value("${tpv.sync.worker-enabled:false}") boolean enabled,
            LicenseSaasCredentialStore credentials, SaasLicenseIdentityResolver identities, ObjectMapper mapper) {
        this(enabled ? baseUrl : "", credentials, identities, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build());
    }
    SaasCustomerDocumentClient(String baseUrl, LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        this.credentials = credentials; this.identities = identities; this.mapper = mapper; this.http = http;
    }

    public JsonNode query(String operation, UUID localCompanyId, UUID localStoreId, UUID localCustomerId,
            UUID expectedCustomerId, Map<String, Object> query) {
        if (!List.of("page", "export", "annual").contains(operation)) throw new IllegalArgumentException("Operación de lectura no válida");
        if (baseUrl.isBlank()) throw SaasCustomerDocumentException.unavailable();
        try {
            var identity = identities.resolve(localCompanyId, localStoreId);
            var body = new LinkedHashMap<>(query);
            body.put("companyId", identity.companyId()); body.put("storeId", identity.storeId());
            body.put("localCustomerId", localCustomerId); body.put("expectedCustomerId", expectedCustomerId);
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/commercial-document-queries/" + operation))
                    .timeout(Duration.ofSeconds(operation.equals("page") ? 30 : 60))
                    .header("Content-Type", "application/json")
                    .header("X-TPV-Installation-Token", credentials.readToken().orElseThrow(SaasCustomerDocumentException::unavailable))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            int maximum = operation.equals("export") ? 64 * 1024 * 1024 : operation.equals("page") ? 1024 * 1024 : 64 * 1024;
            var response = http.send(request, info -> new BoundedBody(info.statusCode() == 200 ? maximum : 8192));
            if (response.statusCode() != 200) {
                if (response.statusCode() == 409 || response.statusCode() == 413 || response.statusCode() == 422) {
                    JsonNode error = mapper.readTree(response.body());
                    String code = error == null ? "" : error.path("code").asText();
                    if (response.statusCode() == 409 && "SAAS_CUSTOMER_BINDING_REQUIRED".equals(code)) {
                        throw SaasCustomerDocumentException.binding();
                    }
                    if (response.statusCode() == 409 && "CUSTOMER_DOCUMENT_SELECTION_UNAVAILABLE".equals(code)) {
                        throw new SaasCustomerDocumentException(org.springframework.http.HttpStatus.CONFLICT, code);
                    }
                    if (error != null && "customer_documents_export_limit_exceeded".equals(error.path("code").asText())) {
                        throw SaasCustomerDocumentException.limit();
                    }
                }
                throw SaasCustomerDocumentException.unavailable();
            }
            JsonNode value;
            try { value = mapper.readTree(response.body()); }
            catch (Exception exception) { throw SaasCustomerDocumentException.invalidResponse(); }
            if (value == null || !value.isObject()
                    || !identity.companyId().toString().equals(value.path("companyId").asText())
                    || !expectedCustomerId.toString().equals(value.path("customer").path("id").asText())
                    || !SaasCustomerDocumentApi.COVERAGE.equals(value.path("coverage").asText())) {
                throw SaasCustomerDocumentException.invalidResponse();
            }
            return value;
        } catch (SaasCustomerDocumentException exception) { throw exception; }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw SaasCustomerDocumentException.unavailable();
        } catch (Exception exception) { throw SaasCustomerDocumentException.unavailable(); }
    }

    /** Bound allocation while streaming, not after a potentially unbounded body was buffered. */
    static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(SaasCustomerDocumentException.invalidResponse()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
