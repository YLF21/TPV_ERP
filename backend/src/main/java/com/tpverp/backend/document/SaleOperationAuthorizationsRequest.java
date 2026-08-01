package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Ephemeral authorization envelope for a mutation without another request body.
 *
 * <p>The credentials are validated during the request and are never stored in
 * the commercial document or an idempotency snapshot.</p>
 */
public record SaleOperationAuthorizationsRequest(
        @Size(max = 32)
        @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                operationAuthorizations,
        @Size(max = 500) String creditOverrideReason) {

    public SaleOperationAuthorizationsRequest {
        operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                operationAuthorizations);
    }

    public static SaleOperationAuthorizationsRequest empty() {
        return new SaleOperationAuthorizationsRequest(Map.of(), null);
    }

    public SaleOperationAuthorizationsRequest(
            Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations) {
        this(operationAuthorizations, null);
    }
}
