package com.tpverp.backend.excel;

import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_ALMACEN;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_PRODUCTO;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.GESTION_VENTAS;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.STOCK_READ;
import static com.tpverp.backend.security.application.CorePermissionBootstrap.VENTA;

import com.tpverp.backend.organization.CurrentOrganization;
import com.tpverp.backend.security.application.PermissionChecks;
import com.tpverp.backend.security.domain.UserAccount;
import java.util.UUID;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stock/exports")
@PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('" + STOCK_READ + "','"
        + GESTION_PRODUCTO + "','" + GESTION_ALMACEN + "','"
        + GESTION_VENTAS + "','" + VENTA + "')")
public class StockExcelExportController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final StockExcelExportService exports;
    private final StockExcelExportJobLauncher launcher;
    private final CurrentOrganization organization;

    public StockExcelExportController(
            StockExcelExportService exports,
            StockExcelExportJobLauncher launcher,
            CurrentOrganization organization) {
        this.exports = exports;
        this.launcher = launcher;
        this.organization = organization;
    }

    @PostMapping
    public StockExcelExportService.JobView create(
            @RequestBody StockExcelExportService.ExportRequest request,
            Authentication authentication) {
        var job = exports.create(
                organization.currentStore().getId(),
                owner(authentication),
                PermissionChecks.hasProductManagement(authentication),
                request);
        launcher.launch(job.id());
        return job;
    }

    @GetMapping("/{jobId}")
    public StockExcelExportService.JobView status(
            @PathVariable UUID jobId,
            Authentication authentication) {
        return exports.status(jobId, organization.currentStore().getId(),
                owner(authentication));
    }

    @GetMapping("/{jobId}/file")
    public ResponseEntity<FileSystemResource> file(
            @PathVariable UUID jobId,
            Authentication authentication) {
        var file = exports.file(jobId, organization.currentStore().getId(),
                owner(authentication));
        return ResponseEntity.ok()
                .contentType(XLSX)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"")
                .body(new FileSystemResource(file.path()));
    }

    static String owner(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof UserAccount user) {
            return user.getId().toString();
        }
        return authentication == null ? null : authentication.getName();
    }
}
