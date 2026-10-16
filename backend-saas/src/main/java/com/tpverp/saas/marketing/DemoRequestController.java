package com.tpverp.saas.marketing;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<DemoRequestReceipt> create(@Valid @RequestBody CreateDemoRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            HttpServletRequest http) {
        UUID parsedKey = null;
        if (key != null) {
            try {
                parsedKey = UUID.fromString(key);
                if (!parsedKey.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException exception) {
                throw new DemoRequestFailure(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Clave de reintento invalida", Map.of("idempotencyKey", "INVALID"), null);
            }
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(service.submit(request, parsedKey, http.getRemoteAddr()));
    }

    @GetMapping("/availability")
    public ResponseEntity<Map<String, Boolean>> availability() {
        if (!service.available()) {
            throw new DemoRequestFailure(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "La recepcion de solicitudes no esta disponible temporalmente");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("available", true));
    }
}
