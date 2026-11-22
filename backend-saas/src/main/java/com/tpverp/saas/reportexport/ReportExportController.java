package com.tpverp.saas.reportexport;

import com.tpverp.saas.admin.AdminAuditService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/reports/export")
public class ReportExportController {
    private static final Set<String> RESOURCES =
            Set.of("statistics", "articles", "documents", "movements", "inventory", "comparison");
    private static final Set<String> FORMATS = Set.of("csv", "xlsx");
    private static final Set<String> LANGUAGES = Set.of("es", "en", "zh");
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ReportExportService service;
    private final AdminAuditService audit;

    public ReportExportController(ReportExportService service, AdminAuditService audit) {
        this.audit = audit;
        this.service = service;
    }

    @GetMapping("/{resource}")
    public void export(@PathVariable String resource, @RequestParam Map<String, String> parameters,
            HttpServletResponse response) throws IOException {
        if (!RESOURCES.contains(resource)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Informe no encontrado");
        }
        String format = parameters.getOrDefault("format", "csv");
        String language = parameters.getOrDefault("language", "es");
        if (!FORMATS.contains(format) || !LANGUAGES.contains(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Formato de exportación no válido");
        }
        try (var report = service.prepare(resource, parameters, format, language)) {
            audit.log("EXPORT_SAAS_REPORT", "REPORT", resource, format);
            download(report, resource, format, response);
        }
    }

    static void download(ExportFiles.PreparedReport report, String resource, String format, HttpServletResponse response)
            throws IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("espos-" + resource + "." + format).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType(format.equals("csv") ? "text/csv;charset=UTF-8" : XLSX);
        response.setContentLengthLong(report.size());
        report.writeTo(response.getOutputStream());
    }
}
