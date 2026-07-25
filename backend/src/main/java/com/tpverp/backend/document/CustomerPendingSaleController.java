package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    private static final String CREATE_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_CREATE')";

    private final CustomerPendingSaleService service;
    private final CustomerReceivablePrintService printing;

    public CustomerPendingSaleController(CustomerPendingSaleService service,
            CustomerReceivablePrintService printing) {
        this.service = service;
        this.printing = printing;
    }

    @PostMapping("/quote")
    @PreAuthorize(CREATE_PERMISSION)
    public CustomerPendingSaleService.Quote quote(
            @Valid @RequestBody CreateRequest request, Authentication authentication) {
        requireLegacyPendingSale(request);
        return service.quote(request, authentication);
    }

    @PostMapping("/card-charges")
    @PreAuthorize(CREATE_PERMISSION)
    public PaymentTerminalResult chargeCard(
            @Valid @RequestBody CardChargeRequest request, Authentication authentication) {
        requireLegacyPendingSale(request.sale());
        return service.chargeCard(request, authentication);
    }

    @PostMapping("")
    @PreAuthorize(CREATE_PERMISSION)
    public CreateResponse create(
            @Valid @RequestBody CreateRequest request, Authentication authentication) {
        requireLegacyPendingSale(request);
        var receivable = service.create(request, authentication);
        return new CreateResponse(receivable, printing.document(receivable.documentId()));
    }

    private static void requireLegacyPendingSale(CreateRequest request) {
        if (request.completionMode() != null) {
            throw new IllegalArgumentException(
                    "sales_document_checkout_endpoint_required");
        }
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
            SalesDocumentCompletionMode completionMode) {
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
                    globalDiscount, lines, payments, quotedTotal, creditOverride, null);
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
                    globalDiscount, lines, payments, quotedTotal, null, null);
        }

        DocumentCommand toCommand() {
            return new DocumentCommand(
                    warehouseId, type, date, customerId, null, null,
                    globalDiscount, true,
                    lines.stream().map(DocumentRequest.LineRequest::toCommand).toList());
        }
    }

    public record CreditOverride(
            @jakarta.validation.constraints.NotBlank
            @jakarta.validation.constraints.Size(max = 500) String reason) {}

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
