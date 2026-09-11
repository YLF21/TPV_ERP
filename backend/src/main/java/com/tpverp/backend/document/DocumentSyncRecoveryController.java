package com.tpverp.backend.document;

import static com.tpverp.backend.document.DocumentSyncRecoveryApi.*;

import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@ConditionalOnProperty(name = "tpv.sync.document-recovery-enabled", havingValue = "true")
@RequestMapping("/api/v1/sync/document-recovery")
@PreAuthorize("hasRole('ADMIN')")
public class DocumentSyncRecoveryController {
    private final DocumentSyncRecoveryService service;
    public DocumentSyncRecoveryController(DocumentSyncRecoveryService service) { this.service = service; }
    @PostMapping("/preview")
    public ResponseEntity<Preview> preview(@Valid @RequestBody PreviewRequest request, Authentication auth) {
        return result(service.preview(request, auth));
    }
    @PostMapping("/prepare")
    public ResponseEntity<Prepared> prepare(@Valid @RequestBody PrepareRequest request, Authentication auth) {
        return result(service.prepare(request, auth));
    }
    @PostMapping("/verify")
    public ResponseEntity<Verified> verify(@Valid @RequestBody VerifyRequest request, Authentication auth) {
        return result(service.verify(request, auth));
    }
    @ExceptionHandler(DocumentSyncRecoveryException.class)
    ResponseEntity<ProblemDetail> failure(DocumentSyncRecoveryException exception) {
        var detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setProperty("code", exception.getMessage());
        return ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(detail);
    }
    private static <T> ResponseEntity<T> result(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
