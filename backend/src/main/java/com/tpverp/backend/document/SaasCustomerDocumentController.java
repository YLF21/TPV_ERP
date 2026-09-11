package com.tpverp.backend.document;

import com.tpverp.backend.document.SaasCustomerDocumentApi.ExportRequest;
import com.tpverp.backend.document.SaasCustomerDocumentApi.Filters;
import com.tpverp.backend.document.SaasCustomerDocumentApi.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customer-document-reports/saas")
public class SaasCustomerDocumentController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final SaasCustomerDocumentService service;
    public SaasCustomerDocumentController(SaasCustomerDocumentService service) { this.service = service; }

    @GetMapping("/{customerId}/{reportKey}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA') "
            + "or (#reportKey == 'tickets' and hasAuthority('TICKETS_READ')) "
            + "or (#reportKey == 'invoices' and hasAuthority('INVOICES_READ')) "
            + "or (#reportKey == 'delivery-notes' and hasAuthority('DELIVERY_NOTES_READ'))")
    public ResponseEntity<Page> page(@PathVariable UUID customerId, @PathVariable String reportKey,
            @RequestParam(required = false) String search, @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortDirection,
            @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size,
            @RequestParam(required = false) @Size(max = 2048) String cursor,
            Authentication authentication) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(service.page(customerId, reportKey,
                new Filters(search, status, dateFrom, dateTo), sortBy, sortDirection, size, cursor, authentication));
    }
    @PostMapping(value = "/export.xlsx", produces = XLSX)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA') "
            + "or (#request.reportKey == 'tickets' and hasAuthority('TICKETS_READ')) "
            + "or (#request.reportKey == 'invoices' and hasAuthority('INVOICES_READ')) "
            + "or (#request.reportKey == 'delivery-notes' and hasAuthority('DELIVERY_NOTES_READ'))")
    public ResponseEntity<byte[]> export(@Valid @RequestBody ExportRequest request, Authentication authentication) {
        return file(service.export(request, authentication), XLSX, "documentos-cliente-saas.xlsx");
    }
    @GetMapping(value = "/{customerId}/annual.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA','INVOICES_READ')")
    public ResponseEntity<byte[]> annual(@PathVariable UUID customerId, @RequestParam @Min(1) @Max(9998) int year,
            @RequestParam(defaultValue = "es") @Pattern(regexp = "es|en|zh") String locale, Authentication authentication) {
        return file(service.annual(customerId, year, locale, authentication), MediaType.APPLICATION_PDF_VALUE,
                "resumen-anual-cliente-" + year + ".pdf");
    }
    @ExceptionHandler(SaasCustomerDocumentException.class)
    ResponseEntity<ProblemDetail> unavailable(SaasCustomerDocumentException exception) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setType(URI.create("urn:tpv-erp:error:" + exception.getMessage()));
        problem.setProperty("code", exception.getMessage());
        if (exception.getMessage().equals("customer_documents_export_limit_exceeded")) problem.setProperty("maxRows", 50_000);
        return ResponseEntity.status(exception.status()).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
    private static ResponseEntity<byte[]> file(byte[] bytes, String contentType, String name) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString()).body(bytes);
    }
}
