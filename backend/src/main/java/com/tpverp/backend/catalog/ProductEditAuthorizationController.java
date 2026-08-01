package com.tpverp.backend.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/product-edit-authorizations")
public class ProductEditAuthorizationController {

    private final ProductEditAuthorizationService service;

    public ProductEditAuthorizationController(ProductEditAuthorizationService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ProductEditAuthorizationService.AuthorizationView authorize(
            @Valid @RequestBody AuthorizationRequest request,
            Authentication authentication) {
        return service.authorize(
                request.productId(),
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
    }

    @DeleteMapping("/{operationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> revoke(
            @PathVariable UUID operationId,
            Authentication authentication) {
        service.revoke(operationId, authentication);
        return ResponseEntity.noContent().build();
    }

    public record AuthorizationRequest(
            @NotNull UUID productId,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword) {

        @Override
        public String toString() {
            return "AuthorizationRequest[productId=" + productId
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }
}
