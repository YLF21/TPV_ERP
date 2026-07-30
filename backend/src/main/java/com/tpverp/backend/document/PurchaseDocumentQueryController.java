package com.tpverp.backend.document;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/purchase-documents")
public class PurchaseDocumentQueryController {

    private final PurchaseDocumentQueryService service;

    public PurchaseDocumentQueryController(PurchaseDocumentQueryService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS')")
    public List<PurchaseDocumentQueryService.PurchaseDocumentView> list(
            @RequestParam CommercialDocumentType type) {
        return service.list(type);
    }
}
