package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final DocumentService service;
    private final DocumentFiscalQrService fiscalQr;

    public TicketController(DocumentService service, DocumentFiscalQrService fiscalQr) {
        this.service = service;
        this.fiscalQr = fiscalQr;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_READ')")
    public List<DocumentView> list() {
        return service.listTickets().stream().map(this::view).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_CREATE')")
    public DocumentView create(
            @Valid @RequestBody CreateTicketRequest request,
            Authentication authentication) {
        return view(service.createTicket(
                request.document().toCommand(),
                request.paymentCommands(),
                authentication));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_CANCEL')")
    public DocumentView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelRequest request,
            Authentication authentication) {
        return view(service.cancelTicket(
                id, authentication, request.reason()));
    }

    @PostMapping("/{id}/invoice")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('GESTION_VENTAS')")
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
            @Valid @RequestBody DeliveryNoteController.AdminEditRequest request) {
        return view(service.adminEditConfirmed(
                id, request.descuentoGlobal(), request.clienteId(), request.proveedorId(),
                request.lineas().stream().map(DocumentRequest.LineRequest::toCommand).toList()));
    }

    private DocumentView view(CommercialDocument document) {
        return DocumentView.from(document, fiscalQr.qrUrl(document.getId()));
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
}
