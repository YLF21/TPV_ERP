package com.tpverp.backend.document;

import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentOperationalTimelineController {

    private final DocumentOperationalTimelineService timelines;

    public DocumentOperationalTimelineController(DocumentOperationalTimelineService timelines) {
        this.timelines = timelines;
    }

    @GetMapping("/{documentId}/operational-events")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('APP_GESTION_ACCESS') and hasAnyAuthority('GESTION_VENTAS','GESTION_PRODUCTO','GESTION_ALMACEN','GESTION_CUENTAS'))")
    public DocumentOperationalTimelineView timeline(
            @PathVariable UUID documentId,
            Authentication authentication) {
        return timelines.timeline(documentId, authentication);
    }
}
