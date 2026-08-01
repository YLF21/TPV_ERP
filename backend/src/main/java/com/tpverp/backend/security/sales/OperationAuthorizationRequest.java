package com.tpverp.backend.security.sales;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Ephemeral credentials for one sale operation.
 *
 * <p>This value is accepted only as command input. It must never be copied to
 * sale snapshots, parked sales, payment sessions, idempotency hashes or audit
 * details.</p>
 */
public record OperationAuthorizationRequest(
        @Size(max = 128) String authorizerUsername,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @Size(max = 128) String authorizerPassword) {

    public static OperationAuthorizationRequest empty() {
        return new OperationAuthorizationRequest(null, null);
    }

    public static Map<SaleOperationCode, OperationAuthorizationRequest> immutableCopy(
            Map<SaleOperationCode, OperationAuthorizationRequest> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > 32) {
            throw new IllegalArgumentException(
                    "sale_operation_authorizations_too_many");
        }
        var copy = new EnumMap<SaleOperationCode, OperationAuthorizationRequest>(
                SaleOperationCode.class);
        values.forEach((code, request) -> {
            if (code == null) {
                throw new IllegalArgumentException(
                        "sale_operation_authorization_code_required");
            }
            if (request == null) {
                throw new IllegalArgumentException(
                        "sale_operation_authorization_value_required");
            }
            copy.put(code, request);
        });
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public String toString() {
        return "OperationAuthorizationRequest[authorizerUsername="
                + authorizerUsername + ", authorizerPassword=<redacted>]";
    }
}
