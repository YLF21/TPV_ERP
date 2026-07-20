package com.tpverp.backend.document;

import com.tpverp.backend.terminal.PaymentTerminalResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-receivables")
public class CustomerReceivableController {

    private static final String READ_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_READ')";
    private static final String PAY_PERMISSION =
            "hasRole('ADMIN') or hasAuthority('CUSTOMER_RECEIVABLES_PAY')";

    private final CustomerReceivableService service;
    private final CustomerReceivablePrintService printing;

    public CustomerReceivableController(CustomerReceivableService service, CustomerReceivablePrintService printing) {
        this.service = service;
        this.printing = printing;
    }

    @GetMapping("")
    @PreAuthorize(READ_PERMISSION)
    public List<CustomerReceivableView> list(
            @Valid @ModelAttribute CustomerReceivableFilter filter,
            Authentication authentication) {
        return service.list(filter, authentication);
    }

    @GetMapping("/{documentId}")
    @PreAuthorize(READ_PERMISSION)
    public CustomerReceivableView detail(
            @PathVariable UUID documentId, Authentication authentication) {
        return service.detail(documentId, authentication);
    }

    @GetMapping("/payment-history")
    @PreAuthorize(READ_PERMISSION)
    public List<CustomerReceivablePaymentHistoryView> paymentHistory(
            @Valid @ModelAttribute CustomerReceivablePaymentHistoryFilter filter,
            Authentication authentication) {
        return service.paymentHistory(filter, authentication);
    }

    @GetMapping("/{documentId}/print-document")
    @PreAuthorize(READ_PERMISSION)
    public CustomerReceivablePrintService.CommercialDocumentPrint printDocument(
            @PathVariable UUID documentId) { return printing.document(documentId); }

    @GetMapping("/{documentId}/payments/{paymentId}/receipt")
    @PreAuthorize(READ_PERMISSION)
    public CustomerReceivablePrintService.PaymentReceipt paymentReceipt(
            @PathVariable UUID documentId, @PathVariable UUID paymentId) {
        return printing.paymentReceipt(documentId, paymentId);
    }

    @GetMapping("/{documentId}/payments/{paymentId}/print")
    @PreAuthorize(READ_PERMISSION)
    public CustomerReceivablePrintService.PaymentReceipt printPayment(
            @PathVariable UUID documentId, @PathVariable UUID paymentId) {
        return printing.paymentReceiptByPaymentId(documentId, paymentId);
    }

    @PostMapping("/{documentId}/card-charges")
    @PreAuthorize(PAY_PERMISSION)
    public PaymentTerminalResult chargeCard(
            @PathVariable UUID documentId,
            @Valid @RequestBody CardChargeRequest request,
            Authentication authentication) {
        return service.chargeCard(documentId, request, authentication);
    }

    @PostMapping("/{documentId}/card-charges/{paymentId}/query")
    @PreAuthorize(PAY_PERMISSION)
    public PaymentTerminalResult queryCard(
            @PathVariable UUID documentId, @PathVariable UUID paymentId,
            Authentication authentication) {
        return service.queryCard(documentId, paymentId, authentication);
    }

    @PostMapping("/{documentId}/payments")
    @PreAuthorize(PAY_PERMISSION)
    public PaymentResponse pay(
            @PathVariable UUID documentId,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        var receivable = service.pay(documentId, request, authentication);
        var paymentId = request.pagos().getFirst().requestId();
        return new PaymentResponse(receivable, printing.paymentReceipt(documentId, paymentId));
    }

    public record PaymentResponse(CustomerReceivableView receivable,
            CustomerReceivablePrintService.PaymentReceipt paymentReceipt) {}

    public record CardChargeRequest(
            @NotNull UUID paymentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }
}
