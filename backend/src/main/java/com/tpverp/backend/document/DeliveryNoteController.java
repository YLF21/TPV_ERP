package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

@RestController
@RequestMapping("/api/v1/delivery-notes")
public class DeliveryNoteController {

    private final DocumentService service;

    public DeliveryNoteController(DocumentService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('DELIVERY_NOTES_READ','VENTA')")
    public List<DocumentView> list() {
        return service.listDeliveryNotes().stream().map(DocumentView::from).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('DELIVERY_NOTES_WRITE','VENTA')")
    public DocumentView create(
            @Valid @RequestBody DocumentRequest request,
            Authentication authentication) {
        return DocumentView.from(service.createDeliveryNote(
                request.toCommand(), authentication));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('DELIVERY_NOTES_CONFIRM','VENTA')")
    public DocumentView confirm(
            @PathVariable UUID id,
            Authentication authentication) {
        return DocumentView.from(service.confirm(id, authentication));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView pay(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        return DocumentView.from(service.payDeliveryNote(id, request.toCommands(), authentication));
    }

    @PutMapping("/{id}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentView adminEdit(
            @PathVariable UUID id,
            @Valid @RequestBody AdminEditRequest request) {
        return DocumentView.from(service.adminEditConfirmed(
                id, request.descuentoGlobal(), request.clienteId(), request.proveedorId(),
                request.lineas().stream().map(DocumentRequest.LineRequest::toCommand).toList()));
    }

    public record AdminEditRequest(
            @NotNull BigDecimal descuentoGlobal,
            UUID clienteId,
            UUID proveedorId,
            @NotEmpty @Valid List<DocumentRequest.LineRequest> lineas) {
    }
}
