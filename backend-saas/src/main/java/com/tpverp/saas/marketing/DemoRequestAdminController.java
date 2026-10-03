package com.tpverp.saas.marketing;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
