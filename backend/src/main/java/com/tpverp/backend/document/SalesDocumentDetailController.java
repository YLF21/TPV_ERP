package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class SalesDocumentDetailController {

    private final DocumentService documents;
    private final CustomerReceivablePrintService printing;
    private DocumentOperationAttributionService operationAttribution;

    @org.springframework.beans.factory.annotation.Autowired
    void setOperationAttribution(DocumentOperationAttributionService operationAttribution) {
        this.operationAttribution = operationAttribution;
    }

    public SalesDocumentDetailController(DocumentService documents,
            CustomerReceivablePrintService printing) {
        this.documents = documents;
        this.printing = printing;
    }

    @GetMapping("/{documentId}/detail")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','DELIVERY_NOTES_READ','VENTA','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public SalesDocumentDetailView detail(@PathVariable UUID documentId) {
        var document = documents.findDetailed(documentId);
        var originTicket = document.getTipo() == CommercialDocumentType.FACTURA_VENTA
                ? documents.findOriginTicket(documentId).orElse(null)
                : null;
        var view = SalesDocumentDetailView.from(document, originTicket);
        return operationAttribution == null ? view
                : view.withOperationAttribution(operationAttribution.resolve(document));
    }

    @GetMapping("/{documentId}/print-copy")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','INVOICES_READ','DELIVERY_NOTES_READ','VENTA','GESTION_CUENTAS')")
    public CustomerReceivablePrintService.CommercialDocumentPrint printCopy(
            @PathVariable UUID documentId) {
        return printing.document(documentId);
    }
}
