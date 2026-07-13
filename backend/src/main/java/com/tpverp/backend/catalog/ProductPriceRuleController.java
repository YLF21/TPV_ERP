package com.tpverp.backend.catalog;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-price-rules")
@PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_PRODUCTO')")
public class ProductPriceRuleController {

    private final ProductPriceRuleService service;

    public ProductPriceRuleController(ProductPriceRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<ProductPriceRuleView> list() {
        return service.list();
    }

    @PostMapping
    public ProductPriceRuleView create(
            @Valid @RequestBody ProductPriceRuleService.ProductPriceRuleCreateRequest request,
            Authentication authentication) {
        return service.create(request, authentication);
    }

    @GetMapping("/{id}")
    public ProductPriceRuleView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public ProductPriceRuleView update(
            @PathVariable UUID id,
            @Valid @RequestBody ProductPriceRuleService.ProductPriceRuleUpdateRequest request,
            Authentication authentication) {
        return service.update(id, request, authentication);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @RequestParam long version,
            Authentication authentication) {
        service.delete(id, version, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/preview")
    public ProductPriceRulePreview preview(
            @PathVariable UUID id,
            @Valid @RequestBody ProductPriceRuleService.ProductPriceRuleExecutionRequest request) {
        return service.preview(id, request.ruleVersion());
    }

    @PostMapping("/{id}/apply")
    public ProductPriceRulePreview apply(
            @PathVariable UUID id,
            @Valid @RequestBody ProductPriceRuleService.ProductPriceRuleExecutionRequest request) {
        return service.apply(id, request.ruleVersion());
    }

    @ExceptionHandler(ProductPriceRuleConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(ProductPriceRuleConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, exception.getMessage());
        problem.setType(URI.create("urn:tpv-erp:error:PRODUCT_PRICE_RULE_CONFLICT"));
        problem.setProperty("code", "PRODUCT_PRICE_RULE_CONFLICT");
        problem.setProperty("productId", exception.getProductId());
        problem.setProperty("product", exception.getProductName());
        problem.setProperty("field", exception.getField());
        problem.setProperty("formIndexes", exception.getFormIndexes());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
