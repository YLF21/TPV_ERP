package com.tpverp.backend.document;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tpverp.backend.security.sales.OperationAuthorizationRequest;
import com.tpverp.backend.security.sales.SaleOperationCode;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final DocumentService service;
    private final DocumentFiscalQrService fiscalQr;
    private final DocumentViewAssembler views;
    private final TicketReturnService returns;
    private final TicketCancellationService cancellations;
    private final GenericSalesApiService genericSales;
    private final PreviousTicketImportService previousTicketImports;

    public TicketController(
            DocumentService service,
            DocumentFiscalQrService fiscalQr,
            DocumentViewAssembler views,
            TicketReturnService returns,
            TicketCancellationService cancellations,
            GenericSalesApiService genericSales,
            PreviousTicketImportService previousTicketImports) {
        this.service = service;
        this.fiscalQr = fiscalQr;
        this.views = views;
        this.returns = returns;
        this.cancellations = cancellations;
        this.genericSales = genericSales;
        this.previousTicketImports = previousTicketImports;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public List<DocumentView> list() {
        var documents = service.listTickets();
        return views.documentViews(documents, fiscalQr::qrUrl);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_CREATE','VENTA')")
    public DocumentView create(
            @Valid @RequestBody CreateTicketRequest request,
            Authentication authentication) {
        return view(genericSales.createTicket(request, authentication));
    }

    @GetMapping("/{id}/print")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public TicketPrintView print(@PathVariable UUID id) {
        return service.loadTicketPrintView(id);
    }

    @GetMapping("/{id}/return-options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public List<DocumentService.CardRefundLineOption> returnOptions(@PathVariable UUID id) {
        return returns.options(id);
    }

    @GetMapping("/return-preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public ReturnPreviewView returnPreview(@RequestParam String ticketNumber) {
        return ReturnPreviewView.from(returns.preview(ticketNumber));
    }

    @PostMapping("/return-valuation")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public ReturnValuationView returnValuation(
            @Valid @RequestBody ReturnValuationRequest request) {
        var selections = new java.util.ArrayList<TicketReturnService.ReturnSelection>();
        for (var line : request.lines()) {
            selections.add(new TicketReturnService.ReturnSelection(
                    line.lineId(), line.quantity()));
        }
        return ReturnValuationView.from(
                returns.value(request.ticketNumber(), List.copyOf(selections)));
    }

    @PostMapping("/{id}/returns")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_CREATE','VENTA')")
    public ReturnView createReturn(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest request,
            Authentication authentication) {
        var cards = new java.util.ArrayList<TicketReturnService.CardPayout>();
        if (request.cards() != null) {
            for (var card : request.cards()) {
                cards.add(new TicketReturnService.CardPayout(
                        card.originalPaymentId(), card.operationId(),
                        card.idempotencyKey(), card.amount()));
            }
        }
        var lines = new java.util.ArrayList<PaymentTerminalRefundLineSelection>();
        if (request.lines() != null) {
            for (var line : request.lines()) {
                lines.add(new PaymentTerminalRefundLineSelection(
                        line.lineId(), line.quantity(), line.serialNumbers()));
            }
        }
        var result = returns.create(
                id,
                request.requestId(),
                request.cashAmount(),
                request.voucherAmount(),
                cards,
                lines,
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication);
        return ReturnView.from(result, service.ticketPrintView(
                result.document(), result.payouts()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS','TICKETS_CANCEL','VENTA')")
    public CancellationView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelRequest request,
            Authentication authentication) {
        var result = cancellations.cancel(
                new TicketCancellationService.CancellationCommand(
                        request.requestId(),
                        id,
                        request.reason(),
                        request.authorizerUsername(),
                        request.authorizerPassword(),
                        request.manualCompensations()),
                authentication);
        return CancellationView.from(result, this::view);
    }

    @GetMapping("/cancellation-preview/last")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS','TICKETS_CANCEL','VENTA')")
    public CancellationPreviewView lastCancellationPreview(
            Authentication authentication) {
        return CancellationPreviewView.from(
                cancellations.latestPreview(authentication), this::view);
    }

    @GetMapping("/cancellation-preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','GESTION_CUENTAS','TICKETS_CANCEL','VENTA')")
    public CancellationPreviewView cancellationPreview(
            @RequestParam String number) {
        return CancellationPreviewView.from(
                cancellations.previewByNumber(number), this::view);
    }

    @GetMapping("/last-current-terminal")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView lastCurrentTerminal(Authentication authentication) {
        return view(cancellations.latestConvertibleTicket(authentication));
    }

    @GetMapping("/previous-current-terminal/import-preview")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('VENTA','TICKETS_CREATE')")
    public PreviousTicketImportView previousCurrentTerminalImportPreview(
            Authentication authentication) {
        return previousTicketImports.preview(authentication);
    }

    @GetMapping("/by-number")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView byNumber(@RequestParam String number) {
        return view(cancellations.ticketByNumber(number));
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView convertToInvoice(
            @PathVariable UUID id,
            @Valid @RequestBody ConvertToInvoiceRequest request,
            Authentication authentication) {
        return view(service.convertTicketToInvoice(
                id,
                request.customerId(),
                request.authorizerUsername(),
                request.authorizerPassword(),
                authentication));
    }

    @PutMapping("/{id}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentView adminEdit(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryNoteController.AdminEditRequest request,
            Authentication authentication) {
        return view(service.adminEditConfirmed(
                id, request.descuentoGlobal(), request.clienteId(), request.proveedorId(),
                request.lineas().stream().map(DocumentRequest.LineRequest::toCommand).toList(),
                authentication));
    }

    private DocumentView view(CommercialDocument document) {
        return views.documentView(document, fiscalQr.qrUrl(document.getId()));
    }

    public record CreateTicketRequest(
            @NotNull @Valid DocumentRequest document,
            @Valid PaymentRequest payments,
            @Size(max = 32)
            @Valid Map<@NotNull SaleOperationCode, @NotNull @Valid OperationAuthorizationRequest>
                    operationAuthorizations) {

        public CreateTicketRequest {
            operationAuthorizations = OperationAuthorizationRequest.immutableCopy(
                    operationAuthorizations);
        }

        public CreateTicketRequest(
                DocumentRequest document,
                PaymentRequest payments) {
            this(document, payments, Map.of());
        }

        List<PaymentCommand> paymentCommands() {
            return payments == null ? List.of() : payments.toCommands();
        }
    }

    public record CancelRequest(
            @NotNull UUID requestId,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword,
            Map<String, String> manualCompensations) {

        @Override
        public String toString() {
            return "CancelRequest[requestId=" + requestId
                    + ", reason=" + reason
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record ConvertToInvoiceRequest(
            @NotNull UUID customerId,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @Size(max = 128) String authorizerPassword) {

        @Override
        public String toString() {
            return "ConvertToInvoiceRequest[customerId=" + customerId
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>]";
        }
    }

    public record ReturnRequest(
            @NotNull UUID requestId,
            @Size(max = 128) String authorizerUsername,
            @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
            @JsonAlias("password") @Size(max = 128) String authorizerPassword,
            @NotNull @DecimalMin("0.00") BigDecimal cashAmount,
            @DecimalMin("0.00") BigDecimal voucherAmount,
            @Valid List<CardReturnRequest> cards,
            @Valid List<ReturnLineRequest> lines) {

        @Override
        public String toString() {
            return "ReturnRequest[requestId=" + requestId
                    + ", authorizerUsername=" + authorizerUsername
                    + ", authorizerPassword=<redacted>"
                    + ", cashAmount=" + cashAmount
                    + ", voucherAmount=" + voucherAmount + "]";
        }
    }

    public record CardReturnRequest(
            @NotNull UUID originalPaymentId,
            @NotNull UUID operationId,
            @NotBlank String idempotencyKey,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    public record ReturnLineRequest(
            @NotNull UUID lineId,
            @NotNull @DecimalMin("0.001") BigDecimal quantity,
            List<String> serialNumbers) {
        public ReturnLineRequest(UUID lineId, BigDecimal quantity) {
            this(lineId, quantity, List.of());
        }
    }

    public record ReturnValuationRequest(
            @NotBlank String ticketNumber,
            @NotNull @Size(min = 1) List<@Valid ReturnLineRequest> lines) {
    }

    public record ReturnValuationView(
            BigDecimal selectedGross,
            BigDecimal lostBenefits,
            BigDecimal refundableAmount,
            BigDecimal eligibleRefundableAmount,
            BigDecimal cumulativeEligibleRefundableAmount,
            BigDecimal cumulativeRefundableAmount,
            BigDecimal previouslyRefundedAmount,
            BigDecimal remainingBasketValue) {
        static ReturnValuationView from(
                TicketReturnValuationService.Valuation valuation) {
            return new ReturnValuationView(
                    valuation.selectedGross(),
                    valuation.lostBenefits(),
                    valuation.refundableAmount(),
                    valuation.eligibleRefundableAmount(),
                    valuation.cumulativeEligibleRefundableAmount(),
                    valuation.cumulativeRefundableAmount(),
                    valuation.previouslyRefundedAmount(),
                    valuation.remainingBasketValue());
        }
    }

    public record ReturnPayoutView(
            String type,
            BigDecimal amount,
            UUID originalPaymentId,
            UUID terminalOperationId,
            String reference) {
        static ReturnPayoutView from(RefundTender payout) {
            return new ReturnPayoutView(
                    payout.getType().name(), payout.getAmount(), payout.getOriginalPaymentId(),
                    payout.getTerminalOperationId(), payout.getReference());
        }
    }

    public record ReturnPreviewView(
            String sourceType,
            String sourceCode,
            UUID ticketId,
            String ticketNumber,
            java.time.LocalDate date,
            BigDecimal total,
            List<TicketReturnService.ReturnLineOption> lines,
            List<DocumentView.PaymentView> payments,
            List<RefundPaymentAvailability.View> paymentAvailability) {
        static ReturnPreviewView from(TicketReturnService.ReturnPreview preview) {
            var ticket = preview.ticket();
            return new ReturnPreviewView(
                    preview.sourceType().name(),
                    preview.sourceCode(),
                    ticket.getId(),
                    ticket.getNumero(),
                    ticket.getFecha(),
                    ticket.getTotal(),
                    preview.lines(),
                    ticket.getPagos().stream()
                            .sorted(java.util.Comparator.comparingInt(DocumentPayment::getPosicion))
                            .map(DocumentView.PaymentView::from)
                            .toList(),
                    preview.paymentAvailability());
        }
    }

    public record ReturnView(
            UUID documentId,
            String documentNumber,
            BigDecimal total,
            List<ReturnPayoutView> payouts,
            String voucherCode,
            TicketPrintView receipt) {
        static ReturnView from(
                TicketReturnService.ReturnResult result, TicketPrintView receipt) {
            return new ReturnView(
                    result.document().getId(),
                    result.document().getNumero(),
                    result.document().getTotal(),
                    result.payouts().stream().map(ReturnPayoutView::from).toList(),
                    result.voucher().map(Voucher::code).orElse(null),
                    receipt);
        }
    }

    public record CancellationPreviewView(
            DocumentView ticket,
            List<DocumentService.ManualCancellationReference> manualReferences,
            List<DocumentService.IntegratedCardCancellation> integratedCardPayments,
            BigDecimal cashAmount,
            boolean openCashDrawer,
            List<String> consumedVoucherCodes,
            List<String> generatedVoucherCodes) {

        static CancellationPreviewView from(
                DocumentService.TicketCancellationValidation validation,
                java.util.function.Function<CommercialDocument, DocumentView> view) {
            return new CancellationPreviewView(
                    view.apply(validation.ticket()),
                    validation.manualReferences(),
                    validation.integratedCardPayments(),
                    validation.cashAmount(),
                    validation.openCashDrawer(),
                    validation.vouchers().consumedVoucherCodes(),
                    validation.vouchers().generatedVoucherCodes());
        }
    }

    public record CancellationView(
            DocumentView ticket,
            List<TicketCancellationService.RestoredVoucher> restoredVouchers,
            List<String> invalidatedVoucherCodes,
            boolean openCashDrawer,
            List<TicketCancellationService.CardAdjustment> cardAdjustments,
            TicketCancellationService.CancellationReceipt receipt) {

        static CancellationView from(
                TicketCancellationService.CancellationResult result,
                java.util.function.Function<CommercialDocument, DocumentView> view) {
            return new CancellationView(
                    view.apply(result.ticket()),
                    result.restoredVouchers(),
                    result.invalidatedVoucherCodes(),
                    result.openCashDrawer(),
                    result.cardAdjustments(),
                    result.receipt());
        }
    }
}
