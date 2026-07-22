package com.tpverp.backend.document;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/invoices")
public class SalesInvoiceRectificationController {

    private final SalesInvoiceRectificationService rectifications;
    private final DocumentService documents;
    private final DocumentViewAssembler views;
    private final DocumentFiscalQrService fiscalQr;

    public SalesInvoiceRectificationController(
            SalesInvoiceRectificationService rectifications,
            DocumentService documents,
            DocumentViewAssembler views,
            DocumentFiscalQrService fiscalQr) {
        this.rectifications = rectifications;
        this.documents = documents;
        this.views = views;
        this.fiscalQr = fiscalQr;
    }

    @GetMapping("/{id}/rectification-source")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','INVOICES_WRITE'))")
    public SalesInvoiceRectificationSourceView source(@PathVariable UUID id) {
        return rectifications.source(id);
    }

    @PostMapping("/{id}/rectifications")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_WRITE'))")
    public SalesInvoiceRectificationView create(
            @PathVariable UUID id,
            @Valid @RequestBody SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        return view(rectifications.createDraft(id, request, authentication));
    }

    @PostMapping("/{id}/rectifications/preview")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_WRITE'))")
    public SalesInvoiceRectificationView preview(
            @PathVariable UUID id,
            @Valid @RequestBody SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        return view(rectifications.preview(id, request, authentication));
    }

    @GetMapping("/rectifications/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','INVOICES_WRITE'))")
    public SalesInvoiceRectificationView details(@PathVariable UUID id) {
        return view(rectifications.details(id));
    }

    @PutMapping("/rectifications/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_WRITE'))")
    public SalesInvoiceRectificationView update(
            @PathVariable UUID id,
            @Valid @RequestBody SalesInvoiceRectificationRequest request,
            Authentication authentication) {
        return view(rectifications.updateDraft(id, request, authentication));
    }

    @PostMapping("/rectifications/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','INVOICES_CONFIRM'))")
    public SalesInvoiceRectificationView confirm(
            @PathVariable UUID id,
            Authentication authentication) {
        documents.confirm(id, authentication);
        return view(rectifications.details(id));
    }

    private SalesInvoiceRectificationView view(
            SalesInvoiceRectificationService.Details details) {
        var document = details.document();
        var metadata = details.metadata();
        return new SalesInvoiceRectificationView(
                views.documentView(document, fiscalQr.qrUrl(document.getId())),
                rectifications.source(details.original().getId()),
                metadata.getFiscalType(), metadata.getMethod(), metadata.getReason(),
                metadata.getDetail(), metadata.isAffectsStock(),
                document.getLineas().stream().map(line ->
                        new SalesInvoiceRectificationView.LineView(
                                line.getId(), line.getOriginalDocumentLineId(), line.getLineType(),
                                line.getCodigo(), line.getNombre(), line.getCantidad(),
                                line.getPrecioUnitario(), line.getBase(), line.getImpuesto(),
                                line.getTotal())).toList());
    }
}
