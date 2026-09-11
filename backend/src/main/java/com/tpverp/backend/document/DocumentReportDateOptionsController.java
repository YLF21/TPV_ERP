package com.tpverp.backend.document;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/document-reports/date-options")
public class DocumentReportDateOptionsController {
    private final DocumentReportDateOptionsService service;

    public DocumentReportDateOptionsController(DocumentReportDateOptionsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public DocumentReportDateOptionsService.DateOptions options(
            @RequestParam String report, Authentication authentication) {
        return service.options(report, authentication);
    }
}
