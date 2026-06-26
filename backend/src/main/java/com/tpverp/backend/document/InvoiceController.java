package com.tpverp.backend.document;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invoices")
public class InvoiceController {

    private final DocumentService service;
    private final DocumentFiscalQrService fiscalQr;

    public InvoiceController(DocumentService service, DocumentFiscalQrService fiscalQr) {
        this.service = service;
        this.fiscalQr = fiscalQr;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICES_READ')")
    public List<DocumentView> list() {
        return service.listInvoices().stream().map(this::view).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICES_WRITE')")
    public DocumentView create(
            @Valid @RequestBody DocumentRequest request,
            Authentication authentication) {
        return view(service.createInvoice(request.toCommand(), authentication));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICES_CONFIRM')")
    public DocumentView confirm(
            @PathVariable UUID id,
            Authentication authentication) {
        return view(service.confirm(id, authentication));
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICES_PAY')")
    public DocumentView pay(
            @PathVariable UUID id,
            @Valid @RequestBody PaymentRequest request,
            Authentication authentication) {
        return view(service.payInvoice(id, request.toCommands(), authentication));
    }

    @PostMapping("/{id}/relations")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICES_WRITE')")
    public DocumentView relate(
            @PathVariable UUID id,
            @Valid @RequestBody RelationRequest request) {
        return view(service.relate(id, request.originId(), request.type()));
    }

    private DocumentView view(Documento document) {
        return DocumentView.from(document, fiscalQr.qrUrl(document.getId()));
    }

    public record RelationRequest(
            @NotNull UUID originId,
            @NotNull TipoRelacionDocumento type) {
    }
}
