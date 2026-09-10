package com.tpverp.backend.party;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpverp.backend.licensing.LicenseSaasCredentialStore;
import com.tpverp.backend.licensing.SaasLicenseIdentityResolver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CustomerIdentitySaasClient {
    private final String baseUrl;
    private final LicenseSaasCredentialStore credentials;
    private final SaasLicenseIdentityResolver identities;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public CustomerIdentitySaasClient(
            @Value("${tpv.sync.central-url:}") String baseUrl,
            @Value("${tpv.sync.worker-enabled:false}") boolean workerEnabled,
            LicenseSaasCredentialStore credentials, SaasLicenseIdentityResolver identities, ObjectMapper mapper) {
        this(workerEnabled ? baseUrl : "", credentials, identities, mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    CustomerIdentitySaasClient(String baseUrl, LicenseSaasCredentialStore credentials,
            SaasLicenseIdentityResolver identities, ObjectMapper mapper, HttpClient http) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.credentials = credentials;
        this.identities = identities;
        this.mapper = mapper;
        this.http = http;
    }

    public Reservation reserve(CustomerIdentityOperations.Operation operation, Map<String, Object> profile) {
        try {
            var payload = context(operation);
            payload.put("operationId", operation.operationId());
            payload.put("expectedCustomerId", operation.expectedCustomerId());
            payload.put("expectedRevision", operation.expectedRevision());
            payload.put("documentType", operation.documentType().name());
            payload.put("documentNumber", operation.documentNumber());
            payload.put("profile", profile);
            var response = send("/api/v1/customer-identities/reservations", payload);
            var value = mapper.readValue(response, Reservation.class);
            if (value == null || !operation.operationId().equals(value.operationId())
                    || value.customerId() == null || value.revision() == null
                    || value.revision() != (operation.expectedRevision() == null
                        ? 1L : Math.addExact(operation.expectedRevision(), 1L))
                    || !operation.documentType().name().equals(value.documentType())
                    || !operation.documentNumber().equals(value.documentNumber())
                    || (operation.expectedCustomerId() != null
                        && !operation.expectedCustomerId().equals(value.customerId()))) {
                throw CustomerIdentityException.unavailable();
            }
            return value;
        } catch (CustomerIdentityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CustomerIdentityException.unavailable();
        }
    }

    public void cancel(CustomerIdentityOperations.Operation operation) {
        try {
            send("/api/v1/customer-identities/reservations/" + operation.operationId() + "/cancel", context(operation));
        } catch (CustomerIdentityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw CustomerIdentityException.unavailable();
        }
    }

    private LinkedHashMap<String, Object> context(CustomerIdentityOperations.Operation operation) {
        var identity = identities.resolve(operation.companyId(), operation.storeId());
        var result = new LinkedHashMap<String, Object>();
        result.put("companyId", identity.companyId());
        result.put("storeId", identity.storeId());
        result.put("localCustomerId", operation.customerId());
        return result;
    }

    private String send(String path, Map<String, Object> body) throws Exception {
        if (baseUrl.isBlank()) throw CustomerIdentityException.unavailable();
        String token = credentials.readToken().orElseThrow(CustomerIdentityException::unavailable);
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-TPV-Installation-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw CustomerIdentityException.unavailable();
        }
        if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
        // Do not surface upstream details, customer data, credentials or arbitrary error codes.
        if (response.statusCode() == 409 || response.statusCode() == 400 || response.statusCode() == 422) {
            String code = Objects.toString(mapper.readTree(response.body()).path("code").asText(), "");
            if (Set.of("CUSTOMER_DOCUMENT_INVALID", "CUSTOMER_DOCUMENT_DUPLICATE", "CUSTOMER_IDENTITY_CONFLICT").contains(code)) {
                throw switch (code) {
                    case "CUSTOMER_DOCUMENT_INVALID" -> CustomerIdentityException.invalid();
                    case "CUSTOMER_DOCUMENT_DUPLICATE" -> CustomerIdentityException.duplicate();
                    default -> CustomerIdentityException.conflict();
                };
            }
            if (response.statusCode() == 409) throw CustomerIdentityException.conflict();
        }
        throw CustomerIdentityException.unavailable();
    }

    public record Reservation(UUID operationId, UUID customerId, Long revision,
            String documentType, String documentNumber) { }
}
