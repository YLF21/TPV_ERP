package com.tpverp.saas.tenant;

import com.tpverp.saas.document.CommercialDocumentQuery;
import com.tpverp.saas.document.CommercialDocumentReadService;
import com.tpverp.saas.sync.AdminStockSnapshotView;
import com.tpverp.saas.sync.AdminSyncPage;
import com.tpverp.saas.sync.AdminSyncProjectionStatusView;
import com.tpverp.saas.sync.AdminSyncQueryService;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** User-authenticated, read-only adapters. No installation token is exposed to web/mobile clients. */
@RestController
@RequestMapping("/api/v1/tenant")
public class TenantSupervisionController {
    private final CommercialDocumentReadService documents;
    private final AdminSyncQueryService sync;

    public TenantSupervisionController(CommercialDocumentReadService documents, AdminSyncQueryService sync) {
        this.documents = documents;
        this.sync = sync;
    }

    @PostMapping("/documents/page")
    public ResponseEntity<CommercialDocumentQuery.Page> documents(@RequestBody DocumentPageRequest request) {
        TenantContext context = TenantContextHolder.current();
        if (request == null || request.storeIds() == null || request.storeIds().size() > 2000
                || request.storeIds().stream().anyMatch(java.util.Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seleccion de tiendas invalida");
        }
        if (!context.storeIds().containsAll(request.storeIds())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tienda no autorizada");
        }
        var scope = CommercialDocumentQuery.Scope.stores(context.companyId(), context.storeIds());
        var filter = new CommercialDocumentQuery.Filter(request.storeIds(), null, request.types(), request.statuses(),
                request.from(), request.to(), null, request.numberContains());
        var page = documents.page(scope, filter, new CommercialDocumentQuery.PageRequest(
                CommercialDocumentQuery.Order.newestFirst(), request.size(), request.cursor()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    @GetMapping("/stores/{storeId}/stock")
    public ResponseEntity<AdminSyncPage<AdminStockSnapshotView>> stock(@PathVariable UUID storeId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int size) {
        TenantContext context = requireStore(storeId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sync.stockPage(context.companyId(), storeId, cursor, size));
    }

    @GetMapping("/stores/{storeId}/sync-status")
    public ResponseEntity<AdminSyncProjectionStatusView> syncStatus(@PathVariable UUID storeId) {
        TenantContext context = requireStore(storeId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(sync.projectionStatus(context.companyId(), storeId));
    }

    private TenantContext requireStore(UUID storeId) {
        TenantContext context = TenantContextHolder.current();
        if (!context.storeIds().contains(storeId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tienda no autorizada");
        return context;
    }

    public record DocumentPageRequest(Set<UUID> storeIds, Set<CommercialDocumentQuery.Type> types,
            Set<CommercialDocumentQuery.Status> statuses, LocalDate from, LocalDate to,
            String numberContains, int size, String cursor) { }
}
