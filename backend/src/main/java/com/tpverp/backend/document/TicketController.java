package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
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
    private final DocumentAttributionResolver attributions;

    public TicketController(
            DocumentService service,
            DocumentFiscalQrService fiscalQr,
            DocumentAttributionResolver attributions) {
        this.service = service;
        this.fiscalQr = fiscalQr;
        this.attributions = attributions;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','TICKETS_READ','VENTA')")
    public List<DocumentView> list() {
        var documents = service.listTickets();
        var attributionIndex = attributions.resolve(documents);
        var views = new ArrayList<DocumentView>(documents.size());
        for (var document : documents) {
            views.add(view(document, attributionIndex.get(document.getId())));
        }
        return List.copyOf(views);
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
        var attribution = attributions.resolve(List.of(document)).get(document.getId());
        return view(document, attribution);
    }

    private DocumentView view(
            CommercialDocument document,
            DocumentAttributionResolver.Attribution attribution) {
        return DocumentView.from(document, fiscalQr.qrUrl(document.getId()), attribution);
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
