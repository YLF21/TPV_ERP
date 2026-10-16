package com.tpverp.saas.marketing;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/demo-requests")
public class DemoRequestAdminController {

    private final DemoRequestService service;

    public DemoRequestAdminController(DemoRequestService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<DemoRequestView>> latest() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.latest());
    }

    @GetMapping("/page")
    public ResponseEntity<DemoRequestPage> page(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) DemoRequestStatus status,
            @RequestParam(required = false) String query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.page(page, size, status, query));
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<DemoRequestView> status(@PathVariable UUID id,
            @Valid @RequestBody UpdateStatus request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.updateStatus(id, request.status(), request.expectedStatus()));
    }

    public record UpdateStatus(@NotNull DemoRequestStatus status, @NotNull DemoRequestStatus expectedStatus) { }
}
