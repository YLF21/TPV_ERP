package com.tpverp.saas.connectivity;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, side-effect-free reachability probe; exposes no tenant or installation information. */
@RestController
public class ConnectivityPingController {
    @GetMapping("/api/v1/connectivity/ping")
    public ResponseEntity<Ping> ping() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new Ping("tpv-erp-saas", "UP"));
    }

    public record Ping(String service, String status) { }
}
