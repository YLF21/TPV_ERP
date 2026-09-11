package com.tpverp.backend.excel;

import com.tpverp.backend.security.domain.UserAccount;
import com.tpverp.backend.shared.api.ApiExceptionContext;
import com.tpverp.backend.shared.api.CorrelationIdFilter;
import com.tpverp.backend.shared.i18n.SupportedLanguage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer-document-reports")
public class CustomerDocumentExcelExportController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private final CustomerDocumentExcelExportService service;

    public CustomerDocumentExcelExportController(CustomerDocumentExcelExportService service) {
        this.service = service;
    }

    @PostMapping(value = "/export.xlsx", produces = XLSX)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('GESTION_VENTAS','VENTA') "
            + "or (#request.reportKey == 'tickets' and hasAuthority('TICKETS_READ')) "
            + "or (#request.reportKey == 'invoices' and hasAuthority('INVOICES_READ')) "
            + "or (#request.reportKey == 'delivery-notes' and hasAuthority('DELIVERY_NOTES_READ'))")
    public ResponseEntity<byte[]> export(
            @Valid @RequestBody CustomerDocumentExportRequest request,
            Authentication authentication) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(XLSX))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("documentos-cliente.xlsx").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(service.export(request, authentication));
    }

    @ExceptionHandler(CustomerDocumentExcelExportService.ExportLimitExceededException.class)
    ResponseEntity<ProblemDetail> limitExceeded(
            HttpServletRequest request, Authentication authentication) {
        var language = authentication != null && authentication.getPrincipal() instanceof UserAccount user
                ? user.getIdioma() : SupportedLanguage.fromHeader(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        var detail = switch (language) {
            case EN -> "The export exceeds 50,000 rows. Narrow the filters and try again.";
            case ZH -> "导出超过 50,000 行。请缩小筛选范围后重试。";
            default -> "La exportación supera 50.000 filas. Reduce los filtros y vuelve a intentarlo.";
        };
        var code = "customer_documents_export_limit_exceeded";
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, detail);
        problem.setType(URI.create("urn:tpv-erp:error:" + code));
        problem.setProperty("code", code);
        problem.setProperty("maxRows", CustomerDocumentExcelExportService.MAX_ROWS);
        problem.setProperty("locale", language.localeCode());
        problem.setProperty("traceId", CorrelationIdFilter.getOrCreate(request));
        ApiExceptionContext.record(request, code, ApiExceptionContext.API_EXCEPTION_HANDLER_STAGE);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
