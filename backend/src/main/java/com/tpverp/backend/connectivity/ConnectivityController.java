package com.tpverp.backend.connectivity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConnectivityController {
    private final SaasConnectivityService service;

    public ConnectivityController(SaasConnectivityService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/connectivity")
    public ResponseEntity<SaasConnectivityService.ConnectivityStatus> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status());
    }
}
