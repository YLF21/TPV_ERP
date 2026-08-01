package com.tpverp.backend.document;

import com.tpverp.backend.security.application.PermissionChecks;
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
    private final GenericSalesApiService genericSales;
    private final DocumentViewAssembler views;

    public DeliveryNoteController(
            DocumentService service,
            GenericSalesApiService genericSales,
            DocumentViewAssembler views) {
        this.service = service;
        this.genericSales = genericSales;
        this.views = views;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public List<DocumentView> list(Authentication authentication) {
        return service.listDeliveryNotes(
                        PermissionChecks.hasSalesDocumentRead(authentication, "DELIVERY_NOTES_READ"),
                        PermissionChecks.hasPurchaseDocumentRead(authentication)).stream()
                .map(views::documentView)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_WRITE','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN')")
    public DocumentView create(
            @Valid @RequestBody DocumentRequest request,
            Authentication authentication) {
        return views.documentView(genericSales.createDeliveryNote(
                request, authentication));
    }

    @PostMapping("/confirmed")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_WRITE','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN')")
    public DocumentView createAndConfirm(
            @Valid @RequestBody DocumentRequest request,
            Authentication authentication) {
        return DocumentView.from(genericSales.createAndConfirmDeliveryNote(
                request, authentication));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_CONFIRM','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN')")
    public DocumentView confirm(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false)
                    SaleOperationAuthorizationsRequest authorizations,
            Authentication authentication) {
        return views.documentView(genericSales.confirm(id, authorizations, authentication));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA')")
    public DocumentView pay(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        return views.documentView(genericSales.payDeliveryNote(id, request, authentication));
    }

    @PutMapping("/{id}/admin")
    @PreAuthorize("hasRole('ADMIN')")
    public DocumentView adminEdit(
            @PathVariable UUID id,
            @Valid @RequestBody AdminEditRequest request,
            Authentication authentication) {
        return views.documentView(service.adminEditConfirmed(
                id, request.descuentoGlobal(), request.clienteId(), request.proveedorId(),
                request.lineas().stream().map(DocumentRequest.LineRequest::toCommand).toList(),
                authentication));
    }

    public record AdminEditRequest(
            @NotNull BigDecimal descuentoGlobal,
            UUID clienteId,
            UUID proveedorId,
            @NotEmpty @Valid List<DocumentRequest.LineRequest> lineas) {
    }
}
