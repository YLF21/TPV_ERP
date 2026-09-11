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

    public CustomerAdoptionApi.Profile lookup(UUID companyId, UUID storeId, CustomerDocumentIdentity document) {
        try {
            var payload = adoptionContext(companyId, storeId, null);
            payload.remove("localCustomerId");
            payload.put("documentType", document.canonicalType().name());
            payload.put("documentNumber", document.canonicalNumber());
            var tree = mapper.readTree(send("/api/v1/customer-identities/lookup", payload));
            requireExactRevision(tree.path("revision"));
            var profile = mapper.treeToValue(tree, CustomerAdoptionApi.Profile.class);
            validateAdoptionProfile(profile);
            if (!document.canonicalNumber().equals(profile.documentNumber())) throw CustomerIdentityException.unavailable();
            return profile;
        } catch (CustomerIdentityException exception) { throw exception; }
        catch (Exception exception) { throw CustomerIdentityException.unavailable(); }
    }

    public CustomerAdoptionApi.Reservation reserveAdoption(CustomerAdoptionOperations.Operation operation) {
        try {
            var payload = adoptionContext(operation.companyId(), operation.storeId(), operation.customerId());
            payload.put("operationId", operation.operationId());
            payload.put("customerId", operation.centralCustomerId());
            payload.put("expectedRevision", operation.expectedRevision());
            payload.put("documentType", operation.documentType().name());
            payload.put("documentNumber", operation.documentNumber());
            var tree = mapper.readTree(send("/api/v1/customer-identities/adoptions", payload));
            requireExactRevision(tree.path("customer").path("revision"));
            var value = mapper.treeToValue(tree, CustomerAdoptionApi.Reservation.class);
            validateAdoptionProfile(value == null ? null : value.customer());
            var profile = value.customer();
            if (!operation.operationId().equals(value.operationId())
                    || !operation.customerId().equals(value.localCustomerId())
                    || !operation.centralCustomerId().equals(profile.customerId())
                    || operation.expectedRevision() != profile.revision()
                    || operation.documentType() != profile.documentType()
                    || !operation.documentNumber().equals(profile.documentNumber())
                    || (profile.localCustomerId() != null && !operation.customerId().equals(profile.localCustomerId()))) {
                throw CustomerIdentityException.unavailable();
            }
            return value;
        } catch (CustomerIdentityException exception) { throw exception; }
        catch (Exception exception) { throw CustomerIdentityException.unavailable(); }
    }

    public void cancelAdoption(CustomerAdoptionOperations.Operation operation) {
        try {
            send("/api/v1/customer-identities/adoptions/" + operation.operationId() + "/cancel",
                    adoptionContext(operation.companyId(), operation.storeId(), operation.customerId()));
        } catch (CustomerIdentityException exception) { throw exception; }
        catch (Exception exception) { throw CustomerIdentityException.unavailable(); }
    }

    private LinkedHashMap<String, Object> adoptionContext(UUID companyId, UUID storeId, UUID customerId) {
        var identity = identities.resolve(companyId, storeId);
        var result = new LinkedHashMap<String, Object>();
        result.put("companyId", identity.companyId());
        result.put("storeId", identity.storeId());
        result.put("localCustomerId", customerId);
        return result;
    }

    private static void requireExactRevision(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw CustomerIdentityException.unavailable();
        }
    }

    static void validateAdoptionProfile(CustomerAdoptionApi.Profile profile) {
        if (profile == null || profile.customerId() == null || profile.revision() == null || profile.revision() < 0
                || profile.active() == null) throw CustomerIdentityException.unavailable();
        try {
            var identity = CustomerDocumentIdentity.validate(profile.documentType(), profile.documentNumber());
            if (identity.canonicalType() != profile.documentType()
                    || !identity.canonicalNumber().equals(profile.documentNumber())) throw CustomerIdentityException.unavailable();
            length(profile.centralCode(), 40, true);
            length(profile.fiscalName(), 255, true);
            length(profile.phone(), 64, false);
            length(profile.email(), 320, false);
            var address = profile.address();
            if (address != null) {
                length(address.address(), 255, false); length(address.postalCode(), 16, false);
                length(address.city(), 128, false); length(address.province(), 128, false);
                length(address.country(), 2, false);
                address.local();
            }
        } catch (Exception exception) { throw CustomerIdentityException.unavailable(); }
    }

    private static void length(String value, int limit, boolean required) {
        if ((required && (value == null || value.isBlank())) || (value != null && value.length() > limit)) {
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
        if (response.statusCode() == 404
                && "CUSTOMER_CENTRAL_NOT_FOUND".equals(mapper.readTree(response.body()).path("code").asText())) {
            throw CustomerIdentityException.notFound();
        }
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
