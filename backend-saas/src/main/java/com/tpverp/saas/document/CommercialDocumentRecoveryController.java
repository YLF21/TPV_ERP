package com.tpverp.saas.document;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commercial-document-queries")
public class CommercialDocumentRecoveryController {
    private final CommercialDocumentRecoveryService service;

    public CommercialDocumentRecoveryController(CommercialDocumentRecoveryService service) { this.service = service; }

    @PostMapping("/recovery-status")
    public ResponseEntity<CommercialDocumentRecoveryApi.Response> status(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody CommercialDocumentRecoveryApi.Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(request, token));
    }
}
