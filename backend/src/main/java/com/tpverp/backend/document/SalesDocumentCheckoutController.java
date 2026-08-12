package com.tpverp.backend.document;

import com.tpverp.backend.security.application.CorePermissionBootstrap;
import com.tpverp.backend.security.application.PermissionChecks;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pos/sales-document-checkouts")
public class SalesDocumentCheckoutController {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            SalesDocumentCheckoutController.class);

    private static final String ACCESS =
            "hasRole('ADMIN') or hasAnyAuthority('VENTA','GESTION_VENTAS',"
                    + "'INVOICES_WRITE','DELIVERY_NOTES_WRITE')";

    private final CustomerPendingSaleService service;
    private final CustomerReceivablePrintService printing;
    private final DocumentViewAssembler views;

    public SalesDocumentCheckoutController(
            CustomerPendingSaleService service,
            CustomerReceivablePrintService printing,
            DocumentViewAssembler views) {
        this.service = service;
        this.printing = printing;
        this.views = views;
    }

    @PostMapping("/quote")
    @PreAuthorize(ACCESS)
    public CustomerPendingSaleService.Quote quote(
            @Valid @RequestBody CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        requireCompletionMode(request);
        requireDocumentAccess(request, authentication);
        return service.quote(request, authentication);
    }

    @PostMapping("/card-charges")
    @PreAuthorize(ACCESS)
    public com.tpverp.backend.terminal.PaymentTerminalResult chargeCard(
            @Valid @RequestBody CustomerPendingSaleController.CardChargeRequest request,
            Authentication authentication) {
        requireCompletionMode(request.sale());
        if (request.sale().completionMode()
                != CustomerPendingSaleController.SalesDocumentCompletionMode.CONFIRM_AND_PAY) {
            throw new IllegalArgumentException(
                    "sales_document_card_requires_confirm_and_pay");
        }
        requireDocumentAccess(request.sale(), authentication);
        return service.chargeCard(request, authentication);
    }

    @PostMapping
    @PreAuthorize(ACCESS)
    public Result create(
            @Valid @RequestBody CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        requireCompletionMode(request);
        requireDocumentAccess(request, authentication);
        var document = service.createDocument(request, authentication);
        var printable = request.completionMode()
                == CustomerPendingSaleController.SalesDocumentCompletionMode.DRAFT
                ? null : preparePrintDocument(printing, document.getId());
        return new Result(views.documentView(document), printable);
    }

    static CustomerReceivablePrintService.CommercialDocumentPrint preparePrintDocument(
            CustomerReceivablePrintService printing,
            UUID documentId) {
        try {
            return printing.document(documentId);
        } catch (RuntimeException failure) {
            // The document mutation has already committed. A printer/template failure must not
            // turn a confirmed sale into an apparently failed sale or encourage a duplicate retry.
            LOGGER.error("Could not prepare print data for confirmed document {}", documentId,
                    failure);
            return null;
        }
    }

    private static void requireCompletionMode(
            CustomerPendingSaleController.CreateRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.completionMode() == null) {
            throw new IllegalArgumentException(
                    "sales_document_completion_mode_required");
        }
    }

    static void requireDocumentAccess(
            CustomerPendingSaleController.CreateRequest request,
            Authentication authentication) {
        var specificPermission = request.type() == CommercialDocumentType.FACTURA_VENTA
                ? CorePermissionBootstrap.INVOICES_WRITE
                : request.type() == CommercialDocumentType.ALBARAN_VENTA
                        ? CorePermissionBootstrap.DELIVERY_NOTES_WRITE
                        : null;
        if (specificPermission == null
                || (!PermissionChecks.hasRole(authentication, "ADMIN")
                        && !PermissionChecks.hasAnyAuthority(
                                authentication,
                                CorePermissionBootstrap.VENTA,
                                CorePermissionBootstrap.GESTION_VENTAS,
                                specificPermission))) {
            throw new AccessDeniedException(
                    "sales_document_checkout_permission_required");
        }
    }

    public record Result(
            DocumentView document,
            CustomerReceivablePrintService.CommercialDocumentPrint printDocument) {}
}
