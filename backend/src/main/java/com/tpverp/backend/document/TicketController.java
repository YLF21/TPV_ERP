package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.tpverp.backend.terminal.PaymentTerminalReauthenticationService;
import com.tpverp.backend.terminal.PaymentTerminalRefundLineSelection;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.PAYMENT_TERMINAL_REFUND;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final DocumentService service;
    private final DocumentFiscalQrService fiscalQr;
    private final DocumentViewAssembler views;
    private final TicketReturnService returns;
    private final PaymentTerminalReauthenticationService reauthentication;

    public TicketController(
            DocumentService service,
            DocumentFiscalQrService fiscalQr,
            DocumentViewAssembler views,
            TicketReturnService returns,
            PaymentTerminalReauthenticationService reauthentication) {
        this.service = service;
        this.fiscalQr = fiscalQr;
        this.views = views;
        this.returns = returns;
        this.reauthentication = reauthentication;
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
        return view(service.createTicket(
                request.document().toCommand(),
                request.paymentCommands(),
                authentication));
    }

    @GetMapping("/{id}/print")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public TicketPrintView print(@PathVariable UUID id) {
        return TicketPrintView.from(service.loadForPrint(id));
    }

    @GetMapping("/{id}/return-options")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PAYMENT_TERMINAL_REFUND + "')")
    public List<DocumentService.CardRefundLineOption> returnOptions(@PathVariable UUID id) {
        return returns.options(id);
    }

    @PostMapping("/{id}/returns")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PAYMENT_TERMINAL_REFUND + "')")
    public ReturnView createReturn(
            @PathVariable UUID id,
            @Valid @RequestBody ReturnRequest request,
            Authentication authentication) {
        reauthentication.require(authentication, request.password());
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
                lines.add(new PaymentTerminalRefundLineSelection(line.lineId(), line.quantity()));
            }
        }
        var result = returns.create(
                id,
                request.requestId(),
                request.cashAmount(),
                request.voucherAmount(),
                cards,
                lines,
                authentication);
        return ReturnView.from(result);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_CANCEL')")
    public DocumentView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelRequest request,
            Authentication authentication) {
        return view(service.cancelTicket(
                id, authentication, request.reason()));
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView convertToInvoice(
            @PathVariable UUID id,
            @Valid @RequestBody ConvertToInvoiceRequest request,
            Authentication authentication) {
        return view(service.convertTicketToInvoice(
                id, request.customerId(), authentication));
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
            @Valid PaymentRequest payments) {

        List<PaymentCommand> paymentCommands() {
            return payments == null ? List.of() : payments.toCommands();
        }
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    public record ConvertToInvoiceRequest(@NotNull UUID customerId) {
    }

    public record ReturnRequest(
            @NotNull UUID requestId,
            String password,
            @NotNull @DecimalMin("0.00") BigDecimal cashAmount,
            @DecimalMin("0.00") BigDecimal voucherAmount,
            @Valid List<CardReturnRequest> cards,
            @Valid List<ReturnLineRequest> lines) {
    }

    public record CardReturnRequest(
            @NotNull UUID originalPaymentId,
            @NotNull UUID operationId,
            @NotBlank String idempotencyKey,
            @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    public record ReturnLineRequest(
            @NotNull UUID lineId,
            @NotNull @DecimalMin("0.001") BigDecimal quantity) {
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

    public record ReturnView(
            UUID documentId,
            String documentNumber,
            BigDecimal total,
            List<ReturnPayoutView> payouts,
            String voucherCode,
            TicketPrintView receipt) {
        static ReturnView from(TicketReturnService.ReturnResult result) {
            return new ReturnView(
                    result.document().getId(),
                    result.document().getNumero(),
                    result.document().getTotal(),
                    result.payouts().stream().map(ReturnPayoutView::from).toList(),
                    result.voucher().map(Voucher::code).orElse(null),
                    TicketPrintView.from(result.document(), result.payouts()));
        }
    }
}
