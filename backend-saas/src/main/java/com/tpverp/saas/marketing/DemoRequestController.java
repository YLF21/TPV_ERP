package com.tpverp.saas.marketing;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/demo-requests")
public class DemoRequestController {

    private final DemoRequestService service;

    public DemoRequestController(DemoRequestService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DemoRequestReceipt> create(@Valid @RequestBody CreateDemoRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(request));
    }
}
