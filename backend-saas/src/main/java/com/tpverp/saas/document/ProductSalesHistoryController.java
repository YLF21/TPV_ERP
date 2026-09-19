package com.tpverp.saas.document;

import static com.tpverp.saas.document.ProductSalesHistoryApi.*;

import org.springframework.http.CacheControl;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-sales-history")
public class ProductSalesHistoryController {
    private final ProductSalesHistoryService service;
    public ProductSalesHistoryController(ProductSalesHistoryService service) { this.service = service; }
    @PostMapping("/page")
    public ResponseEntity<Response> page(@RequestHeader(value="X-TPV-Installation-Token", required=false) String token,
            @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(request, token));
    }
    @PostMapping("/export")
    public ResponseEntity<Response> export(@RequestHeader(value="X-TPV-Installation-Token", required=false) String token,
            @RequestBody Request request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.export(request, token));
    }
    @ExceptionHandler(ProductSalesHistoryService.QueryFailure.class)
    public ResponseEntity<ProblemDetail> limit(ProductSalesHistoryService.QueryFailure failure) {
        var problem = ProblemDetail.forStatusAndDetail(failure.getStatusCode(), failure.getReason());
        problem.setProperty("code", failure.getCode());
        return ResponseEntity.status(failure.getStatusCode()).cacheControl(CacheControl.noStore()).body(problem);
    }
}
