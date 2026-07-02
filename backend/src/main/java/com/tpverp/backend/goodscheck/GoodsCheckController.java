package com.tpverp.backend.goodscheck;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/goods-checks")
public class GoodsCheckController {

    private static final String CHECK_PERMISSION = "hasRole('ADMIN') or hasAnyAuthority("
            + "'GESTION_PRODUCTO','DELIVERY_NOTES_READ','DELIVERY_NOTES_WRITE',"
            + "'INVOICES_READ','INVOICES_WRITE')";

    private final GoodsCheckService service;

    public GoodsCheckController(GoodsCheckService service) {
        this.service = service;
    }

    @PostMapping("/documents/{documentId}/start")
    @PreAuthorize(CHECK_PERMISSION)
    public GoodsCheckView start(@PathVariable UUID documentId, Authentication authentication) {
        return service.start(documentId, authentication);
    }

    @GetMapping("/{id}")
    @PreAuthorize(CHECK_PERMISSION)
    public GoodsCheckView get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/scan")
    @PreAuthorize(CHECK_PERMISSION)
    public GoodsCheckView scan(
            @PathVariable UUID id,
            @Valid @RequestBody GoodsCheckScanRequest request,
            Authentication authentication) {
        return service.scan(id, request, authentication);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(CHECK_PERMISSION)
    public GoodsCheckView close(@PathVariable UUID id, Authentication authentication) {
        return service.close(id, authentication);
    }
}
