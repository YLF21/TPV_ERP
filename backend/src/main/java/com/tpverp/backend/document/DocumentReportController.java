package com.tpverp.backend.document;

import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.shared.api.PagedResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-reports")
public class DocumentReportController {

    private final DocumentReportService service;

    public DocumentReportController(DocumentReportService service) {
        this.service = service;
    }

    @GetMapping("/invoices")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public PagedResult<DocumentReportView> invoices(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            Authentication authentication) {
        return service.listInvoices(
                limit,
                cursor,
                PermissionChecks.hasSalesDocumentRead(authentication, "INVOICES_READ"),
                PermissionChecks.hasPurchaseDocumentRead(authentication));
    }

    @GetMapping("/delivery-notes")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','DELIVERY_NOTES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public PagedResult<DocumentReportView> deliveryNotes(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            Authentication authentication) {
        return service.listDeliveryNotes(
                limit,
                cursor,
                PermissionChecks.hasSalesDocumentRead(authentication, "DELIVERY_NOTES_READ"),
                PermissionChecks.hasPurchaseDocumentRead(authentication));
    }
}
