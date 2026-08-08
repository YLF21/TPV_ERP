package com.tpverp.backend.document;

import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
            @NotNull List<@Valid LineRequest> lines,
            String discountAuthorizationToken,
            String promotionalCouponCode,
            @DecimalMin("0.00") BigDecimal checkoutDiscountAmount,
            @Size(max = 500) String internalComment,
            @Size(max = 32)
            @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                    operationAuthorizations,
            @Valid PreviousTicketImportRequest previousTicketImport,
            @Size(max = 64) String quoteFingerprint) {

        public SaleRequest {
            lines = List.copyOf(lines == null ? List.of() : lines);
            operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                    operationAuthorizations);
        }

        public SaleRequest(UUID customerId, List<LineRequest> lines) {
            this(customerId, lines, null, null, null, null, Map.of(), null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken) {
            this(customerId, lines, discountAuthorizationToken, null, null, null,
                    Map.of(), null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode,
                    null, null, Map.of(), null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode,
                BigDecimal checkoutDiscountAmount) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode,
                    checkoutDiscountAmount, null, Map.of(), null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode,
                BigDecimal checkoutDiscountAmount,
                String internalComment) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode,
                    checkoutDiscountAmount, internalComment, Map.of(), null, null);
        }

        /**
         * Compatibility constructor retained for existing backend callers and tests that
         * predate historical ticket replay and the authoritative quote fingerprint.
         */
        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode,
                BigDecimal checkoutDiscountAmount,
                String internalComment,
                Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode,
                    checkoutDiscountAmount, internalComment, operationAuthorizations,
                    null, null);
        }

        public SaleRequest(
                UUID customerId,
                List<LineRequest> lines,
                String discountAuthorizationToken,
                String promotionalCouponCode,
                BigDecimal checkoutDiscountAmount,
                String internalComment,
                Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
                PreviousTicketImportRequest previousTicketImport) {
            this(customerId, lines, discountAuthorizationToken, promotionalCouponCode,
                    checkoutDiscountAmount, internalComment, operationAuthorizations,
                    previousTicketImport, null);
        }

        public OperationAuthorizationRequest authorizationFor(SaleOperationCode code) {
            return operationAuthorizations.getOrDefault(
                    code, OperationAuthorizationRequest.empty());
        }

        @AssertTrue(message = "La venta necesita lineas actuales o un ticket anterior")
        public boolean hasSaleContent() {
            return !lines.isEmpty() || previousTicketImport != null;
        }
    }

    public record PreviousTicketImportRequest(
            @NotNull UUID ticketId,
            @NotBlank @Size(max = 64) String fingerprint,
            @Size(max = 128) Map<@NotNull UUID,
                    @NotNull @Size(max = 128) List<@NotBlank @Size(max = 128) String>>
                    serialNumbersBySourceLineId) {

        public PreviousTicketImportRequest {
            fingerprint = fingerprint == null ? null : fingerprint.trim();
            var copy = new java.util.LinkedHashMap<UUID, List<String>>();
            if (serialNumbersBySourceLineId != null) {
                serialNumbersBySourceLineId.forEach((key, values) -> copy.put(
                        key, List.copyOf(values == null ? List.of() : values)));
            }
            serialNumbersBySourceLineId = Map.copyOf(copy);
        }
    }

    public record LineRequest(
            @NotNull UUID productId,
            @NotNull BigDecimal quantity,
            @NotNull @DecimalMin("0.00") BigDecimal discount,
            BigDecimal openUnitPrice,
            List<String> serialNumbers,
            @Size(max = 255) String temporaryName,
            @Valid ReturnOriginRequest returnOrigin,
            @Size(max = 128) String cartLineId,
            @Size(max = 256) String temporaryPriceAuthorizationToken) {
        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount,
                BigDecimal openUnitPrice,
                List<String> serialNumbers,
                String temporaryName,
                ReturnOriginRequest returnOrigin) {
            this(productId, quantity, discount, openUnitPrice, serialNumbers,
                    temporaryName, returnOrigin, null, null);
        }

        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount,
                BigDecimal openUnitPrice,
                List<String> serialNumbers,
                String temporaryName) {
            this(productId, quantity, discount, openUnitPrice, serialNumbers,
                    temporaryName, null, null, null);
        }

        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount,
                BigDecimal openUnitPrice) {
            this(productId, quantity, discount, openUnitPrice, List.of(), null, null);
        }

        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount,
                BigDecimal openUnitPrice,
                List<String> serialNumbers) {
            this(productId, quantity, discount, openUnitPrice, serialNumbers, null, null);
        }

        public LineRequest(
                UUID productId,
                BigDecimal quantity,
                BigDecimal discount) {
            this(productId, quantity, discount, null, List.of(), null, null);
        }

        @AssertTrue(message = "La cantidad no es válida para el origen indicado")
        public boolean isQuantityAllowed() {
            if (quantity == null) return false;
            if (returnOrigin != null) return quantity.signum() < 0;
            return quantity.signum() > 0 || quantity.compareTo(BigDecimal.ONE.negate()) == 0;
        }
    }

    public record ReturnOriginRequest(
            @NotNull TicketReturnService.ReturnSourceType sourceType,
            @NotNull @Size(max = 64) String sourceCode,
            @NotNull UUID sourceTicketId,
            @NotNull UUID sourceLineId,
            UUID giftReceiptLineId) {

        @AssertTrue(message = "El ticket regalo requiere su línea de origen")
        public boolean isGiftReceiptOriginValid() {
            return sourceType != TicketReturnService.ReturnSourceType.GIFT_RECEIPT
                    || giftReceiptLineId != null;
        }
    }

    public record CashRequest(
            @NotNull UUID checkoutId,
            @NotNull @Valid SaleRequest sale,
            @NotNull @DecimalMin("0.01") BigDecimal received,
            @NotNull @DecimalMin("0.01") BigDecimal quotedTotal) {}
}
