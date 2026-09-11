package com.tpverp.saas.document;

import static com.tpverp.saas.document.CommercialDocumentApi.*;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/commercial-document-queries")
public class CommercialDocumentQueryController {
    private final CommercialDocumentQueryService service;
    public CommercialDocumentQueryController(CommercialDocumentQueryService service) { this.service = service; }

    @PostMapping("/page")
    public ResponseEntity<PageResponse> page(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody PageRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(request, token));
    }

    @PostMapping("/export")
    public ResponseEntity<ExportResponse> export(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody ExportRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.export(request, token));
    }

    @PostMapping("/annual")
    public ResponseEntity<AnnualResponse> annual(
            @RequestHeader(value = "X-TPV-Installation-Token", required = false) String token,
            @Valid @RequestBody AnnualRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.annual(request, token));
    }

    @ExceptionHandler(CommercialDocumentQueryService.QueryFailure.class)
    public ResponseEntity<ProblemDetail> queryFailure(CommercialDocumentQueryService.QueryFailure failure) {
        var problem = ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
        problem.setProperty("code", failure.getCode());
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(problem);
    }
}
