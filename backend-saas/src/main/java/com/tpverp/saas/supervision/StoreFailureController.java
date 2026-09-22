package com.tpverp.saas.supervision;

import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Protected by the existing /api/v1/admin interceptor and VIEW_ADMIN_DATA. */
@RestController
@RequestMapping("/api/v1/admin/supervision/failures")
public class StoreFailureController {
    private final StoreFailureQueryService service;
    public StoreFailureController(StoreFailureQueryService service) { this.service = service; }

    @GetMapping
    public StoreFailureQueryService.Page page(@RequestParam(required = false) UUID companyId,
            @RequestParam(required = false) UUID storeId, @RequestParam(required = false) UUID installationId,
            @RequestParam(required = false) String source, @RequestParam(required = false) String status,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "true") boolean activeStoresOnly, @RequestParam(required = false) String q,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int size) {
        return service.page(new StoreFailureQueryService.Filter(companyId, storeId, installationId,
                source, status, from, to, activeStoresOnly, q), cursor, size);
    }

    @GetMapping("/{id}")
    public StoreFailureView detail(@PathVariable String id) { return service.detail(id); }
}
