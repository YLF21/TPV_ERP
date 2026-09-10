package com.tpverp.backend.document;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-document-reports")
public class CustomerModel347Controller {

    private final CustomerModel347Service service;

    public CustomerModel347Controller(CustomerModel347Service service) {
        this.service = service;
    }

    @GetMapping(value = "/{customerId}/model-347.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA','INVOICES_READ')")
    public ResponseEntity<byte[]> generate(@PathVariable UUID customerId,
            @RequestParam @Min(1) @Max(9998) int year,
            @RequestParam(defaultValue = "es") @Pattern(regexp = "es|en|zh") String locale,
            Authentication authentication) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("modelo-347-" + year + ".pdf").build().toString())
                .body(service.generate(customerId, year, locale, authentication));
    }
}
