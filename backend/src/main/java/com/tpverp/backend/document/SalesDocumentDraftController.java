package com.tpverp.backend.document;

import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.PermissionChecks;
import jakarta.validation.Valid;
import java.util.EnumSet;
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
@RequestMapping("/api/v1/pos/sales-document-drafts")
public class SalesDocumentDraftController {

    private static final String ACCESS =
            "hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS',"
                    + "'INVOICES_WRITE','DELIVERY_NOTES_WRITE')";

    private final SalesDocumentDraftQueryService drafts;
    private final CustomerPendingSaleService service;
    private final CustomerReceivablePrintService printing;
    private final DocumentViewAssembler views;

    public SalesDocumentDraftController(
            SalesDocumentDraftQueryService drafts,
            CustomerPendingSaleService service,
            CustomerReceivablePrintService printing,
            DocumentViewAssembler views) {
        this.drafts = drafts;
        this.service = service;
        this.printing = printing;
        this.views = views;
    }

    @GetMapping
    @PreAuthorize(ACCESS)
    public List<SalesDocumentDraftSummaryView> list(Authentication authentication) {
        return drafts.list(allowedTypes(authentication));
    }

    @GetMapping("/{id}")
    @PreAuthorize(ACCESS)
    public SalesDocumentDraftView detail(
            @PathVariable UUID id, Authentication authentication) {
        return drafts.detail(id, allowedTypes(authentication));
    }

    @PostMapping("/{id}/quote")
    @PreAuthorize(ACCESS)
    public CustomerPendingSaleService.Quote quote(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        requireDraftVersion(request);
        SalesDocumentCheckoutController.requireDocumentAccess(request, authentication);
        return service.quoteDraft(id, request, authentication);
    }

    @PostMapping("/{id}/card-charges")
    @PreAuthorize(ACCESS)
    public com.tpverp.backend.terminal.PaymentTerminalResult chargeCard(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        requireDraftVersion(request.sale());
        SalesDocumentCheckoutController.requireDocumentAccess(request.sale(), authentication);
        return service.chargeDraftCard(id, request, authentication);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ACCESS)
    public SalesDocumentCheckoutController.Result update(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        requireDraftVersion(request);
        if (request.completionMode()
                != CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
            throw new IllegalArgumentException("sales_document_draft_mode_required");
        }
        SalesDocumentCheckoutController.requireDocumentAccess(request, authentication);
        var document = service.updateDraft(id, request, authentication);
        return new SalesDocumentCheckoutController.Result(views.documentView(document), null);
    }

    @PostMapping("/{id}")
    @PreAuthorize(ACCESS)
    public SalesDocumentCheckoutController.Result checkout(
            @PathVariable UUID id,
            @Valid @RequestBody CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        requireDraftVersion(request);
        if (request.completionMode() == null
                || request.completionMode()
                == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT) {
            throw new IllegalArgumentException("sales_document_checkout_mode_required");
        }
        SalesDocumentCheckoutController.requireDocumentAccess(request, authentication);
        var document = service.completeDraft(id, request, authentication);
        var printable = SalesDocumentCheckoutController.preparePrintDocument(
                printing, document.getId());
        return new SalesDocumentCheckoutController.Result(
                views.documentView(document), printable.document(), printable.errorCode());
    }

    private static void requireDraftVersion(
            CustomerPendingSaleController.CreateRequest request) {
        if (request.draftVersion() == null) {
            throw new IllegalArgumentException("sales_document_draft_version_required");
        }
    }

    private static EnumSet<CommercialDocumentType> allowedTypes(
            Authentication authentication) {
        var types = EnumSet.noneOf(CommercialDocumentType.class);
        var broad = PermissionChecks.hasRole(authentication, "ADMIN")
                || PermissionChecks.hasAnyAuthority(authentication,
                        CorePermissionBootstrap.VENTA,
                        CorePermissionBootstrap.GESTION_VENTAS);
        if (broad || PermissionChecks.hasAnyAuthority(authentication,
                CorePermissionBootstrap.INVOICES_WRITE)) {
            types.add(CommercialDocumentType.FACTURA_VENTA);
        }
        if (broad || PermissionChecks.hasAnyAuthority(authentication,
                CorePermissionBootstrap.DELIVERY_NOTES_WRITE)) {
            types.add(CommercialDocumentType.ALBARAN_VENTA);
        }
        return types;
    }
}
