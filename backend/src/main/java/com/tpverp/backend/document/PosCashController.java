package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/cash")
public class PosCashController {

    private final PosCashService service;

    public PosCashController(PosCashService service) {
        this.service = service;
    }

    @PostMapping("/quote")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Quote quote(@Valid @RequestBody SaleRequest request, Authentication authentication) {
        return service.quote(request, authentication);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PosCashService.Result charge(@Valid @RequestBody CashRequest request, Authentication authentication) {
        return service.charge(request, authentication);
    }

    public record SaleRequest(
            UUID customerId,
            @NotEmpty List<@Valid LineRequest> lines,
            String discountAuthorizationToken,
            String promotionalCouponCode,
            @DecimalMin("0.00") BigDecimal checkoutDiscountAmount) {
        public SaleRequest(UUID customerId, List<LineRequest> lines) {
            this(customerId, lines, null, null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken) {
            this(customerId, lines, discountAuthorizationToken, null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode, null);
        }
    }

    public record LineRequest(
            @NotNull UUID productId,
            @NotNull BigDecimal quantity,
            @NotNull @DecimalMin("0.00") BigDecimal discount,
            BigDecimal openUnitPrice,
            List<String> serialNumbers) {
        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount,
                BigDecimal openUnitPrice) {
            this(productId, quantity, discount, openUnitPrice, List.of());
        }

        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount) {
            this(productId, quantity, discount, null, List.of());
        }

        @AssertTrue(message = "La cantidad debe ser positiva o exactamente -1")
        public boolean isQuantityAllowed() {
            return quantity != null
                    && (quantity.signum() > 0 || quantity.compareTo(BigDecimal.ONE.negate()) == 0);
        }
    }

    public record CashRequest(
            @NotNull UUID checkoutId,
            @NotNull @Valid SaleRequest sale,
            @NotNull @DecimalMin("0.01") BigDecimal received,
            @NotNull @DecimalMin("0.01") BigDecimal quotedTotal) {}
}
