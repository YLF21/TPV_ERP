package com.tpverp.saas.reportexport;

import com.tpverp.saas.admin.AdminAuditQuery.Filters;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditExportController {
    private final AuditExportService exports;

    public AuditExportController(AuditExportService exports) {
        this.exports = exports;
    }

    @GetMapping("/api/v1/admin/audit/export")
    public void export(Filters filters, @RequestParam(defaultValue = "es") String language,
            HttpServletResponse response) throws IOException {
        try (var file = exports.prepare(filters, language)) {
            ReportExportController.download(file, "saas-audit", "xlsx", response);
        }
    }
}
