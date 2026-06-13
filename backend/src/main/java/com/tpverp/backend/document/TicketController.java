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

    public TicketController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_READ')")
    public List<DocumentView> list() {
        return service.listTickets().stream().map(DocumentView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_CREATE')")
    public DocumentView create(
            @Valid @RequestBody CreateTicketRequest request,
            Authentication authentication) {
        return DocumentView.from(service.createTicket(
                request.document().toCommand(),
                request.payments().toCommands(),
                authentication));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('TICKETS_CANCEL')")
    public DocumentView cancel(
            @PathVariable UUID id,
            @Valid @RequestBody CancelRequest request,
            Authentication authentication) {
        return DocumentView.from(service.cancelTicket(
                id, authentication, request.reason()));
    }

    @PutMapping("/{id}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentView adminEdit(
            @PathVariable UUID id,
            @Valid @RequestBody DeliveryNoteController.AdminEditRequest request) {
        return DocumentView.from(service.adminEditConfirmed(
                id, request.descuentoGlobal(), request.clienteId(), request.proveedorId(),
                request.lineas().stream().map(DocumentRequest.LineRequest::toCommand).toList()));
    }

    public record CreateTicketRequest(
            @NotNull @Valid DocumentRequest document,
            @NotNull @Valid PaymentRequest payments) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }
}
