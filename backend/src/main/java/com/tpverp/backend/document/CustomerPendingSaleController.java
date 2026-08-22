package com.tpverp.backend.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.terminal.PaymentTerminalResult;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/pos/customer-pending-sales")
public class CustomerPendingSaleController {

    private static final String ACCESS =
            "hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS',"
                    + "'INVOICES_WRITE','DELIVERY_NOTES_WRITE')";

    private final CustomerPendingSaleService service;
    private final CustomerReceivablePrintService printing;

    public CustomerPendingSaleController(CustomerPendingSaleService service,
            CustomerReceivablePrintService printing) {
        this.service = service;
        this.printing = printing;
    }

    @PostMapping("/quote")
    @PreAuthorize(ACCESS)
    public CustomerPendingSaleService.Quote quote(
            @Valid @RequestBody CreateRequest request, Authentication authentication) {
        requireLegacyPendingSale(request);
        requireDocumentAccess(request, authentication);
        return service.quote(request, authentication);
    }

    @PostMapping("/card-charges")
    @PreAuthorize(ACCESS)
    public PaymentTerminalResult chargeCard(
            @Valid @RequestBody CardChargeRequest request, Authentication authentication) {
        requireLegacyPendingSale(request.sale());
        requireDocumentAccess(request.sale(), authentication);
        return service.chargeCard(request, authentication);
    }

    @PostMapping("")
    @PreAuthorize(ACCESS)
    public CreateResponse create(
            @Valid @RequestBody CreateRequest request, Authentication authentication) {
        requireLegacyPendingSale(request);
        requireDocumentAccess(request, authentication);
        var receivable = service.create(request, authentication);
        return new CreateResponse(receivable, printing.document(receivable.documentId()));
    }

    private static void requireLegacyPendingSale(CreateRequest request) {
        if (request.completionMode() != null) {
            throw new IllegalArgumentException(
                    "sales_document_checkout_endpoint_required");
        }
    }

    private static void requireDocumentAccess(
            CreateRequest request,
            Authentication authentication) {
        SalesDocumentCheckoutController.requireDocumentAccess(request, authentication);
    }

    public record CreateResponse(CustomerReceivableView receivable,
            CustomerReceivablePrintService.CommercialDocumentPrint printDocument) {}

    public record CreateRequest(
            @NotNull UUID checkoutId,
            @NotNull UUID warehouseId,
            @NotNull CommercialDocumentType type,
            @NotNull LocalDate date,
            @NotNull UUID customerId,
            @NotNull LocalDate dueDate,
            @NotNull BigDecimal globalDiscount,
            @NotEmpty @Valid List<DocumentRequest.LineRequest> lines,
            List<@Valid PaymentItem> payments,
            @NotNull @DecimalMin("0.00") BigDecimal quotedTotal,
            @Valid CreditOverride creditOverride,
            SalesDocumentCompletionMode completionMode,
            @Size(max = 500) String internalComment,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword,
            @Size(max = 32)
            @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                    operationAuthorizations,
            @jakarta.validation.constraints.Min(0) Long draftVersion,
            @DecimalMin("0.00") BigDecimal documentDiscountPercent) {

        public CreateRequest {
            operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                    operationAuthorizations);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride,
                SalesDocumentCompletionMode completionMode,
                String internalComment,
                String authorizerUsername,
                String authorizerPassword,
                Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations,
                Long draftVersion) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    completionMode, internalComment, authorizerUsername,
                    authorizerPassword, operationAuthorizations, draftVersion, null);
        }

        @Override
        public String toString() {
            return "CreateRequest[checkoutId=" + checkoutId
                    + ", warehouseId=" + warehouseId
                    + ", type=" + type
                    + ", customerId=" + customerId
                    + ", completionMode=" + completionMode
                    + ", draftVersion=" + draftVersion
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride,
                SalesDocumentCompletionMode completionMode,
                String internalComment,
                String authorizerUsername,
                String authorizerPassword,
                Map<SaleOperationCode, OperationAuthorizationRequest> operationAuthorizations) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    completionMode, internalComment, authorizerUsername,
                    authorizerPassword, operationAuthorizations, null);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride,
                SalesDocumentCompletionMode completionMode,
                String internalComment,
                String authorizerUsername,
                String authorizerPassword) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    completionMode, internalComment, authorizerUsername,
                    authorizerPassword, Map.of(), null);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride,
                SalesDocumentCompletionMode completionMode,
                String internalComment) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    completionMode, internalComment, null, null, Map.of(), null);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride,
                SalesDocumentCompletionMode completionMode) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    completionMode, null, null, null, Map.of(), null);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal,
                CreditOverride creditOverride) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, creditOverride,
                    null, null, null, null, Map.of(), null);
        }

        public CreateRequest(
                UUID checkoutId,
                UUID warehouseId,
                CommercialDocumentType type,
                LocalDate date,
                UUID customerId,
                LocalDate dueDate,
                BigDecimal globalDiscount,
                List<DocumentRequest.LineRequest> lines,
                List<PaymentItem> payments,
                BigDecimal quotedTotal) {
            this(checkoutId, warehouseId, type, date, customerId, dueDate,
                    globalDiscount, lines, payments, quotedTotal, null,
                    null, null, null, null, Map.of(), null);
        }

        DocumentCommand toCommand() {
            return new DocumentCommand(
                    warehouseId, type, date, customerId, null, null,
                    globalDiscount, true,
                    lines.stream().map(DocumentRequest.LineRequest::toCommand).toList(),
                    internalComment, documentDiscountPercent);
        }
    }

    public record CreditOverride(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500) String reason,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword) {
        public CreditOverride(String reason) {
            this(reason, null, null);
        }

        @Override
        public String toString() {
            return "CreditOverride[reason=" + reason
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record PaymentItem(
            @NotNull PaymentKind kind,
            @NotNull UUID methodId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            boolean principal,
            BigDecimal delivered,
            BigDecimal change,
            String voucherCode,
            String reference,
            UUID requestId,
            UUID paymentTerminalOperationId) {

        public PaymentItem(
                UUID methodId,
                BigDecimal amount,
                boolean principal,
                BigDecimal delivered,
                BigDecimal change,
                String voucherCode,
                String reference) {
            this(PaymentKind.STANDARD, methodId, amount, principal, delivered, change,
                    voucherCode, reference, null, null);
        }
    }

    public enum PaymentKind {
        STANDARD,
        MANUAL_CARD,
        INTEGRATED_CARD
    }

    public enum SalesDocumentCompletionMode {
        DRAFT,
        CONFIRM_PENDING,
        CONFIRM_AND_PAY
    }

    public record CardChargeRequest(
            @NotNull @Valid CreateRequest sale,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }
}
